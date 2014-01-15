(ns kixi.hecuba.web.resources
  (:require
   [liberator.core :refer (defresource)]
   [kixi.hecuba.protocols :refer (upsert! item items)]
   [bidi.bidi :refer (->Redirect path-for)]
   [hiccup.core :refer (html)]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [camel-snake-kebab :as csk]))

(def base-media-types ["text/html" "application/json" "application/edn"])

(defresource projects-resource [querier commander project-resource]
  :allowed-methods #{:get :post}
  :available-media-types base-media-types
  :exists? (fn [{{{id :id} :route-params body :body routes :jig.bidi/routes} :request :as ctx}]
             {::projects
              (map (fn [m] (assoc m :href (path-for routes project-resource :id (:id m))))
                   (items querier))})
  :handle-ok (fn [{projects ::projects {mt :media-type} :representation :as ctx}]
               (case mt
                 "text/html"
                 (let [debug false
                       fields (remove #{:name :id :href :type} (keys (first projects)))]
                   (html [:body
                          [:h2 "Fields"]
                          [:ul (for [k fields] [:li (csk/->snake_case_string (name k))])]
                          [:h2 "Items"]
                          [:table
                           [:thead
                            [:tr
                             [:th "Name"]
                             (for [k fields] [:th (string/replace (csk/->Snake_case_string k) "_" " ")])
                             (when debug [:th "Debug"])]]
                           [:tbody
                            (for [p projects]
                              [:tr
                               [:td [:a {:href (:href p)} (:name p)]]
                               (for [k fields] [:td (k p)])
                               (when debug [:td (pr-str p)])])]]

                          ]))
                 "application/edn" (pr-str (vec projects))
                 projects))
  :post! (fn [{{body :body} :request}]
           (let [payload (io! (edn/read (java.io.PushbackReader. (io/reader body))))]
             (upsert! commander (assoc payload :type :project)))))

(defresource project-resource [querier]
  :allowed-methods #{:get}
  :available-media-types base-media-types
  :exists? (fn [{{{id :id} :route-params body :body routes :jig.bidi/routes} :request :as ctx}]
             (if-let [p (item querier id)] {::project p} false))
  :handle-ok (fn [{project ::project {mt :media-type} :representation :as ctx}]
               (case mt
                 "text/html" (html [:h1 (:name project)])
                 project)))

(defn create-routes [querier commander]
  (let [project (project-resource querier)
        projects (projects-resource querier commander project)]
    [""
     [["projects/" projects]
      ["projects" (->Redirect 307 projects)]
      [["project/" :id] project]]]))
