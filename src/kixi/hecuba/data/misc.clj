(ns kixi.hecuba.data.misc
  "Common functions"
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [kixi.hecuba.storage.db :as db]
            [qbits.hayt :as hayt]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]))


(def truthy? #{"true"})

(defn metadata-is-number? [{:keys [reading_metadata] :as m}]
  (truthy? (get reading_metadata "is-number")))

(defn metadata-is-spike? [{:keys [reading_metadata] :as m}]
  (truthy? (get reading_metadata "median-spike")))

;; FIXME: This should move to kixi.hecuba.data
(defn where-from
  "Takes measurement or sensor and returns where clause"
  [m]
  [[= :device_id (:device_id m)] [= :type (:type m)]])

;;;;; Time conversion functions ;;;;;
;; FIXME: These should all be moved to kixi.hecuba.time

(defn hourly-timestamp [t]
  (tc/to-date (tf/unparse (tf/formatters :date-hour) (tc/from-date t))))
(defn daily-timestamp [t]
  (tc/to-date (tf/unparse (tf/formatters :date) (tc/from-date t))))

(defn get-year-partition-key [timestamp] (Long/parseLong (format "%04d" (t/year timestamp))))

(defmulti get-month-partition-key type)
(defmethod get-month-partition-key java.util.Date [t]
  (let [timestamp (tc/from-date t)] (Long/parseLong (format "%04d%02d" (t/year timestamp) (t/month timestamp)))))
(defmethod get-month-partition-key org.joda.time.DateTime [t] (Long/parseLong (format "%04d%02d" (t/year t) (t/month t))))

(defmulti truncate-seconds type)
(defmethod truncate-seconds java.util.Date [t]
  (let [time-str (tf/unparse (tf/formatter "yyyy-MM-dd'T'HH:mm") (tc/from-date t))]
    (tc/to-date  (tf/parse (tf/formatter "yyyy-MM-dd'T'HH:mm") time-str))))
(defmethod truncate-seconds org.joda.time.DateTime [t]
  (let [time-str (tf/unparse (tf/formatter "yyyy-MM-dd'T'HH:mm") t)]
    (tf/parse (tf/formatter "yyyy-MM-dd'T'HH:mm") time-str)))

;;;;; Last check functions ;;;;;

(defn all-sensors
  "Given a querier, retrieves all sensors data joined with their metadata."
  [store]
  (db/with-session [session (:hecuba-session store)]
    (let [all-sensors-metadata (db/execute session (hayt/select :sensor_metadata))]
      (map #(merge (first (db/execute session
                                      (hayt/select :sensors
                                                   (hayt/where [[= :device_id (:device_id %)] [= :type (:type %)]]))))
                   %) all-sensors-metadata))))

(defn merge-sensor-metadata [store sensor]
  (db/with-session [session (:hecuba-session store)]
    (merge (first (db/execute session (hayt/select :sensor_metadata
                                                   (hayt/where [[= :device_id (:device_id sensor)]
                                                                [= :type (:type sensor)]])))) sensor)))

(defn all-sensor-information
  "Given store,  device_id and type, combines data from sensor and sensor_metadata tables
  for that sensor."
  [store device_id type]
  (db/with-session [session (:hecuba-session store)]
    (let [sensor (first (db/execute session
                                    (hayt/select :sensors (hayt/where [[= :device_id device_id]
                                                                       [= :type type]]))))]
      (merge-sensor-metadata store sensor))))

(defn all-sensor-metadata-for-device
  "Given device_id, retrieves all sensors metadata."
  [store device_id]
  (db/with-session [session (:hecuba-session store)]
    (db/execute session (hayt/select :sensor_metadata (hayt/where [[= :device_id device_id]])))))

(defn start-end-dates
  "Given a sensor and column name returns start and end dates for (re)calculations."
  [column sensor]
  (let [range (-> sensor column)
        start (get range "start")
        end   (get range "end")]
    (when (and (not (nil? start))
               (not (nil? end))
               (not= start end))
      {:start-date (tc/from-date start) :end-date (tc/from-date end)})))

