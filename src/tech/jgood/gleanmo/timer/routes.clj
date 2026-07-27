(ns tech.jgood.gleanmo.timer.routes
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar get-user-time-zone]]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.db.mutations :as mutations]
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
  [ctx {:keys [entity-key beginning-key end-key]}]
  (queries/active-timers-for-user
   (:biff/db ctx)
   (-> ctx :session :uid)
   entity-key
   beginning-key
   end-key
   :user-settings (queries/resolve-user-settings ctx)))

(defn fetch-completed-logs
  "Fetch recent completed logs (both beginning and end set), ordered by beginning desc."
  [ctx {:keys [entity-key beginning-key end-key]} limit]
  (queries/recent-completed-timer-logs
   (:biff/db ctx)
   (-> ctx :session :uid)
   entity-key
   beginning-key
   end-key
   limit
   :user-settings (queries/resolve-user-settings ctx)))

(defn log-duration-seconds
  "Calculate duration in seconds for a completed log entry."
  [log beginning-key end-key]
  (let [start (get log beginning-key)
        end   (get log end-key)]
    (when (and start end)
      (t/seconds (t/between start end)))))

(defn- interval-seconds
  "Calculate duration in seconds for a [start end] interval."
  [[start end]]
  (t/seconds (t/between start end)))

(defn format-duration
  "Format a duration in seconds as Xh Ym."
  [total-seconds]
  (let [hours   (quot total-seconds 3600)
        minutes (quot (mod total-seconds 3600) 60)]
    (cond
      (and (zero? hours) (zero? minutes)) "< 1m"
      (zero? hours) (str minutes "m")
      :else (str hours "h " minutes "m"))))

(defn- today-window
  "Return today's [start, end) window as instants in the user's timezone."
  [ctx]
  (let [zone-id (java.time.ZoneId/of (or (get-user-time-zone ctx) "UTC"))
        today   (.toLocalDate (java.time.ZonedDateTime/now zone-id))
        start   (.toInstant (.atStartOfDay today zone-id))
        end     (.toInstant (.atStartOfDay (.plusDays today 1) zone-id))]
    {:start start
     :end   end}))

(defn- clamp-interval-to-window
  "Clamp [start end] to [window-start window-end). Returns nil if no overlap."
  [start end window-start window-end]
  (let [start* (if (.isAfter start window-start) start window-start)
        end*   (if (.isBefore end window-end) end window-end)]
    (when (.isBefore start* end*)
      [start* end*])))

(defn- merge-overlapping-intervals
  "Merge overlapping or adjacent intervals."
  [intervals]
  (reduce
   (fn [acc [start end]]
     (if-let [[acc-start acc-end] (peek acc)]
       (if (not (.isAfter start acc-end))
         (conj (pop acc)
               [acc-start (if (.isAfter end acc-end) end acc-end)])
         (conj acc [start end]))
       [[start end]]))
   []
   (sort-by first intervals)))

(defn- unique-interval-seconds
  "Total unique seconds covered by a set of intervals."
  [intervals]
  (->> intervals
       merge-overlapping-intervals
       (map interval-seconds)
       (reduce + 0)))

(defn- today-logs
  "Fetch completed logs that overlap today. Each log is clamped to today's window."
  [ctx {:keys [entity-query beginning-key end-key]}]
  (let [{:keys [start end]} (today-window ctx)]
    (->> (queries/all-for-user-query entity-query ctx)
         (keep (fn [log]
                 (let [log-start (get log beginning-key)
                       log-end   (get log end-key)
                       interval  (when (and log-start log-end)
                                   (clamp-interval-to-window log-start
                                                             log-end
                                                             start
                                                             end))]
                   (when interval
                     (assoc log :timer/day-interval interval))))))))

(defn- metric-card
  [label value value-classes]
  [:div.rounded-lg.border.border-dark.p-3.bg-dark
   [:p.text-xs.text-gray-400.uppercase.tracking-wide label]
   [:p {:class (str "text-xl font-semibold mt-1 " value-classes)} value]])

