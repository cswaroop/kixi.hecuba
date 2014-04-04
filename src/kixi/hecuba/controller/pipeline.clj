(ns kixi.hecuba.controller.pipeline
  "Pipeline for scheduled jobs."
  (:require [kixipipe.pipeline             :refer [defnconsumer produce-item produce-items submit-item] :as p]
            [pipejine.core                 :as pipejine :refer [new-queue producer-of]]
            [clojure.tools.logging         :as log]
            [kixi.hecuba.data.batch-checks :as checks]
            [kixi.hecuba.data.misc         :as misc]
            [kixi.hecuba.data.calculate    :as calculate]
            [com.stuartsierra.component    :as component]))

(defn build-pipeline [commander querier]
  (let [fanout-q              (new-queue {:name "fanout-q" :queue-size 50})
        data-quality-q        (new-queue {:name "data-quality-q" :queue-size 50})
        median-calculation-q  (new-queue {:name "median-calculation-q" :queue-size 50})
        mislabelled-sensors-q (new-queue {:name "mislabelled-sensors-q" :queue-size 50})
        difference-series-q   (new-queue {:name "difference-series-q" :queue-size 50})
        rollups-q             (new-queue {:name "rollups-q" :queue-size 50})
        spike-check-q         (new-queue {:name "spike-check-q" :queue-size 50})]

    (defnconsumer fanout-q [{:keys [dest type] :as item}]
      (let [item (dissoc item :dest)]
        (condp = dest
          :data-quality (condp = type
                          :median-calculation  (produce-item item median-calculation-q)
                          :mislabelled-sensors (produce-item item mislabelled-sensors-q)
                          :spike-check         (produce-item item spike-check-q))
          :calculated-datasets (condp = type
                                 :difference-series (produce-item item difference-series-q)
                                 :rollups           (produce-item item rollups-q)))))

    (defnconsumer median-calculation-q [item]
      (let [sensors (misc/all-sensors querier)]
        (doseq [s sensors]
          (let [device-id (:device-id s)
                type      (:type s)
                period    (:period s)
                where     {:device-id device-id :type type}
                table     (case period
                            "CUMULATIVE" :difference-series
                            "INSTANT"    :measurement
                            "PULSE"      :measurement)
                range     (misc/start-end-dates querier table :median-calc-check s where)
                new-item  (assoc item :sensor s :range range)]
            (when (and range (not= period "PULSE"))
              (checks/median-calculation commander querier table new-item)
              (misc/reset-date-range querier commander s :median-calc-check (:start-date range) (:end-date range)))))))

    (defnconsumer mislabelled-sensors-q [item]
      (let [sensors (misc/all-sensors querier)]
        (doseq [s sensors]
          (let [device-id (:device-id s)
                type      (:type s)
                period    (:period s)
                where     {:device-id device-id :type type}
                range     (misc/start-end-dates querier :measurement :mislabelled-sensors-check s where)
                new-item  (assoc item :sensor s :range range)]
            (when range
              (checks/mislabelled-sensors commander querier new-item)
              (misc/reset-date-range querier commander s :mislabelled-sensors-check (:start-date range) (:end-date range)))))))

    (defnconsumer difference-series-q [item]
      (let [sensors (misc/all-sensors querier)]
        (doseq [s sensors]
          (let [device-id (:device-id s)
                type      (:type s)
                period    (:period s)
                where     {:device-id device-id :type type}
                range     (misc/start-end-dates querier :measurement :difference-series s where)
                new-item  (assoc item :sensor s :range range)]
            (when range
              (calculate/difference-series commander querier new-item)
              (misc/reset-date-range querier commander s :difference-series (:start-date range) (:end-date range)))))))

    (defnconsumer rollups-q [item]
      (let [sensors (misc/all-sensors querier)]
        (doseq [s sensors]
          (let [device-id  (:device-id s)
                type       (:type s)
                period     (:period s)
                table      (case period
                             "CUMULATIVE" :difference-series
                             "INSTANT"    :measurement
                             "PULSE"      :measurement)
                where      {:device-id device-id :type type}
                range      (misc/start-end-dates querier table :rollups s where)
                new-item   (assoc item :sensor s :range range)]
            (when range
              (calculate/hourly-rollups commander querier new-item)
              (calculate/daily-rollups commander querier new-item)
              (misc/reset-date-range querier commander s :rollups (:start-date range) (:end-date range)))))))     

    (defnconsumer spike-check-q [item]
      (let [sensors (misc/all-sensors querier)]
        (doseq [s sensors]
          (let [device-id (:device-id s)
                type      (:type s)
                period    (:period s)
                where     {:device-id device-id :type type}
                range     (misc/start-end-dates querier :measurement :spike-check s where)
                new-item  (assoc item :sensor s :range range)]
            (when (and range (not= period "PULSE"))
              (checks/median-spike-check commander querier new-item)
              (misc/reset-date-range querier commander s :spike-check (:start-date range) (:end-date range)))))))

    (producer-of fanout-q median-calculation-q mislabelled-sensors-q spike-check-q difference-series-q rollups-q)

    (list fanout-q #{median-calculation-q mislabelled-sensors-q spike-check-q difference-series-q rollups-q})))

(defrecord Pipeline []
  component/Lifecycle
  (start [this]
    (let [commander (-> this :store :commander)
          querier   (-> this :store :querier)
          [head others] (build-pipeline commander querier)]
      (-> this
          (assoc :head head)
          (assoc :others others))))
  (stop [this] this))

(defn new-pipeline []
  (->Pipeline))