(defn min-max-dates
  "Takes a sequence of measurements and retuns a map
  of min and max dates for that sequence. It should be passed
  a batch of measurements."
  [measurements]
  (assert (not (empty? measurements)) "No measurements passed to min-max-dates")
  (let [parsed-dates (map #(tc/from-date (:timestamp %)) measurements)]
    (reduce (fn [{:keys [min-date max-date]} timestamp]
              {:min-date (if (t/before? timestamp min-date) timestamp min-date)
               :max-date (if (t/after? timestamp max-date) timestamp max-date)})
            {:min-date (first parsed-dates)
             :max-date (first parsed-dates)} parsed-dates)))

(defn range-for-all-sensors
  "Takes a sequence of sensors and returns min and max dates from their lower_ts and upper_ts"
  [sensors]
  (assert (not (empty? sensors)) "Sensors passed to range-for-all-sensors are empty.")
  (when (every? #(not (nil? %)) (map :lower_ts sensors))
    (let [all-starts (map #(tc/from-date (:lower_ts %)) sensors)
          all-ends   (map #(tc/from-date (:upper_ts %)) sensors)
          min-date   (tc/to-date-time (apply min (map tc/to-long all-starts)))
          max-date   (tc/to-date-time (apply max (map tc/to-long all-ends)))]
      [min-date max-date])))

(defn calculate-range
  "Takes a start/end range from sensor_metadata \"dirty dates\" and a period,
  and returns range (number of months from end-date) to calculate."
  [{:keys [start-date end-date]} period]
  (let [start (t/minus end-date period)]
    {:start-date start :end-date end-date}))

;;;;; Parsing of measurements ;;;;;

(defn numbers-as-strings? [& strings]
  (every? #(re-find #"^-?\d+(?:\.\d+)?$" %) strings))

(defn parse-double [txt]
  (Double/parseDouble txt))

(defn parse-value
  [m]
  (assoc-in m [:value] (parse-double (:value m))))

(defn sort-measurments
  [m]
  (sort-by :timestamp m) m)

(defn parse-measurements
  "Takes measurements in the format returned from the database.
   Returns a list of maps, with all values parsed approprietly."
  [measurements]
  (map (fn [m] (assoc-in m [:value]
                         (if (metadata-is-number? m)
                           (edn/read-string (:value m))
                           nil)))
       measurements))

(defn find-broken-sensors
  "Finds sensors with bad metadata and label as broken.
  Returns sequence of maps."
  [store]
  (let [where [= :status "Broken"]]
    (db/with-session [session (:hecuba-session store)]
      (db/execute session
                  (hayt/select :sensors (hayt/where where))))))

(defn find-mislabelled-sensors
  "Finds sensots that are marked as mislabelled.
  Returns sequence of maps."
  [store]
  (let [where [= :mislabelled "true"]]
    (db/with-session [session (:hecuba-session store)]
      (db/execute session
                  (hayt/select :sensor_metadata (hayt/where where))))))

(defn reset-date-range
  "Given querier, commander, sensor, column and start/end dates, update these dates in sensor metadata."
  [store {:keys [device_id type]} col start-date end-date]
  (db/with-session [session (:hecuba-session store)]
    (let [where               [[= :device_id device_id] [= :type type]]
          current-metadata    (first (db/execute session (hayt/select :sensor_metadata (hayt/where where))))
          current-range       (get current-metadata col)
          start               (get current-range "start")
          end                 (get current-range "end")]
      (when (and start end)
        (let [current-start (tc/from-date start)
              current-end   (tc/from-date end)]
          (when-not (and (t/before? current-start start-date)
                         (t/after? current-end end-date))
            (db/execute session (hayt/update :sensor_metadata (hayt/set-columns {col nil}) (hayt/where where)))))))))

;; Datasets helper functions

(defn output-unit-for [t]
  (log/error t)
  (case (.toUpperCase t)
    "VOL2KWH" "kWh"
    "KWH2CO2" "co2"))

(defn output-type-for [t operation]
  (case (.toUpperCase operation)
    "VOL2KWH" (str t "_kwh")
    "KWH2CO2" (str t "_co2")))

(defn update-date-range [sensor column min-date max-date]
  (let [existing-range (get sensor column)
        start          (get existing-range "start")
        end            (get existing-range "end")]
    (assert (and (not (nil? min-date)) (not (nil? max-date))) (format "Min and max dates are null. Sensor: %s,  column: %s" sensor column))
    (cond
     (or (nil? start) (nil? end)) {"start" min-date "end" max-date}
     (.before min-date start) {"start" min-date}
     (.after max-date end) {"end" max-date})))

(defn update-bounds [min-date max-date {:keys [lower_ts upper_ts]}]
  (cond
   (and (nil? lower_ts) (nil? upper_ts)) {:upper_ts max-date :lower_ts min-date}
   (.before min-date lower_ts) {:lower_ts min-date}
   (.after max-date upper_ts) {:upper_ts max-date}))

(defn columns-to-update?
  "It returns columns to update, if there are any. Otherwise it returns nil."
  [sensor start end new-bounds]
  (merge
   (when-let [rollups (update-date-range sensor :rollups start end)]
     {:rollups [+ rollups]})
   (when-let [mislabelled (update-date-range sensor :mislabelled_sensors_check start end)]
     {:mislabelled_sensors_check [+ mislabelled]})
   (when-let [difference (update-date-range sensor :difference_series start end)]
     {:difference_series [+ difference]})
   (when-let [median (update-date-range sensor :median_calc_check start end)]
     {:median_calc_check [+ median]})
   (when-let [spikes (update-date-range sensor :spike_check start end)]
     {:spike_check [+ spikes]})
   (when-let [co2 (update-date-range sensor :co2 start end)]
     {:co2 [+ co2]})
   (when-let [kwh (update-date-range sensor :kwh start end)]
     {:kwh [+ kwh]})
   (when-let [datasets (update-date-range sensor :calculated_datasets start end)]
     {:calculated_datasets [+ datasets]})
   (when-let [actual-annual-calculation (update-date-range sensor :actual_annual_calculation start end)]
     {:actual_annual_calculation [+ actual-annual-calculation]})
   (when-let [lower (:lower_ts new-bounds)] {:lower_ts lower})
   (when-let [upper (:upper_ts new-bounds)] {:upper_ts upper})))

(defn update-sensor-metadata
  "Updates start and end dates when new measurement is received."
  [session sensor min-date max-date]
  (let [start      (tc/to-date min-date)
        end        (tc/to-date max-date)
        where      (where-from sensor)
        s          (first (db/execute session
                                      (hayt/select :sensor_metadata
                                                   (hayt/where where))))
        new-bounds (update-bounds start end s)]
    (when-let [columns (columns-to-update? s start end new-bounds)]
      (db/execute session
                  (hayt/update :sensor_metadata
                               (hayt/set-columns columns)
                               (hayt/where where))))))


(defn prepare-batch
  "Creates a CQL batch statement. For performance reasons
   it should be passed a page of measurements."
  [measurements]
  (hayt/batch
   (apply hayt/queries (map #(hayt/insert :partitioned_measurements (hayt/values %)) measurements))
   (hayt/logged false)))

(defn insert-batch [session batch]
  (let [batch-statement (prepare-batch batch)]
    (db/execute session batch-statement)))

(defn insert-measurements
  "Takes store, lazy sequence of measurements and
   size of the batches and inserts them into the database."
  [store sensor page measurements]
  (db/with-session [session (:hecuba-session store)]
    (doseq [batch (partition-all page measurements)]
      (let [{:keys [min-date max-date]} (min-max-dates batch)]
        (insert-batch session batch)
        (update-sensor-metadata session sensor min-date max-date)))))

(defn deep-merge
  "Recursively merges maps. If keys are not maps, the last value wins."
  [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))
