(ns kixi.hecuba.main-om
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]
   [cljs.core.async :refer [<! chan put! sliding-buffer]]
   [ajax.core :refer (GET POST)]
   [kixi.hecuba.navigation :as nav]))

(enable-console-print!)

(defn table-row [fields]
  (fn [data owner]
    (om/component
        (apply dom/tr #js {:onClick (fn [e] (.log js/console "ooh!"))}
               (for [field fields]
                 (dom/td nil (str (get data field))))
               ))))

(defn create-table [model-path fields row]
  (fn [data owner]
    (reify
      om/IRender
      (render [_]
        (dom/div #js {:className "table-responsive"}
             (dom/table #js {:className "table table-bordered table-hover table-striped"}
                  (dom/thead nil
                       (apply dom/tr nil
                            (for [field fields]
                              (dom/th nil field))))
                  (dom/tbody nil
                       (om/build-all row
                           (get-in data model-path)
                           {:key :hecuba/name}))))))))

(def app-model
  (atom {:messages []
         :nav {:active "dashboard"
               :menuitems [{:name "dashboard" :label "Dashboard" :href "/index.html" :icon "dashboard" :active? true}
                           {:name "overview" :label "Overview" :href "/charts.html" :icon "bar-chart-o"}
                           {:name "users" :label "Users"}
                           {:name "programmes" :label "Programmes"}
                           {:name "projects" :label "Project"}
                           {:name "properties" :label "Properties"}
                           {:name "about" :label "About"}
                           {:name "documentation" :label "Documentation"}
                           {:name "api_users" :label "API users"}
                           ]}
         :projects []
         :properties []}))

;; Add navigation.
(om/root app-model nav/nav (.getElementById js/document "hecuba-nav"))

;; Attach projects to a table component at hecuba-projects
(om/root app-model (create-table [:projects]
                                 ["Name" "Project code" "Leaders"]
                                 (table-row [:hecuba/name :project-code :leaders])) (.getElementById js/document "hecuba-projects"))

(om/root app-model (create-table [:properties]
                                 ["Name" "Address" "Rooms" "Construction date"]
                                 (table-row [:hecuba/name :address :rooms :date-of-construction])) (.getElementById js/document "hecuba-properties"))

(GET "/messages/" {:handler (fn [x]
                              (println "Messages: " x)
                              (swap! app-model assoc-in [:messages] x))
                   :headers {"Accept" "application/edn"}})

;; Get the real project data
(GET "/projects/" {:handler #(swap! app-model assoc-in [:projects] %)
                   :headers {"Accept" "application/edn"}})

(GET "/propertiesX/" {:handler #(swap! app-model assoc-in [:properties] %)
                      :headers {"Accept" "application/edn"}})

;; Observation: Projects and Messages appear to be competing - only one of them seems to 'win'
