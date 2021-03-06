(ns kixi.hecuba.controller.pipeline
  "Pipeline for scheduled jobs."
  (:require [kixipipe.pipeline                      :refer [defnconsumer produce-item produce-items submit-item] :as p]
            [pipejine.core                          :as pipejine :refer [new-queue producer-of]]
            [clojure.tools.logging                  :as log]
            [kixipipe.storage.s3                    :as s3]
            [kixi.hecuba.data.batch-checks          :as checks]
            [kixi.hecuba.data.calculate             :as calculate]
            [kixi.hecuba.data.calculated-fields     :as fields]
            [kixi.hecuba.data.measurements.download :as measurements-download]
            [kixi.hecuba.data.measurements.upload   :as measurements-upload]
            [kixi.hecuba.data.entities.upload       :as entities-upload]
            [kixi.hecuba.data.sensors               :as sensors]
            [kixi.hecuba.time                       :as time]
            [clj-time.core                          :as t]
            [clj-time.coerce                        :as tc]
            [com.stuartsierra.component             :as component]
            [kixi.hecuba.data.datasets              :as dd]
            [kixi.hecuba.api.datasets               :as datasets]
            [kixi.hecuba.storage.db                 :as db]
            [kixi.hecuba.data.devices               :as devices]
            [kixi.hecuba.data.entities.search       :as search]
            [clojure.string                         :as string]
            [clojure.edn                            :as edn]))

