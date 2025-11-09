(ns tech.jgood.gleanmo.timer.routes
  (:require
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(defn infer-primary-rel
  "Determine the primary relationship field and parent entity for a timer entity.

  Respects explicit `:timer/primary-rel` metadata, falling back to `:<entity>/<entity>-id`
  naming convention. Throws if neither approach yields a valid relationship."
  [{:keys [entity-str entity-schema]}]
  (let [explicit-field (schema-utils/primary-rel-field entity-schema)
        base-name      (schema-utils/entity-base-name entity-str)
        candidate-key  (or explicit-field
                            (keyword entity-str (str base-name "-id")))
        field-entry    (schema-utils/schema-field entity-schema candidate-key)
        parent-entity  (some-> field-entry schema-utils/relationship-target-entity)]
    (when-not field-entry
      (throw (ex-info "Timer entity missing primary relationship field"
                      {:entity-str entity-str
                       :expected-field candidate-key
                       :explicit-field explicit-field})))
    (when-not parent-entity
      (throw (ex-info "Unable to determine parent entity for timer relationship"
                      {:entity-str entity-str
                       :relationship-field candidate-key
                       :field-entry field-entry})))
    {:relationship-field candidate-key
     :parent-entity-key  parent-entity
     :parent-entity-str  (name parent-entity)}))


(defn timer-config
  "Build derived configuration for a timer-enabled entity."
  [{:keys [entity-key entity-str entity-schema schema-map]}]
  (let [schema-map     (or schema-map schema)
        entity-schema  (or entity-schema (schema-utils/entity-schema schema-map entity-key))
        interval-fields (schema-utils/ensure-interval-fields {:entity-schema entity-schema
                                                              :entity-str entity-str})
        rel-info        (infer-primary-rel {:entity-schema entity-schema
                                            :entity-str    entity-str})
        parent-entity   (:parent-entity-key rel-info)
        parent-schema   (schema-utils/entity-schema schema-map parent-entity)
        entity-query    {:entity-type-str entity-str
                         :schema          entity-schema
                         :filter-references true}
        parent-query    {:entity-type-str (:parent-entity-str rel-info)
                         :schema          parent-schema
                         :filter-references true}]
    (merge {:entity-key        entity-key
            :entity-str        entity-str
            :entity-schema     entity-schema
            :schema-map        schema-map
            :relationship-key  (:relationship-field rel-info)
            :parent-entity-key parent-entity
            :parent-entity-str (:parent-entity-str rel-info)
            :beginning-key     (schema-utils/entity-field-key entity-str "beginning")
            :end-key           (schema-utils/entity-field-key entity-str "end")
            :notes-key         (schema-utils/entity-field-key entity-str "notes")
            :entity-query      entity-query
            :parent-query      parent-query}
           interval-fields
           rel-info)))


(defn fetch-active-timers
  [ctx {:keys [entity-query beginning-key end-key]}]
  (->> (queries/all-for-user-query entity-query ctx)
       (filter (fn [timer]
                 (and (get timer beginning-key)
                      (nil? (get timer end-key)))))))

(defn start-timer-card
  "Render a start button for a parent entity."
  [parent {:keys [entity-str parent-entity-key relationship-key beginning-key]}]
  (let [label-key          (schema-utils/entity-attr-key parent-entity-key "label")
        notes-key          (schema-utils/entity-attr-key parent-entity-key "notes")
        rel-param-name     (schema-utils/ns-keyword->input-name relationship-key)
        beginning-param    (schema-utils/ns-keyword->input-name beginning-key)
        encoded-beginning  (java.net.URLEncoder/encode (str (t/now)) "UTF-8")
        encoded-redirect   (java.net.URLEncoder/encode (str "/app/timer/" entity-str)
                                                       "UTF-8")]
    [:div.bg-dark-surface.rounded-lg.p-4.border.border-dark.transition-all.duration-300.hover:shadow-lg.hover:border-neon-yellow
[:div.flex.items-center.justify-between
       [:div.flex-1.min-w-0
        [:div
         [:h3.text-lg.font-semibold.text-white (or (get parent label-key) "Unnamed")]
         (when-let [notes (get parent notes-key)]
           [:p.text-sm.text-gray-400.truncate notes])]]
       [:a.bg-neon-yellow.bg-opacity-20.text-neon-yellow.px-3.py-2.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all.no-underline
       {:href (str "/app/crud/form/" entity-str
                   "/new?"
                   rel-param-name "=" (:xt/id parent)
                   "&" beginning-param "=" encoded-beginning
                   "&redirect=" encoded-redirect)}
       "Start Timer"]]]))

(defn active-timer-card
  "Create a card for an active timer with stop functionality"
  [timer parents ctx {:keys [entity-str parent-entity-key relationship-key beginning-key notes-key]}]
  (let [timer-parent-id (get timer relationship-key)
        parent          (first (filter #(= (:xt/id %) timer-parent-id) parents))
        label-key       (schema-utils/entity-attr-key parent-entity-key "label")
        parent-name     (or (some-> parent (get label-key)) "Unknown")
        start-time      (get timer beginning-key)
        now             (t/now)
        elapsed-seconds (t/seconds (t/between start-time now))
        elapsed-hours   (quot elapsed-seconds 3600)
        elapsed-minutes (quot (mod elapsed-seconds 3600) 60)
        elapsed-str     (str elapsed-hours "h " elapsed-minutes "m")
        timer-notes     (get timer notes-key)
        redirect-target (str "/app/timer/" entity-str)
        edit-url        (str "/app/crud/form/" entity-str "/edit/" (:xt/id timer)
                             "?redirect="
                             (java.net.URLEncoder/encode redirect-target "UTF-8"))]
    [:div.bg-dark-surface.rounded-lg.p-4.border.border-neon-cyan.transition-all.duration-300.hover:shadow-lg
     [:div.flex.items-center.justify-between.mb-4
      [:span.text-sm.text-gray-400.uppercase.tracking-wide "Active Timer"]
      [:a.bg-red-500.bg-opacity-20.text-red-400.px-3.py-2.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all.no-underline
       {:href (str "/app/timer/" entity-str "/" (:xt/id timer) "/stop")}
       "End Session"]]
     [:a.block.no-underline {:href edit-url}
      [:div.flex.flex-col.space-y-1.text-white.transition-all.duration-300.hover:text-neon-cyan
       [:h3.text-lg.font-semibold parent-name]
       [:p.text-sm.text-neon-cyan (str "Running for " elapsed-str)]
       (when timer-notes
         [:p.text-sm.text-gray-400.truncate timer-notes])]]]))

(defn timer-page
  "Timer page showing parent entities and active timers"
  [ctx {:keys [entity-str parent-entity-str parent-query] :as config}]
  (let [parents      (queries/all-for-user-query parent-query ctx)
        ;; Find active timers (entries with beginning but no end)
        active-timers (fetch-active-timers ctx config)]
    (ui/page
     ctx
      (side-bar
       ctx
       [:div.container.mx-auto.p-6.space-y-8
        [:h1.text-3xl.font-bold.text-white "⏱️ Time Tracker"]

;; Active Timers Section
         [:div.mb-8
          [:h2.text-xl.font-semibold.mb-4.text-neon-cyan "Active Timers"]
          [:div
           {:id "active-timers-section"
            :hx-get (str "/app/timer/" entity-str "/active")
            :hx-trigger "every 30s"
            :hx-swap "outerHTML"}
           (when (seq active-timers)
             [:div.space-y-4
              (for [timer active-timers]
                ^{:key (:xt/id timer)}
                (active-timer-card timer parents ctx config))])]]

[:div.mb-8
          [:h2.text-xl.font-semibold.mb-4.text-neon-yellow "Start Timer"]
          (if (seq parents)
            [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
             (for [parent parents]
               ^{:key (:xt/id parent)}
               (start-timer-card parent config))]
            [:p.text-gray-400
             (str "No " parent-entity-str "s found. Create some first!")])]]))))

(defn active-timers-section
  "Return HTML for the active timers section"
  [ctx {:keys [entity-str parent-query] :as config}]
  (let [parents (queries/all-for-user-query parent-query ctx)
        active-timers (fetch-active-timers ctx config)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (ui/fragment
            [:div
             {:id "active-timers-section"
              :hx-get (str "/app/timer/" entity-str "/active")
              :hx-trigger "every 30s"
              :hx-swap "outerHTML"}
             (when (seq active-timers)
[:div.space-y-4
                 (for [timer active-timers]
                   ^{:key (:xt/id timer)}
                   (active-timer-card timer parents ctx config))])])}))

(defn stop-timer
  "Stop an active timer by setting the end time"
  [timer-id ctx {:keys [entity-str end-key entity-query]}]
  (let [user-id (get-in ctx [:session :uid])
        timer   (first (filter #(= (:xt/id %) timer-id)
                               (queries/all-for-user-query entity-query ctx)))]
    (if (and timer (nil? (get timer end-key)))
      (do
        ;; Update the timer with end time
        (tech.jgood.gleanmo.db.mutations/update-entity!
         ctx
         {:entity-key (keyword entity-str),
          :entity-id  timer-id,
          :data       {end-key (t/now)}})
        ;; Redirect to edit form so the user can review notes/details
        (let [edit-path        (str "/app/crud/form/" entity-str "/edit/" timer-id)
              return-target    (str "/app/timer/" entity-str)
              encoded-redirect (java.net.URLEncoder/encode return-target "UTF-8")]
          {:status  303
           :headers {"location" (str edit-path "?redirect=" encoded-redirect)}}))
      ;; Timer not found or already stopped
      {:status  303
       :headers {"location" (str "/app/timer/" entity-str)}})))

(defn gen-routes
  "Generate timer routes for an interval entity"
  [{:keys [entity-key entity-str] :as opts}]
  (let [config (timer-config (merge {:entity-key entity-key
                                     :entity-str entity-str}
                                    opts))]
    ["/timer" {}
     [(str "/" entity-str)
      {:get (fn [ctx]
              (timer-page ctx config))}]
     [(str "/" entity-str "/:id/stop")
      {:get (fn [ctx]
              (let [timer-id (java.util.UUID/fromString
                              (get-in ctx [:path-params :id]))]
                (stop-timer timer-id ctx config)))}]
     [(str "/" entity-str "/active")
      {:get (fn [ctx]
              (active-timers-section ctx config))}]]))
