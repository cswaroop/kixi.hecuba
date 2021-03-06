(ns kixi.hecuba.time
  (:require [clj-time.core     :as t]
            [clj-time.coerce   :as tc]
            [clj-time.periodic :as tp]
            [clj-time.format   :as tf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; @  @  @       @@@@     @    @   @   @@@   @@@@  @@@@        @  @  @
;; @  @  @       @   @   @ @   @@  @  @   @  @     @   @       @  @  @
;; @  @  @       @   @  @   @  @ @ @  @      @@@   @@@@        @  @  @
;; @  @  @       @   @  @@@@@  @ @ @  @  @@  @     @ @         @  @  @
;;               @   @  @   @  @  @@  @   @  @     @  @
;; @  @  @       @@@@   @   @  @   @   @@@@  @@@@  @   @       @  @  @

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; No-one understands date/time properly. Write tests before changing.
;; ======

;; year/month/day/hour/minute constants vary wildly.

;; Review these...
;;
;; http://joda-time.sourceforge.net/apidocs/org/joda/time/DateTimeConstants.html
;; http://docs.oracle.com/javase/7/docs/api/constant-values.html#java.util
;; http://docs.oracle.com/javase/7/docs/api/javax/xml/datatype/DatatypeConstants.html



(defn hourly-timestamp [t]
  (tc/to-date (tf/unparse (tf/formatters :date-hour) (tc/from-date t))))
(defn daily-timestamp [t]
  (tc/to-date (tf/unparse (tf/formatters :date) (tc/from-date t))))

(defmulti get-year-partition-key type)
(defmethod get-year-partition-key org.joda.time.DateTime [t]
  (Long/parseLong (format "%4d" (t/year t))))
(defmethod get-year-partition-key java.util.Date [t]
  (let [timestamp (tc/from-date t)]
    (Long/parseLong (format "%4d" (t/year timestamp)))))


(defmulti get-month-partition-key type)
(defmethod get-month-partition-key java.util.Date [t]
  (let [timestamp (tc/from-date t)]
    (Long/parseLong (format "%4d%02d" (t/year timestamp) (t/month timestamp)))))
(defmethod get-month-partition-key org.joda.time.DateTime [t]
  (Long/parseLong (format "%4d%02d" (t/year t) (t/month t))))

(defmulti truncate-seconds type)
(defmethod truncate-seconds java.util.Date [t]
  (let [time-str (tf/unparse (tf/formatters :date-hour-minute) (tc/from-date t))]
    (tc/to-date  (tf/parse (tf/formatters :date-hour-minute) time-str))))
(defmethod truncate-seconds org.joda.time.DateTime [t]
  (let [time-str (tf/unparse (tf/formatters :date-hour-minute) t)]
    (tf/parse (tf/formatters :date-hour-minute) time-str)))

(defmulti truncate-minutes type)
(defmethod truncate-minutes java.util.Date [t]
  (let [time-str (tf/unparse (tf/formatter "yyyy-MM-dd'T'HH") (tc/from-date t))]
    (tc/to-date  (tf/parse (tf/formatter "yyyy-MM-dd'T'HH") time-str))))
(defmethod truncate-minutes org.joda.time.DateTime [t]
  (let [time-str (tf/unparse (tf/formatter "yyyy-MM-dd'T'HH") t)]
    (tf/parse (tf/formatter "yyyy-MM-dd'T'HH") time-str)))

(def formatter (tf/formatter (t/default-time-zone) "yyyy-MM-dd'T'HH:mm:ssZ" "yyyy-MM-dd HH:mm:ss"))
(defn to-db-format [date] (tf/parse formatter date))
(defn db-to-iso [date] (tf/unparse formatter (tc/from-date date)))

(defn now->timestamp []
  (db-to-iso (tc/to-date (t/now))))

(defn dates-overlap?
  "Takes a start/end range from sensor_metadata \"dirty dates\" and a period,
  and returns range to calculate if it overlaps. Otherwise it returns nil."
  [{:keys [start-date end-date]} period]
  (let [end   (t/now)
        start (t/minus end period)]
    (when (t/overlaps? (t/interval start end) (t/interval start-date end-date))
      {:start-date start :end-date end})))

(defn time-range
  "Return a lazy sequence of DateTime's from start to end, incremented
  by 'step' units of time."
  [start end step]
  (let [start-date (t/first-day-of-the-month (tc/to-date-time start))
        end-date   (t/last-day-of-the-month (tc/to-date-time end))
        in-range-inclusive? (complement (fn [t] (t/after? t end-date)))]
    (take-while in-range-inclusive? (tp/periodic-seq start-date step))))

(defn range->months [start-date end-date]
  (->> (time-range start-date end-date (t/months 1))
       (map #(get-month-partition-key (tc/to-date %)))))

(defn range->years [start-date end-date]
  (->> (time-range start-date end-date (t/years 1))
       (map #(get-year-partition-key (tc/to-date-time %)))))

(defn start-end-dates
  "Given a sensor, column and where clause, returns start and end dates for (re)calculations."
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

(defn calculate-range
  "Takes a start/end range from sensor_metadata \"dirty dates\" and a period,
  and returns range (number of months from end-date) to calculate."
  [{:keys [start-date end-date]} period]
  (let [start (t/minus end-date period)]
    {:start-date start :end-date end-date}))

(def default-date-formatters
  (concat [(tf/formatter "dd/MM/yyyy")
           (tf/formatter "dd/MM/yyyy HH:mm")
           (tf/formatter "dd/MM/yyyy HH:mm:ss")
           (tf/formatter "dd/MM/yyyy HH:mm:ss.SSS")
           (tf/formatter "yyyy/MM/dd HH:mm:ss.SSS")
           (tf/formatter "yyyy/MM/dd HH:mm:ss")
           (tf/formatter "yyyy/MM/dd HH:mm")
           (tf/formatter "yyyy/MM/dd")
           (tf/formatter "dd-MM-yyyy")
           (tf/formatter "dd-MM-yyyy HH:mm")
           (tf/formatter "dd-MM-yyyy HH:mm:ss")
           (tf/formatter "dd-MM-yyyy HH:mm:ss.SSS")
           (tf/formatter "yyyy-MM-dd HH:mm:ss.SSS")
           (tf/formatter "yyyy-MM-dd HH:mm:ss")
           (tf/formatter "yyyy-MM-dd HH:mm")
           (tf/formatter "yyyy-MM-dd")]
          (vals tf/formatters)))

(defn auto-parse
  ([s fmts]
     (first
      (for [f fmts
            :let [d (try (tf/parse f s) (catch Exception _ nil))]
            :when d] d)))
  ([s]
     (auto-parse s default-date-formatters)))

(defn hecuba-date-time-string [date-time-string]
  (tf/unparse (tf/formatters :date-time) (auto-parse date-time-string)))

(defn db-timestamp
  "Returns java.util.Date from String timestamp."
  [t] (.parse (java.text.SimpleDateFormat.  "yyyy-MM-dd'T'HH:mm:ss") t))