(defn build-pipeline [store]
  (let [fanout-q                (new-queue {:name "fanout-q" :queue-size 50})
        data-quality-q          (new-queue {:name "data-quality-q" :queue-size 50})
        median-calculation-q    (new-queue {:name "median-calculation-q" :queue-size 50})
        mislabelled-sensors-q   (new-queue {:name "mislabelled-sensors-q" :queue-size 50})
        rollups-q               (new-queue {:name "rollups-q" :queue-size 50})
        spike-check-q           (new-queue {:name "spike-check-q" :queue-size 50})
        synthetic-readings-q    (new-queue {:name "synthetic-readings-q" :queue-size 50})
        resolution-q            (new-queue {:name "resolution-q" :queue-size 50})
        difference-series-q     (new-queue {:name "difference-series-q" :queue-size 50})
        convert-to-co2-q        (new-queue {:name "convert-to-co2-q" :queue-size 50})
        convert-to-kwh-q        (new-queue {:name "convert-to-kwh-q" :queue-size 50})
        sensor-status-q         (new-queue {:name "sensor-status" :queue-size 50})
        actual-annual-q         (new-queue {:name "actual-annual-q" :queue-size 50})
        store-upload-s3-q       (new-queue {:name "store-upload-s3-q" :queue-size 50})
        upload-measurements-q   (new-queue {:name "upload-measurements-q" :queue-size 50 :number-of-consumer-threads 4})
        upload-images-q         (new-queue {:name "image-upload-q" :queue-size 10})
        upload-documents-q      (new-queue {:name "document-upload-q" :queue-size 10})
        download-measurements-q (new-queue {:name "download-measurements-q" :queue-size 50 :number-of-consumer-threads 2})
        recalculate-q           (new-queue {:name "recalculate-q" :queue-size 10})]

    (defnconsumer fanout-q [{:keys [dest type] :as item}]
      (let [item (dissoc item :dest)]
        (condp = dest
          :data-quality (condp = type
                          :median-calculation  (produce-item item median-calculation-q)
                          :mislabelled-sensors (produce-item item mislabelled-sensors-q)
                          :spike-check         (produce-item item spike-check-q)
                          :resolution          (produce-item item resolution-q)
                          :sensor-status       (produce-item item sensor-status-q))
          :calculated-datasets (condp = type
                                 :rollups            (produce-item item rollups-q)
                                 :synthetic-readings (produce-item item synthetic-readings-q)
                                 :difference-series  (produce-item item difference-series-q)
                                 :convert-to-co2     (produce-item item convert-to-co2-q)
                                 :convert-to-kwh     (produce-item item convert-to-kwh-q))
          :calculated-fields (condp = type
                               :actual-annual (produce-item item actual-annual-q))
          :upload (condp = type
                    :measurements (produce-item item upload-measurements-q)
                    :images (produce-item item upload-images-q)
                    :documents (produce-item item upload-documents-q))
          :download (condp = type
                      :measurements (produce-item item download-measurements-q))
          :recalculate (produce-item item recalculate-q))))

    (defn median-calculation [store item]
      (let [{:keys [sensor range]} item
            {:keys [period device_id type]} sensor]
        (when (and period (not= period "PULSE"))
          (log/info  "Calculating median for: " device_id type)
          (checks/median-calculation store item)
          (sensors/reset-date-range store sensor :median_calc_check
                                 (:start-date range)
                                 (:end-date range)))))

    (defnconsumer median-calculation-q [item]
      (log/info "Starting median calculation.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :median_calc_check s)]
            (median-calculation store (assoc item :sensor s :range range)))))
      (log/info "Finished median calculation."))

    (defn mislabelled-sensors [store item]
      (let [{:keys [sensor range]} item
            {:keys [period device_id type]} sensor]
        (when (some #{period} ["INSTANT" "PULSE" "CUMULATIVE"])
          (log/info  "Checking if mislabelled: " device_id type)
          (checks/mislabelled-sensors store item)
          (sensors/reset-date-range store sensor :mislabelled_sensors_check
                                 (:start-date range)
                                 (:end-date range)))))

    (defnconsumer mislabelled-sensors-q [item]
      (log/info "Starting mislabelled sensors check.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :mislabelled_sensors_check s)]
            (mislabelled-sensors store (assoc item :sensor s :range range)))))
      (log/info "Finished mislabelled sensors check."))

    (defn difference-series [store item]
      (let [{:keys [sensor range]} item
            {:keys [period device_id type]} sensor]
        (when (and range (= "CUMULATIVE" period))
          (log/debugf "Started calculating Difference Series for Sensor: %s:%s Start: %s End: %s" device_id type (:start-date range) (:end-date range))
          (try
            (calculate/difference-series store item)
            (sensors/reset-date-range store sensor :difference_series
                                   (:start-date range)
                                   (:end-date range))
            (catch Throwable t
              (log/errorf t "FAILED to calculate Difference Series for Sensor: %s:%s Start: %s End: %s" device_id type (:start-date range) (:end-date range))
              (throw t)))
          (log/debugf "COMPLETED calculating Difference Series for Sensor: %s:%s Start: %s End: %s" device_id type (:start-date range) (:end-date range)))))

    (defnconsumer difference-series-q [item]
      (log/info "Starting calculation of difference series.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :difference_series s)]
            (difference-series store (assoc item :sensor s :range range)))))
      (log/info "COMPLETED calculation of difference series."))

    (defn convert-to-co2 [store item]
      (let [{:keys [sensor range]} item
            {:keys [unit period type device_id]} sensor
            substring? (fn [sub st] (not= (.indexOf st sub) -1))
            regex-seq  ["oil" "gas" "electricity" "kwh"]
            should-convert-type? (fn [type] (some #(substring? % type) regex-seq))]
        (when (and  unit
                    (= "KWH" (.toUpperCase unit))
                    (= "PULSE" period)
                    (should-convert-type? type))
          (log/info  "Converting to co2: " device_id type)
          (calculate/kWh->co2 store item)
          (sensors/reset-date-range store sensor :co2
                                 (:start-date range)
                                 (:end-date range)))))

    (defnconsumer convert-to-co2-q [item]
      (log/info "Starting conversion from kWh to co2.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :co2 s)]
            (convert-to-co2 store (assoc item :sensor s :range range)))))
      (log/info "Finished conversion from kWh to co2."))

    (defn convert-to-kwh [store item]
      (let [{:keys [sensor range]} item
            {:keys [unit device_id type]} sensor]
        (when (and unit
                   (some #(= (.toUpperCase unit) (.toUpperCase %)) ["m^3" "ft^3"])
                   (some #(= type %) ["gasConsumption" "oilConsumption"]))
          (log/info  "Converting to kWh: " device_id type)
          (calculate/gas-volume->kWh store item)
          (sensors/reset-date-range store sensor :kwh
                                 (:start-date range)
                                 (:end-date range)))))

    (defnconsumer convert-to-kwh-q [item]
      (log/info "Starting conversion from vol to kwh.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :kwh s)]
            (convert-to-kwh store (assoc item :sensor s :range range)))))
      (log/info "Finished conversion from vol to kwh."))

    (defn rollups [store item]
      (let [{:keys [sensor range]} item
            {:keys [period device_id type]} sensor]
        (when period
          (log/debugf "Started Rolling Up for Sensor: %s:%s Start: %s End: %s" device_id type (:start-date range) (:end-date range))
          (try
            (calculate/hourly-rollups store item)
            (calculate/daily-rollups store item)
            (sensors/reset-date-range store sensor :rollups
                                   (:start-date range)
                                   (:end-date range))
            (db/with-session [session (:hecuba-session store)]
              (let [{:keys [device_id]} sensor
                    {:keys [entity_id]} (devices/get-by-id session device_id)]
                (-> (search/searchable-entity-by-id entity_id session)
                    (search/->elasticsearch (:search-session store)))))
            (catch Throwable t
              (log/errorf t "FAILED to Roll Up for Sensor: %s:%s Start: %s End: %s" device_id type (:start-date range) (:end-date range))
              (throw t)))
          (log/debugf "COMPLETED Rolling Up for Sensor: %s:%s Start: %s End: %s" device_id type (:start-date range) (:end-date range)))))

    (defnconsumer rollups-q [item]
      (log/info "Starting rollups.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :rollups s)]
            (rollups store (assoc item :sensor s :range range)))))
      (log/info "Finished rollups."))

    (defn spike-check [store item]
      (let [{:keys [s device_id type range period]} item]
        (when (and (not (nil? (:median s)))
                   (not (zero? (:median s)))
                   (not= period "PULSE"))
          (checks/median-spike-check store item))))

    (defnconsumer spike-check-q [item]
      (log/info "Starting median spike check.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :spike_check s)]
            (spike-check store (assoc item :sensor s :range range))
            (sensors/reset-date-range store s :spike_check (:start-date range) (:end-date range)))))
      (log/info "Finished median spike check."))

    (defmulti synthetic-readings (fn [store {:keys [ds sensors range]}] (keyword (:operation ds))))

    (defmethod synthetic-readings :sum [store {:keys [ds sensors range]}]
      (calculate/calculate-dataset store ds sensors range))
    (defmethod synthetic-readings :subtract [store {:keys [ds sensors range]}]
      (calculate/calculate-dataset store ds sensors range))
    (defmethod synthetic-readings :divide [store {:keys [ds sensors range]}]
      (calculate/calculate-dataset store ds sensors range))
    (defmethod synthetic-readings :multiply-series-by-field [store {:keys [ds sensors range]}]
      (let [{:keys [operands]} ds
            [field unit] (string/split (last operands) #"~")]
        (calculate/calculate-dataset store ds sensors range (edn/read-string field))))
    (defmethod synthetic-readings :divide-series-by-field [store {:keys [ds sensors range]}]
      (let [{:keys [operands]} ds
            [field unit] (string/split (last operands) #"~")]
        (calculate/calculate-dataset store ds sensors range (edn/read-string field))))

    (defmulti sensors-for-dataset (fn [ds store] (keyword (:operation ds))))
    (defmethod sensors-for-dataset :sum [ds store]
      (datasets/sensors-for-dataset ds store))
    (defmethod sensors-for-dataset :subtract [ds store]
      (datasets/sensors-for-dataset ds store))
    (defmethod sensors-for-dataset :divide [ds store]
      (datasets/sensors-for-dataset ds store))
    (defmethod sensors-for-dataset :multiply-series-by-field [ds store]
      (let [{:keys [operands]} ds]
        (datasets/sensors-for-dataset {:operands (take 1 operands)} store)))
    (defmethod sensors-for-dataset :divide-series-by-field [ds store]
      (let [{:keys [operands]} ds]
        (datasets/sensors-for-dataset {:operands (take 1 operands)} store)))

    (defnconsumer synthetic-readings-q [item]
      (log/info "Starting synthetic readings job.")
      (db/with-session [session (:hecuba-session store)]
        (let [datasets (dd/get-all session)]
          (doseq [ds datasets]
            (let [sensors (sensors-for-dataset ds store)
                  {:keys [device_id sensor_id]} ds
                  synthetic-sensor (sensors/all-sensor-information store device_id sensor_id)]
              (when-let [range (time/start-end-dates :calculated_datasets synthetic-sensor)]
                (synthetic-readings store (assoc item
                                            :range range
                                            :ds ds :sensors sensors))
                (sensors/reset-date-range store {:device_id device_id :sensor_id sensor_id} :calculated_datasets
                                          (:start-date range)
                                          (:end-date range))
                (db/with-session [session (:hecuba-session store)]
                  (let [{:keys [device_id]} ds
                        {:keys [entity_id]} (devices/get-by-id session device_id)]
                    (-> (search/searchable-entity-by-id entity_id session)
                        (search/->elasticsearch (:search-session store))))))))))
      (log/info "Finished synthetic readings job."))

    (defn actual-annual [store item]
      (let [{:keys [sensor range]} item]
        (when (and (= (:actual_annual sensor) true)
                   (not= (:period sensor) "CUMULATIVE"))
          (log/info "Calculating actual annual for sensor: " (:device_id sensor) (:type sensor))
          (when-let [new-range (time/calculate-range range (t/months 12))]
            (log/info "Calculating actual annual value for last 12 months for sensor: " (:device_id sensor) (:type sensor))
            (fields/calculate store sensor :actual-annual
                              "actual_annual_12months"
                              new-range))
          (when-let [new-range (time/calculate-range range (t/months 1))]
            (log/info "Calculating actual annual value for last 1 month for sensor: " (:device_id sensor) (:type sensor))
            (fields/calculate store sensor :actual-annual
                              "actual_annual_1month"
                              new-range))

          (sensors/reset-date-range store sensor :actual_annual_calculation
                                 (:start-date range)
                                 (:end-date range))
          (db/with-session [session (:hecuba-session store)]
              (let [{:keys [device_id]} sensor
                    {:keys [entity_id]} (devices/get-by-id session device_id)]
                (-> (search/searchable-entity-by-id entity_id session)
                    (search/->elasticsearch (:search-session store))))))))

    (defnconsumer actual-annual-q [item]
      (log/info "Starting calculation of actual annual use.")
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (when-let [range (time/start-end-dates :actual_annual_calculation s)]
            (actual-annual store (assoc item :range range :sensor s)))))
      (log/info "Finished calculation of actual annual use."))

    (defnconsumer resolution-q [item]
      (log/info "Starting resolution check.")
      (calculate/resolution store item)
      (log/info "Finished resolution check."))

    (defnconsumer sensor-status-q [item]
      (log/info "Starting sensor status check.")
      (let [sensors (sensors/all-sensors store)
            end     (t/now)
            start   (t/minus end (t/days 1))]
        (doseq [s sensors]
          (checks/sensor-status store {:sensor s :range {:start-date start :end-date end}})))
      (log/info "Finished sensor status check"))

    (defnconsumer store-upload-s3-q [item]
      (s3/store-file (:s3 store) item)
      (produce-item item upload-measurements-q))

    (defnconsumer upload-measurements-q [item]
      (measurements-upload/upload-item store item))

    (defnconsumer download-measurements-q [item]
      (measurements-download/download-item store item))

    (defnconsumer upload-images-q [item]
      (entities-upload/image-upload store item))

    (defnconsumer upload-documents-q [item]
      (entities-upload/document-upload store item))

    (defn calculate-over-all-sensors [store item calculate-fn]
      (let [sensors (sensors/all-sensors store)]
        (doseq [s sensors]
          (let [{:keys [device_id type lower_ts upper_ts]} s
                range    (when (and lower_ts upper_ts)
                           {:start-date (tc/from-date lower_ts)
                            :end-date (tc/from-date upper_ts)})
                new-item (assoc item :sensor s :range range)]
            (when range
               (calculate-fn store new-item))))))

    (defnconsumer recalculate-q [item]
      (let [sensors     (sensors/all-sensors store)
            calculation (:type item)]
        (log/infof "Starting recalculation of: %s" calculation)
        (condp = calculation
          :synthetic-readings (db/with-session [session (:hecuba-session store)]
                                (let [datasets (dd/get-all session)]
                                  (doseq [ds datasets]
                                    (let [sensors (sensors-for-dataset ds store)
                                          [min-date max-date] (sensors/range-for-all-sensors sensors)
                                          new-item (assoc item :sensors sensors :ds ds :range
                                                          {:start-date min-date :end-date max-date})]
                                      (when (and min-date max-date)
                                        (synthetic-readings store new-item))))))
          :rollups (calculate-over-all-sensors
                    store item rollups)
          :difference-series (calculate-over-all-sensors
                              store item difference-series)
          :convert-to-co2 (calculate-over-all-sensors store item convert-to-kwh)
          :convert-to-kwh (calculate-over-all-sensors store item convert-to-co2)
          :actual-annual (calculate-over-all-sensors store item actual-annual)
          :spike-check (calculate-over-all-sensors store item spike-check)
          :median-calculation (calculate-over-all-sensors store item median-calculation)
          :mislabelled-sensors (calculate-over-all-sensors store item mislabelled-sensors))
        (log/infof "Finished recalculation of: %s" calculation)))

    (producer-of fanout-q median-calculation-q mislabelled-sensors-q spike-check-q rollups-q synthetic-readings-q
                 resolution-q difference-series-q convert-to-co2-q convert-to-kwh-q sensor-status-q actual-annual-q
                 upload-measurements-q recalculate-q)

    (list fanout-q #{median-calculation-q mislabelled-sensors-q spike-check-q rollups-q synthetic-readings-q
                     resolution-q difference-series-q convert-to-co2-q convert-to-kwh-q sensor-status-q actual-annual-q
                     upload-measurements-q recalculate-q})))

(defrecord Pipeline []
  component/Lifecycle
  (start [this]
    (log/info "Pipeline starting")
    (let [store         (-> this :store)
          [head others] (build-pipeline store)]
      (-> this
          (assoc :head head)
          (assoc :others others))))
  (stop [this] this))

(defn new-pipeline []
  (->Pipeline))
