(ns tech.jgood.gleanmo.app.overview
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.app.timers :as timers-app]
   [tech.jgood.gleanmo.crud.views :as crud-views]
   [tech.jgood.gleanmo.crud.views.formatting :as fmt]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.timer.routes :as timer-routes]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(def recent-activity-types
  ["task"
   "habit-log"
   "meditation-log"
   "bm-log"
   "medication-log"
   "project-log"
   "reading-log"
   "calendar-event"
   "exercise-session"
   "exercise-log"
   "exercise-set"
   "symptom-episode"
   "symptom-log"])

(def recent-activity-order-keys
  {"habit-log"      :habit-log/timestamp,
   "meditation-log" :meditation-log/beginning,
   "bm-log"         :bm-log/timestamp,
   "medication-log" :medication-log/timestamp,
   "project-log"    :project-log/beginning,
   "reading-log"    :reading-log/beginning,
   "calendar-event" :calendar-event/beginning,
   "exercise-session" :exercise-session/beginning,
   "exercise-log"   :exercise-log.interval/beginning,
   "exercise-set"   :exercise-set.interval/beginning,
   "symptom-episode" :symptom-episode/beginning,
   "symptom-log"    :symptom-log/timestamp})

(defn- overview-activity-types
  [ctx]
  (let [{:keys [show-bm-logs]} (db/resolve-user-settings ctx)]
    (if show-bm-logs
      recent-activity-types
      (vec (remove #{"bm-log"} recent-activity-types)))))

(def event-colors
  {:blue   {:accent "#3b82f6" :muted "rgba(59,130,246,0.18)"}
   :cyan   {:accent "#06b6d4" :muted "rgba(6,182,212,0.18)"}
   :green  {:accent "#22c55e" :muted "rgba(34,197,94,0.18)"}
   :violet {:accent "#8b5cf6" :muted "rgba(139,92,246,0.18)"}
   :red    {:accent "#ef4444" :muted "rgba(239,68,68,0.18)"}
   :orange {:accent "#f97316" :muted "rgba(249,115,22,0.18)"}
   :default {:accent "#8b949e" :muted "rgba(139,148,158,0.18)"}})

(defn event-accent
  [color-key]
  (get event-colors color-key (get event-colors :default)))

(def recent-activity-accents
  {"task"           {:accent "#84cc16", :muted "rgba(132,204,22,0.16)"},
   "habit-log"      {:accent "#8b5cf6", :muted "rgba(139,92,246,0.16)"},
   "meditation-log" {:accent "#22c55e", :muted "rgba(34,197,94,0.16)"},
   "bm-log"         {:accent "#0ea5e9", :muted "rgba(14,165,233,0.16)"},
   "medication-log" {:accent "#f59e0b", :muted "rgba(245,158,11,0.16)"},
   "project-log"    {:accent "#3b82f6", :muted "rgba(59,130,246,0.16)"},
   "calendar-event" {:accent "#ec4899", :muted "rgba(236,72,153,0.16)"},
   :default         {:accent "#8b949e", :muted "rgba(139,148,158,0.16)"}})

(defn accent-style
  [etype]
  (get recent-activity-accents etype (get recent-activity-accents :default)))

(defn- user-zone
  [ctx]
  (try
    (java.time.ZoneId/of (shared/get-user-time-zone ctx))
    (catch Exception _
      (java.time.ZoneId/of "UTC"))))

(defn- local-date->instant
  [zone local-date]
  (-> local-date
      (t/at (t/time "00:00"))
      (t/in zone)
      t/instant))

(defn- ->instant
  [ctx v]
  (cond
    (nil? v) nil
    (instance? java.time.Instant v) v
    (instance? java.time.LocalDate v) (local-date->instant (user-zone ctx) v)
    :else (try
            (t/instant v)
            (catch Exception _
              nil))))

(defn- relative-time
  [ctx inst]
  (when inst
    (let [zone     (user-zone ctx)
          now      (t/in (t/now) zone)
          ts       (t/in inst zone)
          in-future (t/> ts now)
          dur      (if in-future
                     (t/between now ts)
                     (t/between ts now))
          secs    (t/seconds dur)
          suffix  (when-not in-future " ago")]
      (cond
        (< secs 60)     (if in-future "in under a minute" "just now")
        (< secs 3600)   (let [m (t/minutes dur)]
                          (str (when in-future "in ")
                               m
                               " min"
                               (when (not= m 1) "s")
                               suffix))
        (< secs 86400)  (let [h (t/hours dur)]
                          (str (when in-future "in ")
                               h
                               " hr"
                               (when (not= h 1) "s")
                               suffix))
        (< secs 604800) (let [d (t/days dur)]
                          (str (when in-future "in ")
                               d
                               " day"
                               (when (not= d 1) "s")
                               suffix))
        :else           (let [w (int (/ (t/days dur) 7))]
                          (str (when in-future "in ")
                               w
                               " week"
                               (when (not= w 1) "s")
                               suffix))))))

(defn- find-time-field
  [entity etype pattern]
  (->> entity
       (filter (fn [[k v]]
                 (let [k-ns (namespace k)]
                   (and v
                        (keyword? k)
                        (or (= k-ns etype)
                            (str/starts-with? k-ns (str etype ".")))
                        (str/includes? (name k) pattern)))))
       first))

(defn- task-anchor
  [entity]
  (or (:task/done-at entity)
      (:task/due-on entity)
      (:task/focus-date entity)))

(defn- activity-time
  "Pick the most meaningful timeline bounds for an entity."
  [ctx entity]
  (let [etype            (some-> entity
                                 ::sm/type
                                 name)
        [timestamp-key timestamp] (when etype
                                    (find-time-field entity etype "timestamp"))
        [beginning-key beginning] (when etype
                                    (find-time-field entity etype "beginning"))
        [end-key end]             (when etype
                                    (find-time-field entity etype "end"))
        task-time        (when (= etype "task")
                           (task-anchor entity))
        created-at       (::sm/created-at entity)
        start-instant    (or (->instant ctx timestamp)
                             (->instant ctx beginning)
                             (->instant ctx task-time)
                             (->instant ctx created-at))
        end-instant      (->instant ctx end)
        source           (cond
                           timestamp  :timestamp
                           beginning  :beginning
                           task-time  :task-date
                           created-at :created-at
                           :else      :unknown)]
    {:instant       start-instant,
     :end-instant   end-instant,
     :sort-instant  (or start-instant created-at (t/epoch)),
     :source        source,
     :timestamp-key timestamp-key,
     :beginning-key beginning-key,
     :end-key       end-key}))

(defn- readable-label
  [s]
  (-> s
      (str/replace "-" " ")
      (str/capitalize)))

(defn- entity-title
  [entity]
  (let [etype   (name (::sm/type entity))
        label-k (keyword etype "label")
        id-str  (some-> (:xt/id entity)
                        str)]
    (or (some-> (get entity label-k)
                str
                not-empty)
        (some-> (::sm/created-at entity)
                str)
        (when id-str
          (subs id-str 0 (min 8 (count id-str))))
        "Item")))

(defn- present-value?
  [v]
  (cond
    (nil? v)    false
    (string? v) (not (str/blank? v))
    (coll? v)   (boolean (seq v))
    :else       true))

(defn- relationship-label
  [db entity-id]
  (when entity-id
    (when-let [entity (db/get-entity-by-id db entity-id)]
      (let [etype     (some-> entity
                              ::sm/type
                              name)
            label-key (when etype (keyword etype "label"))]
        (or (when (and label-key (contains? entity label-key))
              (get entity label-key))
            (str (subs (str entity-id) 0 8) "..."))))))

(defn- activity-time-meta
  [entity ctx]
  (when-let [etype (some-> entity ::sm/type name)]
    (when-let [entity-schema (get schema-registry/schema (keyword etype))]
      (let [display-fields (crud-views/get-display-fields entity-schema)
            {:keys [mode label time-str time-zone duration since-instant]}
            (crud-views/build-time-display entity etype display-fields ctx)
            relative (relative-time ctx since-instant)]
        (when mode
          {:mode     mode
           :label    label
           :time-str time-str
           :time-zone time-zone
           :duration duration
           :relative relative})))))

(declare timeline-time)

(defn- elapsed-duration
  [start]
  (when start
    (crud-views/format-duration start (t/now))))

(defn- active-timer-summaries
  [ctx]
  (->> timers-app/timer-entities
       (mapcat
        (fn [{:keys [entity-key entity-str display-name route]}]
          (let [{:keys [relationship-key beginning-key] :as config}
                (timer-routes/timer-config {:entity-key entity-key
                                            :entity-str entity-str})]
            (for [timer (timer-routes/fetch-active-timers ctx config)
                  :let [start     (get timer beginning-key)
                        parent-id (get timer relationship-key)]]
              {:id        (:xt/id timer)
               :href      route
               :entity-str entity-str
               :type      display-name
               :label     (or (relationship-label (:biff/db ctx) parent-id)
                              "Active timer")
               :start     start
               :elapsed   (elapsed-duration start)}))))))

(defn- render-active-timers
  [ctx]
  (let [timers (active-timer-summaries ctx)]
    (when (seq timers)
      [:div.space-y-2
       [:div.flex.items-center.gap-3
        [:div.h-px.flex-1.bg-dark-border]
        [:div.text-xs.font-semibold.uppercase.tracking-wide.text-neon-cyan
         "Running now"]
        [:div.h-px.flex-1.bg-dark-border]]
       [:div.grid.grid-cols-1.md:grid-cols-3.gap-2
        (for [{:keys [id href type label start elapsed]} timers]
          [:a.block.rounded-md.border.border-neon-cyan.bg-dark-surface.px-3.py-2.no-underline.hover:bg-dark-light.transition-colors
           {:key  (str id)
            :href href}
           [:div.flex.items-center.justify-between.gap-3
            [:div.min-w-0
             [:div.text-sm.font-semibold.text-white.truncate label]
             [:div.text-xs.text-gray-500.uppercase.tracking-wide type]]
            [:div.text-right.shrink-0
             [:div.text-xs.text-neon-cyan elapsed]
             [:div.text-xs.text-gray-500 (or (timeline-time ctx start) "")]]]])]])))

(defn- timeline-date
  [ctx instant]
  (some-> instant
          (t/in (user-zone ctx))
          t/date))

(defn- timeline-time
  [ctx instant]
  (when instant
    (->> (t/in instant (user-zone ctx))
         (t/format (t/formatter "h:mm a")))))

(defn- date-label
  [ctx instant]
  (let [zone  (user-zone ctx)
        now   (t/date (t/in (t/now) zone))
        date  (timeline-date ctx instant)]
    (cond
      (nil? date) ""
      (= date now) "Today"
      (= date (t/>> now (t/new-period 1 :days))) "Tomorrow"
      (= date (t/<< now (t/new-period 1 :days))) "Yesterday"
      :else (str date))))

(defn- timeline-item-sort-key
  [_ctx entity]
  (let [now     (t/now)
        instant (get-in entity [::activity-time :sort-instant])
        future? (and instant (t/> instant now))
        millis  (some-> instant t/long)]
    (if future?
      [0 millis]
      [1 (- (or millis 0))])))

(defn- dedupe-entities
  [entities]
  (vals
   (reduce (fn [acc entity]
             (assoc acc (:xt/id entity) entity))
           {}
           entities)))

(defn dashboard-stats
  "Compute lightweight dashboard stats using a bounded set of entities."
  [ctx]
  (let [user-id       (-> ctx :session :uid)
        user-settings (db/resolve-user-settings ctx)
        zone          (user-zone ctx)
        items         (->> (db/dashboard-recent-entities
                            (:biff/db ctx)
                            user-id
                            {:entity-types    (overview-activity-types ctx)
                             :per-type-limit  200
                             :order-keys      recent-activity-order-keys
                             :user-settings   user-settings})
                           (map #(assoc % ::activity-time (activity-time ctx %))))
        now-zoned     (t/in (t/now) zone)
        today         (t/date now-zoned)
        week-start    (t/<< today (t/new-period 6 :days))
        activity-date (fn [entity]
                        (some-> entity ::activity-time :instant (t/in zone) t/date))
        count-since   (fn [start-date]
                        (count (filter (fn [e]
                                         (when-let [d (activity-date e)]
                                           (and (t/>= d start-date)
                                                (t/<= d today))))
                                       items)))
        entries-today (count-since today)
        entries-week  (count-since week-start)
        active-timers (reduce
                       (fn [acc {:keys [entity-key entity-str]}]
                         (let [config (timer-routes/timer-config
                                       {:entity-key entity-key
                                        :entity-str entity-str})
                               timers (timer-routes/fetch-active-timers ctx config)]
                           (+ acc (count timers))))
                       0
                       timers-app/timer-entities)
        distinct-types (count (distinct (map ::sm/type items)))
        now-tasks (db/count-tasks-by-state (:biff/db ctx) user-id :now
                                           :user-settings user-settings)]
    (log/info "Dashboard stats"
              {:entries-today entries-today
               :entries-week  entries-week
               :active-timers active-timers
               :distinct-types distinct-types
               :now-tasks now-tasks
               :sample-count (count items)
               :week-start week-start})
    {"Now tasks"        now-tasks
     "Entries today"    entries-today
     "This week"        entries-week
     "Active timers"    active-timers}))

(defn upcoming-events
  "Fetch near-future calendar events."
  [ctx {:keys [limit], :or {limit 5}}]
  (let [user-id (-> ctx :session :uid)]
    (db/dashboard-upcoming-events
     (:biff/db ctx)
     user-id
     {:limit         limit
      :user-settings (db/resolve-user-settings ctx)})))

(defn recent-activity
  "Fetch recent entities across primary log types."
  [ctx {:keys [limit], :or {limit 10}}]
  (let [user-id (-> ctx :session :uid)
        now     (t/now)]
    (->> (db/dashboard-recent-entities
          (:biff/db ctx)
          user-id
          {:entity-types   (overview-activity-types ctx)
           :per-type-limit (* 2 limit)
           :order-keys     recent-activity-order-keys
           :user-settings  (db/resolve-user-settings ctx)})
         (map #(assoc % ::activity-time (activity-time ctx %)))
         (remove #(t/> (get-in % [::activity-time :sort-instant]) now))
         (sort-by (comp :sort-instant ::activity-time) #(compare %2 %1))
         (take limit))))

(defn timeline-activity
  "Fetch past and near-future timeline entries across timeline-worthy entities."
  [ctx {:keys [limit upcoming-limit], :or {limit 18 upcoming-limit 6}}]
  (let [recent   (recent-activity ctx {:limit (* 2 limit)})
        upcoming (upcoming-events ctx {:limit upcoming-limit})]
    (->> (concat recent upcoming)
         dedupe-entities
         (map #(assoc % ::activity-time (activity-time ctx %)))
         (sort-by #(timeline-item-sort-key ctx %))
         (take limit))))

(defn render-upcoming-events
  "Render near-future calendar events."
  [ctx]
  (let [events (upcoming-events ctx {:limit 5})]
    [:div.border.border-dark.bg-dark-surface.rounded-lg.p-4.space-y-3
     [:div.space-y-1
      [:p.text-xs.text-gray-400.uppercase.tracking-wide "Upcoming events"]]
     (if (seq events)
       [:div.grid.grid-cols-1.md:grid-cols-2.gap-2
        (for [e events
              :let [label   (or (:calendar-event/label e) "Untitled event")
                    start   (or (:calendar-event/beginning e) (::sm/created-at e))
                    rel     (relative-time ctx start)
                    {:keys [accent]} (event-accent (:calendar-event/color-neon e))
                    href    (str "/app/crud/form/calendar-event/edit/" (:xt/id e))]]
          [:a {:class "group relative overflow-hidden rounded-lg border border-dark bg-dark/80 hover:bg-dark transition-colors"
               :key (str (:xt/id e))
               :href href
               :style {:box-shadow "0 6px 18px rgba(0,0,0,0.22)"}}
           [:div.absolute.left-0.top-0.bottom-0.w-1 {:style {:background accent}}]
           [:div.flex.items-start.gap-3.px-4.py-3
            [:div.flex-1.min-w-0.space-y-1
             [:div.flex.items-center.gap-2
              [:div.font-semibold.text-white.truncate label]
              (when rel
                [:span.text-xs.uppercase.tracking-wide.text-gray-500.ml-auto rel])]
             (when-let [desc (:calendar-event/summary e)]
               [:div.text-sm.text-gray-400.truncate desc])]]])]
       [:p.text-sm.text-gray-400 "No upcoming events found."])]))

(defn stats-strip
  "Simple stat strip for the dashboard. Accepts a map of stat label->value."
  [stats]
  [:div.border.border-dark.bg-dark-surface.rounded-lg.p-4.text-sm
   [:div.grid.grid-cols-2.md:grid-cols-4.gap-4
    (for [[label value] stats]
      [:div.flex.flex-col.gap-1 {:key label}
       [:span.text-gray-400.uppercase.tracking-wide.text-xs label]
       [:span.text-white.font-semibold (or value "—")]])]])

(defn- duration-minutes
  [start end]
  (when (and start end)
    (max 1 (t/minutes (t/between start end)))))

(defn- duration-height
  [minutes]
  (when minutes
    (let [day-ratio (/ minutes 1440.0)
          scaled    (+ 24 (* 128 (Math/sqrt day-ratio)))]
      (int (min 160 (max 28 scaled))))))

(defn- timeline-marker
  [{:keys [accent muted interval? minutes]}]
  [:span.absolute.left-0.top-4.flex.w-8.justify-center
   (if interval?
     [:span.block.w-2.rounded-full
      {:style {:background accent
               :height     (str (duration-height minutes) "px")
               :box-shadow (str "0 0 0 5px rgba(13,17,23,1), 0 0 0 8px " muted)}}]
     [:span.block.h-3.w-3.rounded-full
      {:style {:background accent
               :box-shadow (str "0 0 0 5px rgba(13,17,23,1), 0 0 0 8px " muted)}}])])

(defn- formatted-field-value
  [entity {:keys [field-key input-type]} {:keys [biff/db], :as ctx}]
  (let [value (get entity field-key)]
    (case input-type
      :single-relationship
      (or (relationship-label db value) "")

      :many-relationship
      (str/join ", " (keep #(relationship-label db %) value))

      :enum
      (some-> value name)

      :set-enum
      (str/join ", " (map name (sort-by name value)))

      :boolean
      (if value "Yes" "No")

      (:string :number :float :int :local-date)
      (str value)

      (fmt/format-cell-value input-type value ctx))))

(defn- priority-field-values
  [entity ctx]
  (when-let [etype (some-> entity ::sm/type name)]
    (when-let [entity-schema (get schema-registry/schema (keyword etype))]
      (let [display-fields (crud-views/get-display-fields entity-schema)]
        (->> display-fields
             (filter crud-views/prioritized-field?)
             (sort-by crud-views/get-field-priority)
             (keep (fn [{:keys [field-key input-label], :as field}]
                     (let [value (get entity field-key)]
                       (when (present-value? value)
                         {:field-key field-key
                          :label     input-label
                          :priority  (crud-views/get-field-priority field)
                          :node      (formatted-field-value entity field ctx)})))))))))

(defn- render-title-field
  [field fallback]
  [:div.text-base.font-semibold.text-white.truncate
   (or (:node field) fallback)])

(defn- priority-detail-class
  [priority]
  (cond
    (= priority 1) "text-sm text-gray-200"
    (= priority 2) "text-sm text-gray-300"
    (= priority 3) "text-xs text-gray-400"
    :else "text-xs text-gray-500"))

(defn- render-priority-detail
  [{:keys [field-key label node priority]}]
  [:span.inline-flex.items-baseline.gap-1.min-w-0
   {:key (str field-key)
    :class (priority-detail-class priority)}
   [:span.text-gray-500.whitespace-nowrap (str label ":")]
   [:span.truncate node]])

(defn- render-meta
  [{:keys [etype id-short relative duration]}]
  [:div.flex.items-center.justify-end.gap-2.text-xs.text-gray-500.uppercase.tracking-wide
   [:span (readable-label etype)]
   (when relative
     [:span relative])
   (when duration
     [:span duration])
   [:span.font-mono.normal-case.tracking-normal.opacity-70 (str id-short "...")]])

(defn render-activity-feed
  "Render activity in a single chronological timeline across entity types."
  [ctx recent-items]
  (let [items recent-items]
    (if (seq items)
      [:div.relative
       [:div.absolute.left-4.top-1.bottom-1.w-px.bg-dark-border.pointer-events-none]
       [:div.space-y-2
        (for [[idx entity] (map-indexed vector items)]
          (let [etype           (name (::sm/type entity))
                {:keys [accent muted]} (accent-style etype)
                href            (str "/app/crud/form/" etype "/edit/" (:xt/id entity))
                priority-fields (priority-field-values entity ctx)
                title-field     (first priority-fields)
                detail-fields   (rest priority-fields)
                time-meta       (activity-time-meta entity ctx)
                id-short        (subs (str (:xt/id entity)) 0 8)
                start           (get-in entity [::activity-time :instant])
                end             (get-in entity [::activity-time :end-instant])
                minutes         (duration-minutes start end)
                interval?       (boolean minutes)
                date            (timeline-date ctx start)
                prev-date       (when (pos? idx)
                                  (timeline-date
                                   ctx
                                   (get-in (nth items (dec idx))
                                           [::activity-time :instant])))]
            [:div {:key (str (:xt/id entity))}
             (when (not= date prev-date)
               [:div.relative.flex.items-center.gap-3.pl-12.pt-6.pb-2
                [:div.h-px.flex-1.bg-dark-border]
                [:div.text-xs.font-semibold.uppercase.tracking-wide.text-gray-500
                 (date-label ctx start)]
                [:div.h-px.flex-1.bg-dark-border]])
             [:a.group.relative.block.pl-12.pr-3.rounded-md.border.transition-colors.duration-200
              {:class "py-3 hover:bg-dark-light"
               :href  href
               :style {:background   "rgba(13,17,23,0.78)"
                       :border-color "rgba(48,54,61,0.85)"}}
              (timeline-marker {:accent accent
                                :muted muted
                                :interval? interval?
                                :minutes minutes})
              [:div.space-y-2.min-w-0
               [:div.flex.items-start.justify-between.gap-4
                [:div.flex.items-baseline.gap-3.min-w-0
                 [:div.w-16.shrink-0.text-sm.font-semibold.text-white
                  (or (timeline-time ctx start) "")]
                 [:div.min-w-0.flex-1
                  (render-title-field title-field (entity-title entity))]]
                [:div.shrink-0.hidden.sm:block
                 (render-meta {:etype etype
                               :id-short id-short
                               :relative (:relative time-meta)
                               :duration (:duration time-meta)})]]
               (when (seq detail-fields)
                 [:div.ml-20.flex.flex-wrap.gap-x-3.gap-y-1
                  (for [field detail-fields]
                    (render-priority-detail field))])
               [:div.ml-20.sm:hidden
                (render-meta {:etype etype
                              :id-short id-short
                              :relative (:relative time-meta)
                              :duration (:duration time-meta)})]]]]))]]
      [:p.text-sm.text-gray-400 "No recent activity yet. Keep logging!"])))

(defn render-recent-activity
  "Render dashboard stats, upcoming events, and recent activity feed."
  [ctx recent-items]
  (let [items recent-items
        stats (dashboard-stats ctx)]
    [:div.space-y-4
     (render-activity-feed ctx items)
     [:div.opacity-70
      (stats-strip stats)]]))

(defn- lazy-container
  "HTMX-enabled wrapper so sections can stream in after the shell renders."
  [id hx-url placeholder & [{:keys [delay-ms]}]]
  (let [trigger (if delay-ms
                  (str "load delay:" delay-ms "ms")
                  "load")]
    [:div {:id id
           :hx-get hx-url
           :hx-trigger trigger
           :hx-swap "outerHTML"}
     [:div {:class "border border-dark bg-dark-surface rounded-lg p-4 text-sm text-gray-500"}
      [:div {:class "flex items-center justify-between"}
       [:span {:class "text-gray-400"} placeholder]
       [:span {:class "text-xs"} "Loading…"]]
      [:div {:class "mt-3 h-3 rounded bg-dark-light opacity-70 animate-pulse"}]
      [:div {:class "mt-2 h-3 rounded bg-dark-light opacity-60 animate-pulse w-2/3"}]]]))

(defn stats-section
  [ctx]
  [:div#overview-stats
   (stats-strip (dashboard-stats ctx))])

(defn upcoming-events-section
  [ctx]
  [:div#overview-events
   (render-upcoming-events ctx)])

(defn recent-activity-section
  [{:keys [params] :as ctx}]
  (let [limit (try
                (some-> (:limit params) Integer/parseInt)
                (catch Exception _ nil))
        items (timeline-activity ctx {:limit (or limit 18)})]
    [:div#overview-recent
     [:div.space-y-5
      (render-active-timers ctx)
      (render-activity-feed ctx items)]]))

(defn overview-shell
  "Top-level layout for the home overview page; sections hydrate via HTMX."
  [_ctx]
  [:div.flex.flex-col.space-y-5
   (lazy-container "overview-recent" "/app/overview/recent" "Loading timeline")
   [:div.opacity-70
    (lazy-container "overview-stats" "/app/overview/stats" "Loading stats"
                    {:delay-ms 120})]])

(defn stats-fragment
  [ctx]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (ui/fragment ctx (stats-section ctx))})

(defn upcoming-events-fragment
  [ctx]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (ui/fragment ctx (upcoming-events-section ctx))})

(defn recent-activity-fragment
  [ctx]
  {:status  200
   :headers {"content-type" "text/html"}
   :body    (ui/fragment ctx (recent-activity-section ctx))})
