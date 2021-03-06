(ns kixi.hecuba.data.batch-checks
  "Batch validation jobs scheduled using quartz scheduler."
  (:require [clj-time.core :as t]
            [com.stuartsierra.frequencies :as freq]
            [kixi.hecuba.storage.db :as db]
            [qbits.hayt :as hayt]
            [kixi.hecuba.data :as data]
            [kixi.hecuba.time :as time]
            [kixi.hecuba.data.validate :as v]
            [kixi.hecuba.data.calculate :as c]
            [kixi.hecuba.data.measurements :as measurements]
            [clojure.tools.logging :as log]))

;;; Check for mislabelled sensors ;;;

(defn going-up?
  "Check if all measurements are going up. Assumes the measurements
  are sorted by their timestamp."
  [measurements]
  (apply <= (map :value measurements)))

(defmulti labelled-correctly?
  "Dispatches a call to a specific device period, where
   appropriate checks are performed."
  (fn [sensor measurements] (:period sensor)))

(defmethod labelled-correctly? "INSTANT" [sensor measurements] true)
(defmethod labelled-correctly? "CUMULATIVE" [sensor measurements] (going-up? (measurements/sort-measurments measurements)))
(defmethod labelled-correctly? "PULSE" [sensor measurements] (empty? (filter #(neg? (:value %)) measurements)))

(defn mislabelled-check
  "Takes an hour worth of measurements and checks if the sensor is labelled correctly according to
  rules in labelled-correctly?"
  [store {:keys [device_id sensor_id type period] :as sensor} start-date]
  (db/with-session [session (:hecuba-session store)]
    (let [end-date     (t/plus start-date (t/hours 1))
          month        (time/get-month-partition-key start-date)
          where        [[= :device_id device_id] [= :sensor_id sensor_id]
                        [= :month month] [>= :timestamp start-date] [< :timestamp end-date]]
          measurements (filter #(number? (:value %))
                               (measurements/parse-measurements (db/execute session
                                                                            (hayt/select :partitioned_measurements
                                                                                         (hayt/where where)))))]
      (when-not (empty? measurements)
        (db/execute session
                    (hayt/update :sensor_metadata
                                 (hayt/set-columns {:mislabelled (if (labelled-correctly? sensor measurements)
                                                                   "false"
                                                                   "true")})
                                 (hayt/where [[= :device_id device_id]
                                              [= :sensor_id sensor_id]]))))
      end-date)))

(defn mislabelled-sensors
  "Checks for mislabelled sensors."
  [store {:keys [sensor range]}]
  (let [{:keys [start-date end-date]} range]
    (loop [start start-date]
      (when-not (t/before? end-date start)
        (recur (mislabelled-check store sensor start))))))

;;;;;;;;;;;; Batch check for spiked measurements ;;;;;;;;;
(defn label-spikes
  "Checks a sequence of measurement against the most recent recorded median. Overwrites the measurement with updated
  metadata."
  [store {:keys [device_id type median sensor_id] :as sensor} start-date]
  (when-not (nil? median)
    (db/with-session [session (:hecuba-session store)]
      (let [end-date     (t/plus start-date (t/hours 1))
            month        (time/get-month-partition-key start-date)
            where        [[= :device_id device_id] [= :sensor_id sensor_id]
                          [= :month month] [>= :timestamp start-date] [< :timestamp end-date]]
            measurements (filter #(measurements/metadata-is-number? %)
                                 (db/execute session (hayt/select :partitioned_measurements (hayt/where where))))
            spikes       (map #(hash-map :timestamp (:timestamp %)
                                         :spike (str (v/larger-than-median median %))) measurements)]
        (db/execute session
                    (hayt/batch
                     (apply hayt/queries (map #(hayt/update :partitioned_measurements
                                                            (hayt/set-columns {:reading_metadata [+ {"median-spike" (:spike %)}]})
                                                            (hayt/where  [[= :device_id device_id]
                                                                          [= :sensor_id sensor_id]
                                                                          [= :month (time/get-month-partition-key (:timestamp %))]
                                                                          [= :timestamp (:timestamp %)]]))
                                                         spikes))))
        end-date))))

(defn median-spike-check
  "Check of median spikes. It re-checks all measurements that have had median calculated."
  [store {:keys [sensor range]}]
  (let [{:keys [start-date end-date]} range]
    (loop [start start-date]
      (when-not (t/before? end-date start)
        (recur (label-spikes store sensor start))))))

;;;;;;;;;;;;; Batch median calculation ;;;;;;;;;;;;;;;;;

(defn remove-bad-readings
  "Filters out errored measurements."
  [m]
  (and (= "true" (get-in m [:metadata "is-number"]))
       (not (zero? (get-in m [:value])))
       (not= "true" (get-in m [:metadata "median-spike"]))))

(defn median
  "Find median of a lazy sequency of measurements.
  Filters out 0s and invalid measurements (non-numbers)."
  [measurements]
  (when (not (empty? measurements))
    (freq/median (frequencies (map :value measurements)))))

(defn update-median
  "Calculates and updates median for a given sensor."
  [store {:keys [device_id type period sensor_id]} start-date]
  (db/with-session [session (:hecuba-session store)]
    (let [end-date     (t/plus start-date (t/hours 1))
          month        (time/get-month-partition-key start-date)
          type         (if (= period "CUMULATIVE") (str type "_differenceSeries") type)
          where        [[= :device_id device_id] [= :sensor_id sensor_id]
                        [= :month month] [>= :timestamp start-date] [< :timestamp end-date]]
          measurements (measurements/parse-measurements (db/execute session
                                                                    (hayt/select :partitioned_measurements (hayt/where where))))
          median       (cond
                        (= "CUMULATIVE" period) (median (filter #(number? (:value %)) measurements))
                        (= "INSTANT" period) (median (filter #(remove-bad-readings %) measurements)))]
      (when (number? median)
        (db/execute session
                    (hayt/update :sensors
                                 (hayt/set-columns {:median median})
                                 (hayt/where [[= :device_id device_id] [= :sensor_id sensor_id]]))))
      end-date)))

(defn median-calculation
  "Retrieves all sensors that either have not had median calculated or the calculation took place over a week ago.
  It iterates over the list of sensors, returns measurements for each and performs calculation.
  Measurements are retrieved in batches."
  [store {:keys [sensor range]}]
  (let [period (-> sensor :period)
        {:keys [start-date end-date]} range]
    (loop [start start-date]
      (when-not (t/before? end-date start)
        (recur (update-median store sensor start))))))

(defn- status-from-measurement-errors
  "If 10% of measurements are invalid, device is broken."
  [events errors]
  (if (and (not (zero? errors))
           (not (zero? events))
           (> (/ errors events) 0.1))
    "Broken"
    "Ok"))

(defn- errored? [m]
  (or (measurements/metadata-is-spike? m)
      (not (empty? (:error m))) ;; TODO need to test whether C* doesn't have stringified "null" stored.
      (and (not (measurements/metadata-is-number? m))
           (not= "N/A" (:value m)))))

;; TODO batch because reduce will realise whole seq
(defn sensor-status
  "Takes the last day worth of measurements, checks their metadata
  and updates sensor's status accordingly."
  [store {:keys [sensor range]}]
  (let [measurements (measurements/measurements-for-range store sensor range (t/hours 1))]
    (when-not (empty? measurements)
      (let [{:keys [events errors]} (reduce (fn [{:keys [events errors]} m]
                                              {:events (inc events)
                                               :errors (if (errored? m) (inc errors) errors)})
                                            {:events 0 :errors 0} measurements)
            status (status-from-measurement-errors events errors)]
        (db/with-session [session (:hecuba-session store)]
          (db/execute session (hayt/update :sensors
                                           (hayt/set-columns {:status status})
                                           (hayt/where (data/where-from sensor)))))))))
