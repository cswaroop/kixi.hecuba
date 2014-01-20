(ns kixi.hecuba.main-om
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]
   [cljs.core.async :refer [<! chan put! sliding-buffer]]
   [ajax.core :refer (GET POST)]
   [kixi.hecuba.navigation :as nav]))

(enable-console-print!)

(defn table-row [data owner cols]
  (om/component
      (apply dom/tr nil
             (for [col cols]
               (let [d (get data col)
                     sd (if (keyword? d) (name d) (str d))]
                 (dom/td nil (if (#{:hecuba/href :hecuba/parent-href} col) (dom/a #js {:href sd} sd) sd)))))))

(defn table [data owner]
  (om/component
      (dom/div #js {:className "table-responsive"}
           (dom/table #js {:className "table table-bordered table-hover table-striped"}
                (dom/thead nil
                     (apply dom/tr nil
                            (for [col (:cols data)]
                              (dom/th nil (name col)))))
                (dom/tbody nil
                     (om/build-all table-row (:rows data)
                         {:key :hecuba/id :opts (:cols data)}))))))

(defn blank-tab [data owner]
  (om/component
      (dom/p nil "This page is unintentionally left blank")))

(defn about-tab [data owner tab]
  (om/component
      (dom/p nil "I'm the About tab")))

(defn programmes-tab [data owner tab]
  (om/component
      (dom/div nil
           (dom/h1 nil (:title tab))
           (when-let [tabdata (get-in data [:programmes :table])]
             (om/build table tabdata))
           (dom/h1 nil "Projects")
           (when-let [tabdata (get-in data [:projects :table])]
             (om/build table tabdata))
           (dom/h1 nil "Properties")
           (when-let [tabdata (get-in data [:properties :table])]
             (om/build table tabdata)))))

(defn tab-container [tabs]
  (fn [data owner]
    (om/component
        (dom/div nil
             (let [selected (-> data :tab-container :selected)]
               (if-let [tab (get tabs selected)]
                 (om/build tab data {:opts (->> data :tab-container :tabs (filter #(= (:name %) selected)) first)})
                 (om/build blank-tab data)))))))


(def app-model
  (atom {:messages []
         :nav {:active "dashboard"
               :menuitems [{:name :dashboard :label "Dashboard" :href "/index.html" :icon "dashboard" :active? true}
                           {:name :overview :label "Overview" :href "/charts.html" :icon "bar-chart-o"}
                           {:name :users :label "Users"}
                           {:name :programmes :label "Programmes"}
                           {:name :projects :label "Project"}
                           {:name :properties :label "Properties"}
                           {:name :about :label "About"}
                           {:name :documentation :label "Documentation"}
                           {:name :api_users :label "API users"}
                           ]}
         :tab-container {:selected :programmes
                          :tabs [{:name :about :title "About"}
                                  {:name :documentation :title "Documentation"}
                                  {:name :users :title "Users"}
                                  {:name :programmes :title "Programmes"}
                                  ]}
         :programmes {}
         :projects {}
         :properties {}}))

(defn ^:export handle-left-nav [menu-item]
  ;; Currently we implement a one-to-one correspondence between the
  ;; left-hand-menu and the tab container, but in due course 'Project'
  ;; and 'Properties' will cause a scroll to a location under the
  ;; 'Programmes' tab
  (swap! app-model assoc-in [:tab-container :selected] menu-item))

(defn ^:export change []
  (.log js/console "Hello Malcolm!")
  (change-tab "documentation")
  )

;; Add navigation.
(om/root app-model (nav/nav handle-left-nav) (.getElementById js/document "hecuba-nav"))

;; Attach projects to a table component at hecuba-projects
#_(om/root app-model (create-table [:projects]
                                 ["Name" "Project code" "Leaders"]
                                 (make-table-row [:hecuba/name :project-code :leaders])) (.getElementById js/document "hecuba-projects"))

#_(om/root app-model (create-table [:properties]
                                 ["Name" "Address" "Rooms" "Construction date"]
                                 (make-table-row [:hecuba/name :address :rooms :date-of-construction])) (.getElementById js/document "hecuba-properties"))

(om/root app-model (tab-container {:about about-tab
                                    :programmes programmes-tab}) (.getElementById js/document "hecuba-tabs"))

(GET "/messages/" {:handler (fn [x]
                              (println "Messages: " x)
                              (swap! app-model assoc-in [:messages] x))
                   :headers {"Accept" "application/edn"}})

(defn handle-get [k]
  (fn [data]
    (let [cols (distinct (mapcat keys data))]
      (swap! app-model assoc-in [k]
             {:raw data
              :table {:cols cols
                      :rows data}
              }))))

;; Get the real project data
(GET "/programmes/" {:handler (handle-get :programmes) :headers {"Accept" "application/edn"}})
(GET "/projects/" {:handler (handle-get :projects) :headers {"Accept" "application/edn"}})
(GET "/propertiesX/" {:handler (handle-get :properties) :headers {"Accept" "application/edn"}})
