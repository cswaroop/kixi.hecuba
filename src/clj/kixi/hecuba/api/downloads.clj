(ns kixi.hecuba.api.downloads
  (:require [clojure.tools.logging :as log]
            [liberator.core :refer (defresource)]
            [kixi.hecuba.api :refer (authorized?) :as api]
            [kixipipe.storage.s3 :as s3]
            [kixi.hecuba.security :refer (has-admin? has-programme-manager? has-project-manager? has-user?) :as sec]
            [kixi.hecuba.data.projects :as projects]
            [kixi.hecuba.data.entities :as entities]
            [kixi.hecuba.data.measurements.core :as mc]
            [clojure.core.match :refer (match)]
            [clojure.string :as str]
            [aws.sdk.s3 :as aws]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]
            [kixipipe.storage.s3 :as s3]
            [liberator.representation :refer (ring-response)]
            [ring.util.response :refer (redirect)]))

;; List of files is retrieved for a username (read from the current session) so only users who can upload files can also GET those files. Other users will get an empty list.

(defn allowed?* [programme-id project-id allowed-programmes allowed-projects role request-method]
  (log/infof "allowed?* programme-id: %s project-id: %s allowed-programmes: %s allowed-projects: %s roles: %s request-method: %s"
             programme-id project-id allowed-programmes allowed-projects role request-method)
  (match  [(has-admin? role)
           (has-programme-manager? programme-id allowed-programmes)
           (has-project-manager? project-id allowed-projects)
           (has-user? programme-id allowed-programmes project-id allowed-projects)
           request-method]

          [true _ _ _ _]    true
          [_ true _ _ _]    true
          [_ _ true _ _]    true
          [_ _ _ true :get] true
          :else false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Downloads for entity

(defn downloads-for-entity-allowed? [store]
  (fn [ctx]
    (let [{:keys [request-method session params]} (:request ctx)
          {:keys [projects programmes role]}     (sec/current-authentication session)
          {:keys [programme_id project_id]} params]
      (if (and project_id programme_id)
        (allowed?* programme_id project_id programmes projects role request-method)
        true))))

(defn status-from-object [store s3-key]
  (with-open [in (s3/get-object-by-metadata (:s3 store) {:key s3-key})]
    (get (json/parse-string (slurp in) keyword) :status)))

(defn merge-downloads-status-with-metadata [store s3-object entity_id]
  (let [session                      (:s3 store)
        metadata                     (s3/get-user-metadata-from-s3-object session s3-object)
        {:keys [downloads-timestamp
                downloads-filename]} metadata]
    (hash-map :filename downloads-filename
              :timestamp (tc/to-string downloads-timestamp)
              :link (str "/4/download/" entity_id "/data")
              :status (status-from-object store (:key s3-object)))))

;; TOFIX We currently store one file per entity only.
(defn downloads-for-entity-status-handle-ok [store ctx]
  (let [{:keys [params session]} (:request ctx)
        {:keys [entity_id]}      params
        files                    (take 2 (s3/list-objects-seq (:s3 store) {:prefix (str "downloads/" entity_id)}))
        statuses                 (map #(merge-downloads-status-with-metadata store % entity_id)
                                      (filter #(re-find #"status" (:key %)) files))]
    (api/render-items ctx statuses)))

(defn downloads-for-entity-data-resource-handle-ok [store ctx]
  (let [{:keys [params session]}                  (:request ctx)
        {:keys [entity_id]}                       params
        {:keys [auth file-bucket] :as s3-session} (:s3 store)
        [data & _]                               (->> {:max-keys 100 :prefix (str "downloads/" entity_id)}
                                                          (s3/list-objects-seq s3-session)
                                                          (filter #(re-find #"data" (:key %))))
        uri                                       (s3/generate-plain-uri s3-session (:key data))]
    (log/info "Redirecting to " uri)
    (ring-response (redirect uri))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RESOURCES

(defresource downloads-data-resource [store]
  :allowed-methods #{:get}
  :available-media-types #{"text/csv"}
  :authorized? (authorized? store)
  :allowed? (downloads-for-entity-allowed? store)
  :handle-ok (partial downloads-for-entity-data-resource-handle-ok store))

(defresource downloads-for-entity [store]
  :allowed-methods #{:get}
  :allowed? (downloads-for-entity-allowed? store)
  :available-media-types #{"application/json" "application/edn"}
  :authorized? (authorized? store)
  :handle-ok (partial downloads-for-entity-status-handle-ok store))