(defn- today-stats-section
  "Render overlap-aware day stats plus per-parent raw totals."
  [ctx {:keys [relationship-key parent-entity-key parent-entity-str] :as config} parent-entities]
  (let [logs      (today-logs ctx config)
        label-key (schema-utils/entity-attr-key parent-entity-key "label")
        labels-by-id (into {}
                           (map (fn [parent]
                                  [(:xt/id parent) (get parent label-key)]))
                           parent-entities)
        by-parent (group-by #(get % relationship-key) logs)
        parent-stats (->> by-parent
                          (map (fn [[parent-id parent-logs]]
                                 (let [secs   (->> parent-logs
                                                   (map :timer/day-interval)
                                                   (map interval-seconds)
                                                   (filter some?)
                                                   (reduce + 0))]
                                   {:label (or (get labels-by-id parent-id) "Unknown")
                                    :seconds secs})))
                          (sort-by :seconds >)
                          (filter #(pos? (:seconds %))))
        raw-secs   (->> logs
                        (map :timer/day-interval)
                        (map interval-seconds)
                        (filter some?)
                        (reduce + 0))
        unique-secs (->> logs
                         (map :timer/day-interval)
                         unique-interval-seconds)
        overlap-secs (max 0 (- raw-secs unique-secs))
        parent-header (str "By " (str/capitalize parent-entity-str) " (raw)")]
    [:div.bg-dark-surface.rounded-lg.p-4.border.border-dark.space-y-3
     [:div.grid.grid-cols-1.md:grid-cols-3.gap-3
      (metric-card "Active (unique)" (format-duration unique-secs) "text-neon-cyan")
      (metric-card "Logged (raw)" (format-duration raw-secs) "text-white")
      (metric-card "Overlap removed" (format-duration overlap-secs) "text-neon-pink")]
     (when (seq parent-stats)
       [:div.space-y-2.pt-1
        [:p.text-xs.text-gray-400.uppercase.tracking-wide parent-header]
        (for [{:keys [label seconds]} parent-stats]
          ^{:key label}
          [:div.flex.items-center.justify-between
           [:span.text-sm.text-gray-300 label]
           [:span.text-sm.text-neon-cyan (format-duration seconds)]])])
     (when-not (seq logs)
       [:p.text-sm.text-gray-400 "No completed logs for today."])]))

(defn- recent-logs-section
  "Render a list of recent completed logs as edit links."
  [ctx {:keys [entity-str beginning-key end-key relationship-key parent-entity-key] :as config} parent-entities]
  (let [logs      (fetch-completed-logs ctx config 5)
        label-key (schema-utils/entity-attr-key parent-entity-key "label")
        tz        (t/zone (or (get-user-time-zone ctx) "UTC"))
        formatter (java.time.format.DateTimeFormatter/ofPattern "MMM d, h:mm a")
        redirect  (java.net.URLEncoder/encode (str "/app/timer/" entity-str) "UTF-8")]
    (when (seq logs)
      [:div.space-y-2
       (for [log logs]
         (let [parent-id   (get log relationship-key)
               parent      (first (filter #(= (:xt/id %) parent-id) parent-entities))
               parent-name (or (some-> parent (get label-key)) "Unknown")
               duration    (log-duration-seconds log beginning-key end-key)
               start-local (t/in (get log beginning-key) tz)
               edit-url    (str "/app/crud/form/" entity-str "/edit/" (:xt/id log)
                                "?redirect=" redirect)]
           ^{:key (:xt/id log)}
           [:a.block.no-underline {:href edit-url}
            [:div.bg-dark-surface.rounded.p-3.border.border-dark.flex.items-center.justify-between.transition-all.duration-300.hover:border-neon-cyan
             [:div
              [:span.text-sm.text-white parent-name]
              [:span.text-xs.text-gray-500.ml-2
               (str (t/format formatter start-local))]]
             [:span.text-sm.text-neon-cyan (when duration (format-duration duration))]]]))])))

(defn start-timer-form
  "Render a one-tap start button posting to the direct-start endpoint."
  [parent {:keys [entity-str]} & {:keys [redirect-target label]}]
  (biff/form
   {:action (str "/app/timers/start/" entity-str)
    :class  "shrink-0"}
   [:input {:type "hidden" :name "parent-id" :value (str (:xt/id parent))}]
   [:input {:type  "hidden"
            :name  "redirect"
            :value (or redirect-target (str "/app/timer/" entity-str))}]
   [:button.bg-neon-yellow.bg-opacity-20.text-neon-yellow.px-3.py-2.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all
    {:type "submit"}
    (or label "Start Timer")]))

(defn start-timer-card
  "Render a start button for a parent entity."
  [parent {:keys [parent-entity-key] :as config}]
  (let [label-key (schema-utils/entity-attr-key parent-entity-key "label")
        notes-key (schema-utils/entity-attr-key parent-entity-key "notes")]
    [:div.bg-dark-surface.rounded-lg.p-4.border.border-dark.transition-all.duration-300.hover:shadow-lg.hover:border-neon-yellow
     [:div.flex.items-center.justify-between
      [:div.flex-1.min-w-0
       [:div
        [:h3.text-lg.font-semibold.text-white (or (get parent label-key) "Unnamed")]
        (when-let [notes (get parent notes-key)]
          [:p.text-sm.text-gray-400.truncate notes])]]
      (start-timer-form parent config)]]))

(defn fetch-locations
  "The user's locations for timer location selects, alphabetical by label."
  [ctx]
  (->> (queries/all-for-user-query
        {:entity-type-str "location"
         :schema          (schema-utils/entity-schema schema :location)}
        ctx)
       (sort-by #(some-> (:location/label %) str/lower-case))
       vec))

(defn location-options
  "Options for a location select: an optional \"no location\" empty entry
   followed by the given locations, current one selected."
  [locations current & {:keys [include-empty]}]
  (concat
   (when include-empty
     [[:option {:value "" :selected (nil? current)} "no location"]])
   (for [{id :xt/id, label :location/label} locations]
     ^{:key id}
     [:option {:value (str id) :selected (= id current)}
      (or label "Unnamed")])))

(defn- card-location-select
  "Compact location select on an active card: posts on change, and the
   refresh event re-renders the section. Skipped when the entity has no
   location field or the user has no locations. The empty option only
   renders for optional location fields (meditation-log's is required)."
  [timer locations {:keys [entity-str entity-schema]}]
  (let [loc-key    (schema-utils/entity-field-key entity-str "location-id")
        field-info (schema-utils/get-field-info entity-schema loc-key)
        current    (get timer loc-key)]
    (when (and field-info (seq locations))
      [:div.flex.items-center.gap-2.mt-3
       [:span.text-sm {:aria-hidden "true"} "📍"]
       [:div.flex-1.min-w-0
        [:select.form-select.w-full
         {:name "location-id"
          :aria-label "Timer location"
          :data-enhance "choices"
          :hx-post (str "/app/timers/location/" entity-str)
          :hx-trigger "change"
          :hx-swap "none"
          :hx-vals (cheshire/generate-string {:timer-id (str (:xt/id timer))})}
         (location-options locations current
                           :include-empty (get-in field-info [:opts :optional]))]]])))

(defn active-timer-card
  "Create a card for an active timer with stop functionality"
  [timer parent-entities _ctx
   {:keys [entity-str parent-entity-key relationship-key beginning-key notes-key]
    :as config}
   & {:keys [redirect-target locations]}]
  (let [timer-parent-id (get timer relationship-key)
        parent          (first (filter #(= (:xt/id %) timer-parent-id) parent-entities))
        label-key       (schema-utils/entity-attr-key parent-entity-key "label")
        parent-name     (or (some-> parent (get label-key)) "Unknown")
        start-time      (get timer beginning-key)
        now             (t/now)
        elapsed-seconds (t/seconds (t/between start-time now))
        elapsed-hours   (quot elapsed-seconds 3600)
        elapsed-minutes (quot (mod elapsed-seconds 3600) 60)
        elapsed-str     (str elapsed-hours "h " elapsed-minutes "m")
        timer-notes     (get timer notes-key)
        redirect-target (or redirect-target (str "/app/timer/" entity-str))
        encoded         (java.net.URLEncoder/encode redirect-target "UTF-8")
        edit-url        (str "/app/crud/form/" entity-str "/edit/" (:xt/id timer)
                             "?redirect=" encoded)]
    [:div.bg-dark-surface.rounded-lg.p-4.border.border-neon-cyan.transition-all.duration-300.hover:shadow-lg
     [:div.flex.items-center.justify-between.mb-4
      [:span.text-sm.text-gray-400.uppercase.tracking-wide "Active Timer"]
      [:a.bg-red-500.bg-opacity-20.text-red-400.px-3.py-2.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all.no-underline
       {:href (str "/app/timer/" entity-str "/" (:xt/id timer) "/stop"
                   "?redirect=" encoded)}
       "End Session"]]
     [:a.block.no-underline {:href edit-url}
      [:div.flex.flex-col.space-y-1.text-white.transition-all.duration-300.hover:text-neon-cyan
       [:h3.text-lg.font-semibold parent-name]
       [:p.text-sm.text-neon-cyan (str "Running for " elapsed-str)]
       (when timer-notes
         [:p.text-sm.text-gray-400.truncate timer-notes])]]
     (card-location-select timer locations config)]))

(defn timer-page
  "Timer page showing parent entities and active timers"
  [ctx {:keys [entity-str parent-entity-str parent-query] :as config}]
  (let [label-key            (schema-utils/entity-attr-key (:parent-entity-key config) "label")
        parent-entities      (->> (queries/all-for-user-query parent-query ctx)
                                  (sort-by #(some-> (get % label-key) str/lower-case)))
        ;; Find active timers (entries with beginning but no end)
        active-timers (fetch-active-timers ctx config)
        locations     (when (seq active-timers) (fetch-locations ctx))]
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
          :hx-trigger "every 30s, refresh-active-timers from:body"
          :hx-swap "outerHTML"}
         (when (seq active-timers)
           [:div.space-y-4
            (for [timer active-timers]
              ^{:key (:xt/id timer)}
              (active-timer-card timer parent-entities ctx config
                                 :locations locations))])]]

       [:div.mb-8
        [:h2.text-xl.font-semibold.mb-4.text-white "Stats"]
        (today-stats-section ctx config parent-entities)]

       [:div.mb-8
        [:h2.text-xl.font-semibold.mb-4.text-neon-yellow "Start Timer"]
        (if (seq parent-entities)
          [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
           (for [parent parent-entities]
             ^{:key (:xt/id parent)}
             (start-timer-card parent config))]
          [:p.text-gray-400
           (str "No " parent-entity-str "s found. Create some first!")])]

       [:div.mb-8
        [:h2.text-xl.font-semibold.mb-4.text-white "Recent Logs"]
        (or (recent-logs-section ctx config parent-entities)
            [:p.text-sm.text-gray-400 "No completed logs yet."])]]))))

(defn active-timers-section
  "Return HTML for the active timers section"
  [ctx {:keys [entity-str parent-query] :as config}]
  (let [active-timers   (fetch-active-timers ctx config)
        parent-entities (when (seq active-timers)
                          (queries/all-for-user-query parent-query ctx))
        locations       (when (seq active-timers) (fetch-locations ctx))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (ui/fragment
            [:div
             {:id "active-timers-section"
              :hx-get (str "/app/timer/" entity-str "/active")
              :hx-trigger "every 30s, refresh-active-timers from:body"
              :hx-swap "outerHTML"}
             (when (seq active-timers)
               [:div.space-y-4
                (for [timer active-timers]
                  ^{:key (:xt/id timer)}
                  (active-timer-card timer parent-entities ctx config
                                     :locations locations))])])}))

(defn- sanitize-redirect
  "Allow only /app/timer-prefixed redirect targets (covers /app/timers too)."
  [target fallback]
  (if (and (string? target) (str/starts-with? target "/app/timer"))
    target
    fallback))

(defn request-param
  "Read a request param by name, tolerating keyword or string keys."
  [ctx k]
  (let [params (:params ctx)]
    (or (get params (keyword k)) (get params k))))

(defn uuid-param
  "Read a request param as a UUID; nil when absent, blank, or invalid."
  [ctx k]
  (let [raw (request-param ctx k)]
    (when (and (string? raw) (not (str/blank? raw)))
      (parse-uuid raw))))

(defn current-location-id
  "The user's persisted current location setting, if any."
  [ctx]
  (:user/current-location-id
   (queries/get-entity-by-id (:biff/db ctx) (-> ctx :session :uid))))

(defn stop-timer
  "Stop an active timer by setting the end time, returning to the issuing page.
   Honors a whitelisted redirect query param; annotation stays one tap away via
   the recent-logs edit links."
  [timer-id ctx {:keys [entity-key entity-str end-key]}]
  (let [redirect (sanitize-redirect (request-param ctx "redirect")
                                    (str "/app/timer/" entity-str))
        timer    (queries/get-entity-for-user (:biff/db ctx)
                                              timer-id
                                              (-> ctx :session :uid)
                                              entity-key)]
    (when (and timer (nil? (get timer end-key)))
      (mutations/update-entity!
       ctx
       {:entity-key entity-key,
        :entity-id  timer-id,
        :data       {end-key (t/now)}}))
    {:status  303
     :headers {"location" redirect}}))

(defn- fill-required-fields
  "Fill required schema fields absent from data: non-optional booleans default
   to false (mirroring the CRUD form path), anything else is copied from the
   most recent completed log. Returns nil when a required field remains
   unfilled — the caller falls back to the CRUD new-form."
  [ctx {:keys [entity-schema]} data fetch-template]
  (let [missing  (->> (schema-utils/extract-schema-fields entity-schema)
                      (map schema-utils/prepare-field)
                      (remove schema-utils/should-remove-system-or-user-field?)
                      (filter :input-required)
                      (remove (comp (partial contains? data) :field-key)))
        template (when (seq missing) (fetch-template ctx))]
    (reduce (fn [acc {:keys [field-key input-type]}]
              (cond
                (some? (get template field-key))
                (assoc acc field-key (get template field-key))

                (= input-type :boolean)
                (assoc acc field-key false)

                :else (reduced nil)))
            data
            missing)))

(defn start-timer
  "Create a running log for a parent entity without a form round trip: the
   primary relationship and beginning are set directly, time-zone comes from
   the user, and any other required fields are defaulted or copied from the
   most recent log. Falls back to the CRUD new-form when that isn't possible
   (e.g. first-ever log of a type with required fields)."
  [ctx {:keys [entity-key entity-str relationship-key beginning-key] :as config}]
  (let [parent-id (uuid-param ctx "parent-id")
        redirect  (sanitize-redirect (request-param ctx "redirect")
                                     (str "/app/timer/" entity-str))]
    (if-not parent-id
      {:status 303, :headers {"location" redirect}}
      (let [tz-key      (schema-utils/entity-field-key entity-str "time-zone")
            loc-key     (schema-utils/entity-field-key entity-str "location-id")
            ;; The workspace picker submits location-id with the form — trust
            ;; it (even blank) over the persisted setting, which may still be
            ;; catching up from the picker's async persistence post. Starts
            ;; without a picker (per-entity pages) read the setting.
            raw-loc     (request-param ctx "location-id")
            location-id (when (schema-utils/schema-field (:entity-schema config) loc-key)
                          (if (string? raw-loc)
                            (when-not (str/blank? raw-loc) (parse-uuid raw-loc))
                            (current-location-id ctx)))
            base        (cond-> {relationship-key parent-id
                                 beginning-key    (t/now)}
                          (schema-utils/schema-field (:entity-schema config) tz-key)
                          (assoc tz-key (get-user-time-zone ctx))

                          location-id
                          (assoc loc-key location-id))
            data        (fill-required-fields ctx
                                              config
                                              base
                                              #(first (fetch-completed-logs % config 1)))]
        (if data
          (do
            (mutations/create-entity!
             ctx
             {:entity-key entity-key,
              :data       (assoc data :user/id (-> ctx :session :uid))})
            {:status 303, :headers {"location" redirect}})
          ;; Not enough information for a one-tap start — bounce to the form
          ;; with the parent (and chosen location) preselected, preserving the
          ;; return target.
          (let [rel-param (schema-utils/ns-keyword->input-name relationship-key)]
            {:status  303
             :headers {"location"
                       (str "/app/crud/form/" entity-str "/new?"
                            rel-param "=" parent-id
                            (when location-id
                              (str "&" (schema-utils/ns-keyword->input-name loc-key)
                                   "=" location-id))
                            "&redirect=" (java.net.URLEncoder/encode redirect "UTF-8"))}}))))))

(defn set-timer-location
  "Set or clear the location on one of the user's running or completed logs.
   Clearing is only allowed when the location field is optional
   (meditation-log's is required). Responds 204 with an HX-Trigger event the
   active-timers sections listen for, so the issuing card re-renders in place."
  [ctx {:keys [entity-key entity-str entity-schema]}]
  (let [timer-id    (uuid-param ctx "timer-id")
        location-id (uuid-param ctx "location-id")
        loc-key     (schema-utils/entity-field-key entity-str "location-id")
        field-info  (schema-utils/get-field-info entity-schema loc-key)
        timer       (when timer-id
                      (queries/get-entity-for-user (:biff/db ctx)
                                                   timer-id
                                                   (-> ctx :session :uid)
                                                   entity-key))]
    (when (and timer
               field-info
               (or location-id (get-in field-info [:opts :optional])))
      (mutations/update-entity!
       ctx
       {:entity-key entity-key,
        :entity-id  timer-id,
        :data       {loc-key (or location-id :db/dissoc)}}))
    (if (get-in ctx [:headers "hx-request"])
      {:status 204, :headers {"HX-Trigger" "refresh-active-timers"}}
      {:status  303
       :headers {"location" (sanitize-redirect (request-param ctx "redirect")
                                               (str "/app/timer/" entity-str))}})))

(defn- relocate-log-data
  "Build the continuation log for a relocated timer: every schema field of
   the old log except the interval and notes (they belong to the finished
   segment), beginning now at the new location."
  [old-log
   {:keys [entity-str entity-schema beginning-key end-key notes-key]}
   location-id]
  (let [loc-key   (schema-utils/entity-field-key entity-str "location-id")
        copy-keys (->> (schema-utils/extract-schema-fields entity-schema)
                       (map schema-utils/prepare-field)
                       (remove schema-utils/should-remove-system-or-user-field?)
                       (map :field-key)
                       (remove #{beginning-key end-key notes-key}))]
    (-> (select-keys old-log copy-keys)
        (assoc beginning-key (t/now)
               loc-key location-id))))

(defn relocate-timer!
  "End a running timer now and start a continuation log at location-id."
  [ctx {:keys [entity-key end-key] :as config} timer location-id]
  (mutations/update-entity!
   ctx
   {:entity-key entity-key,
    :entity-id  (:xt/id timer),
    :data       {end-key (t/now)}})
  (mutations/create-entity!
   ctx
   {:entity-key entity-key,
    :data       (assoc (relocate-log-data timer config location-id)
                       :user/id (-> ctx :session :uid))}))

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
