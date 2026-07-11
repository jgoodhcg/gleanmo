(ns tech.jgood.gleanmo.app.overview
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.app.timers :as timers-app]
   [tech.jgood.gleanmo.crud.views :as crud-views]
   [tech.jgood.gleanmo.crud.views.formatting :as fmt]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.db.relation-labels :as rel]
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
   "exercise-block"
   "symptom-episode"
   "symptom-log"
   "mood-log"
   "boulder-session"])

(def recent-activity-order-keys
  {"habit-log"      :habit-log/timestamp,
   "meditation-log" :meditation-log/beginning,
   "bm-log"         :bm-log/timestamp,
   "medication-log" :medication-log/timestamp,
   "project-log"    :project-log/beginning,
   "reading-log"    :reading-log/beginning,
   "calendar-event" :calendar-event/beginning,
   "exercise-session" :exercise-session/beginning,
   "exercise-block" :exercise-block/beginning,
   "symptom-episode" :symptom-episode/beginning,
   "symptom-log"    :symptom-log/timestamp,
   "mood-log"       :mood-log/timestamp,
   "boulder-session" :boulder-session/beginning})

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
   "reading-log"    {:accent "#f97316", :muted "rgba(249,115,22,0.16)"},
   "exercise-session" {:accent "#ef4444", :muted "rgba(239,68,68,0.16)"},
   "exercise-block" {:accent "#ef4444", :muted "rgba(239,68,68,0.16)"},
   "symptom-log"    {:accent "#f43f5e", :muted "rgba(244,63,94,0.16)"},
   "mood-log"       {:accent "#06b6d4", :muted "rgba(6,182,212,0.16)"},
   "boulder-session" {:accent "#84cc16", :muted "rgba(132,204,22,0.16)"},
   :default         {:accent "#8b949e", :muted "rgba(139,148,158,0.16)"}})

(defn accent-style
  [etype]
  (get recent-activity-accents etype (get recent-activity-accents :default)))

(def timeline-type-order
  ["reading-log"
   "meditation-log"
   "project-log"
   "task"
   "medication-log"
   "habit-log"
   "bm-log"
   "symptom-log"
   "mood-log"
   "boulder-session"
   "calendar-event"
   "exercise-session"
   "exercise-block"])

(def timeline-type-meta
  {"reading-log"      {:code "READ" :label "reading log" :icon-key :book}
   "meditation-log"  {:code "MED" :label "meditation log" :icon-key :medit}
   "project-log"     {:code "PROJ" :label "project log" :icon-key :project}
   "task"            {:code "TASK" :label "task" :icon-key :task}
   "medication-log"  {:code "MEDS" :label "medication log" :icon-key :pill}
   "habit-log"       {:code "HABIT" :label "habit log" :icon-key :habit}
   "bm-log"          {:code "BM" :label "bm log" :icon-key :drop}
   "symptom-log"     {:code "SYMP" :label "symptom log" :icon-key :pulse}
   "mood-log"        {:code "MOOD" :label "mood log" :icon-key :pulse}
   "boulder-session" {:code "CLIMB" :label "boulder session" :icon-key :dumbbell}
   "calendar-event"  {:code "CAL" :label "calendar event" :icon-key :calendar}
   "exercise-session" {:code "EX" :label "exercise session" :icon-key :dumbbell}
   "exercise-block"  {:code "BLOCK" :label "exercise block" :icon-key :dumbbell}
   :default          {:code "ITEM" :label "item" :icon-key :pin}})

(def status-styles
  {:running    {:ring "#22d3ee" :icon "#22d3ee" :shadow "0 0 0 4px rgba(34,211,238,.10)"
                :badge "LIVE" :badge-style {:color "#22d3ee" :background "rgba(34,211,238,0.12)"}}
   :actionable {:ring "#f5a524" :icon "#f5a524" :shadow "none"
                :badge "DUE TODAY" :badge-style {:color "#f5a524" :background "rgba(245,165,36,0.12)"}}
   :scheduled  {:ring "#8b5cf6" :icon "#a78bfa" :shadow "none"
                :badge "SCHEDULED" :badge-style {:color "#a78bfa" :background "rgba(139,92,246,0.12)"}}
   :normal     {:ring "#2a3140" :icon "#9aa4b2" :shadow "none"
                :badge nil :badge-style nil}})

(defn- type-meta
  [etype]
  (get timeline-type-meta etype (get timeline-type-meta :default)))

(defn- url-encode
  [s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn- edit-form-url
  [etype entity-id]
  (str "/app/crud/form/" etype "/edit/" entity-id
       "?redirect=" (url-encode "/app")))

(defn- icon-svg
  [icon-key size]
  (let [attrs {:width size
               :height size
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width "1.7"
               :stroke-linecap "round"
               :stroke-linejoin "round"}]
    (case icon-key
      :book
      [:svg attrs
       [:path {:d "M12 6.2C10.4 4.9 8.3 4.2 5 4.2v13.6c3.3 0 5.4.7 7 2 1.6-1.3 3.7-2 7-2V4.2c-3.3 0-5.4.7-7 2z"}]
       [:line {:x1 "12" :y1 "6.2" :x2 "12" :y2 "19.8"}]]

      :medit
      [:svg attrs
       [:circle {:cx "12" :cy "12" :r "7.5"}]
       [:circle {:cx "12" :cy "12" :r "2.5"}]]

      :project
      [:svg attrs
       [:rect {:x "3.5" :y "8" :width "17" :height "11.5" :rx "1.5"}]
       [:path {:d "M9 8V6.5A1.5 1.5 0 0 1 10.5 5h3A1.5 1.5 0 0 1 15 6.5V8"}]
       [:line {:x1 "3.5" :y1 "12.5" :x2 "20.5" :y2 "12.5"}]]

      :task
      [:svg attrs
       [:rect {:x "4" :y "4" :width "16" :height "16" :rx "3"}]
       [:polyline {:points "8 12 11 15 16 9"}]]

      :pill
      [:svg attrs
       [:rect {:x "3.5" :y "8.5" :width "17" :height "7" :rx "3.5"}]
       [:line {:x1 "12" :y1 "8.5" :x2 "12" :y2 "15.5"}]]

      :habit
      [:svg attrs
       [:path {:d "M4.5 12a7.5 7.5 0 0 1 12.9-5.2L19.5 8.9"}]
       [:polyline {:points "19.5 4.4 19.5 8.9 15 8.9"}]
       [:path {:d "M19.5 12a7.5 7.5 0 0 1-12.9 5.2L4.5 15.1"}]
       [:polyline {:points "4.5 19.6 4.5 15.1 9 15.1"}]]

      :drop
      [:svg attrs
       [:path {:d "M12 3.5c3.1 3.8 6 6.8 6 9.9a6 6 0 0 1-12 0c0-3.1 2.9-6.1 6-9.9z"}]]

      :pulse
      [:svg attrs
       [:polyline {:points "3 12 8 12 10.5 5 14 19 16.5 12 21 12"}]]

      :dumbbell
      [:svg attrs
       [:rect {:x "5.5" :y "7.5" :width "3" :height "9" :rx "1"}]
       [:rect {:x "15.5" :y "7.5" :width "3" :height "9" :rx "1"}]
       [:line {:x1 "8.5" :y1 "12" :x2 "15.5" :y2 "12"}]
       [:line {:x1 "3" :y1 "9.5" :x2 "3" :y2 "14.5"}]
       [:line {:x1 "21" :y1 "9.5" :x2 "21" :y2 "14.5"}]]

      :calendar
      [:svg attrs
       [:rect {:x "4" :y "5.5" :width "16" :height "14.5" :rx "1.5"}]
       [:line {:x1 "4" :y1 "10" :x2 "20" :y2 "10"}]
       [:line {:x1 "9" :y1 "3" :x2 "9" :y2 "7"}]
       [:line {:x1 "15" :y1 "3" :x2 "15" :y2 "7"}]]

      [:svg attrs
       [:circle {:cx "12" :cy "10" :r "6"}]
       [:polyline {:points "7.5 14.5 12 21 16.5 14.5"}]])))

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
  ;; Sequential on purpose: these overlap the dashboard cascade via a future,
  ;; and total in-flight queries are kept low to avoid thrashing the small
  ;; prod box (see roadmap/dashboard-performance.md).
  (->> timers-app/timer-entities
       (map
        (fn [{:keys [entity-key entity-str display-name]}]
          (let [{:keys [relationship-key beginning-key] :as config}
                (timer-routes/timer-config {:entity-key entity-key
                                            :entity-str entity-str})]
            (doall
             (for [timer (timer-routes/fetch-active-timers ctx config)
                   :let [start     (get timer beginning-key)
                         parent-id (get timer relationship-key)]]
               {:id        (:xt/id timer)
                :href      (edit-form-url entity-str (:xt/id timer))
                :entity-str entity-str
                :type      display-name
                :label     (or (rel/relationship-label ctx parent-id)
                               "Active timer")
                :start     start
                :elapsed   (elapsed-duration start)})))))
       (apply concat)))

(defn- render-active-timers
  [ctx timers]
  (when (seq timers)
    [:section.space-y-3
     [:div.flex.items-center.gap-3
      [:span.h-2.w-2.rounded-full.bg-neon-cyan.animate-pulse]
      [:div.text-xs.font-semibold.uppercase.tracking-wide.text-neon-cyan
       "Running now"]
      [:div.h-px.flex-1.bg-dark-border]
      [:div.text-xs.text-gray-500 (str (count timers) " active")]]
     [:div.grid.grid-cols-1.md:grid-cols-3.gap-3
      (for [{:keys [id href entity-str label start elapsed]} timers
            :let [meta (type-meta entity-str)
                  {:keys [icon-key code]} meta
                  type-label (:label meta)]]
        [:a.block.relative.overflow-hidden.rounded-lg.bg-dark-surface.px-4.py-3.no-underline.hover:bg-dark-light.transition-colors
         {:key (str id)
          :href href
          :style {:border "1px solid #1e2430"}}
         [:div.absolute.left-0.top-0.bottom-0.w-1.bg-neon-cyan]
         [:div.flex.items-center.justify-between.gap-3
          [:div.flex.items-center.gap-3.min-w-0
           [:div.flex.h-8.w-8.shrink-0.items-center.justify-center.rounded-full.bg-dark.text-neon-cyan
            (icon-svg icon-key 16)]
           [:div.min-w-0
            [:div.text-sm.font-semibold.text-white.truncate label]
            [:div.text-xs.text-gray-500.uppercase.tracking-wide
             (or type-label code)]]]
          [:div.text-right.shrink-0
           [:div.text-sm.font-semibold.text-neon-cyan elapsed]
           [:div.text-xs.text-gray-500 (str "started " (or (timeline-time ctx start) ""))]]]])]]))

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

(def ^:private overview-per-type-limit
  "Per-type fetch bound shared by the timeline and the stats counts."
  100)

(defn- fetch-overview-items
  "Single bounded fetch of recent entities shared by the timeline and stats."
  [ctx]
  (let [user-id (-> ctx :session :uid)]
    (->> (db/dashboard-recent-entities
          (:biff/db ctx)
          user-id
          {:entity-types   (overview-activity-types ctx)
           :per-type-limit overview-per-type-limit
           :order-keys     recent-activity-order-keys
           :user-settings  (db/resolve-user-settings ctx)})
         (map #(assoc % ::activity-time (activity-time ctx %))))))

(defn dashboard-stats
  "Compute lightweight dashboard stats from an already-fetched bounded set of
   entities and active-timer summaries. The single-arg arity fetches its own
   data for standalone use (e.g. the /app/overview/stats fragment)."
  ([ctx]
   (dashboard-stats ctx (fetch-overview-items ctx) (active-timer-summaries ctx)))
  ([ctx items timers]
   (let [user-id       (-> ctx :session :uid)
         user-settings (db/resolve-user-settings ctx)
         zone          (user-zone ctx)
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
         active-timers (count timers)
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
      "Active timers"    active-timers})))

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
  "Select recent (non-future) entries from already-fetched overview items."
  [ctx items {:keys [limit], :or {limit 10}}]
  (let [now (t/now)]
    (->> items
         (remove #(t/> (get-in % [::activity-time :sort-instant]) now))
         (sort-by (comp :sort-instant ::activity-time) #(compare %2 %1))
         (take limit))))

(defn timeline-activity
  "Build past and near-future timeline entries from already-fetched items."
  [ctx items {:keys [limit upcoming-limit], :or {limit 18 upcoming-limit 6}}]
  (let [recent   (recent-activity ctx items {:limit (* 2 limit)})
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

(defn- formatted-field-value
  [entity {:keys [field-key input-type]} ctx]
  (let [value (get entity field-key)]
    (case input-type
      :single-relationship
      (or (rel/relationship-label ctx value) "")

      :many-relationship
      (str/join ", " (keep #(rel/relationship-label ctx %) value))

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
  [:span.text-sm.font-semibold.text-white.leading-snug
   (or (:node field) fallback)])

(defn- priority-detail-class
  [priority]
  (cond
    (= priority 1) "text-xs text-gray-300"
    (= priority 2) "text-xs text-gray-400"
    (= priority 3) "text-xs text-gray-500"
    :else "text-xs text-gray-500"))

(defn- render-priority-detail
  [{:keys [field-key label node priority]}]
  [:span.inline-flex.items-baseline.gap-1.min-w-0
   {:key (str field-key)
    :class (priority-detail-class priority)}
   [:span.text-gray-600.whitespace-nowrap label]
   [:span.truncate node]])

(defn- entity-status
  [ctx entity]
  (let [etype   (some-> entity ::sm/type name)
        start   (get-in entity [::activity-time :instant])
        end     (get-in entity [::activity-time :end-instant])
        future? (and start (t/> start (t/now)))
        today   (t/date (t/in (t/now) (user-zone ctx)))]
    (cond
      (and start
           (nil? end)
           (#{"project-log" "reading-log" "meditation-log"} etype))
      :running

      future? :scheduled

      (and (= etype "task")
           (or (= :now (:task/state entity))
               (some->> (:task/due-on entity) (t/>= today))
               (some->> (:task/focus-date entity) (t/>= today))))
      :actionable

      :else :normal)))

(defn- status-style
  [status]
  (get status-styles status (:normal status-styles)))

(defn- timeline-node
  [{:keys [type status]}]
  (let [{:keys [icon-key]} (type-meta type)
        {:keys [ring shadow], icon-color :icon} (status-style status)]
    [:div.relative.z-10.flex.h-full.justify-center
     [:span.relative.mt-2.flex.h-8.w-8.items-center.justify-center.rounded-full.bg-dark
      {:style {:border     (str "1.5px solid " ring)
               :color      icon-color
               :box-shadow shadow}}
      (icon-svg icon-key 15)]]))

(defn- render-status-badge
  [status]
  (let [{:keys [badge badge-style]} (status-style status)]
    (when badge
      [:span.text-xs.font-semibold.uppercase.tracking-wide.rounded.px-2.py-0.5
       {:style badge-style}
       badge])))

(defn- type-counts
  [items]
  (frequencies (map #(some-> % ::sm/type name) items)))

(def ^:private timeline-filter-script
  "Client-side type filter: toggles row/group visibility and chip styling
   without re-requesting the fragment (each backend request re-runs the
   whole dashboard cascade)."
  "window.gleanmoFilterTimeline = function (btn) {
     var type = btn.getAttribute('data-filter-type') || '';
     var visible = 0;
     document.querySelectorAll('[data-etype]').forEach(function (el) {
       var show = !type || el.getAttribute('data-etype') === type;
       el.style.display = show ? '' : 'none';
       if (show) { visible++; }
     });
     document.querySelectorAll('[data-timeline-group]').forEach(function (g) {
       var any = false;
       g.querySelectorAll('[data-etype]').forEach(function (el) {
         if (el.style.display !== 'none') { any = true; }
       });
       g.style.display = any ? '' : 'none';
     });
     document.querySelectorAll('[data-filter-type]').forEach(function (chip) {
       var active = chip === btn;
       chip.classList.toggle('bg-dark-light', active);
       chip.classList.toggle('text-white', active);
       chip.classList.toggle('bg-dark-surface', !active);
       chip.classList.toggle('text-gray-500', !active);
     });
     var count = document.querySelector('[data-filter-count]');
     if (count) { count.textContent = visible + ' shown'; }
   };")

(defn- render-type-filters
  [items]
  (let [counts (type-counts items)
        total  (count items)
        ordered-types (->> timeline-type-order
                           (filter counts))
        extra-types   (->> (keys counts)
                           (remove (set ordered-types))
                           sort)]
    [:div.space-y-3
     [:div.flex.items-baseline.justify-between.gap-4
      [:div.flex.items-baseline.gap-3
       [:span.text-sm.font-semibold.uppercase.tracking-wide.text-white
        "Activity timeline"]
       [:span.text-xs.text-gray-500 {:data-filter-count true}
        (str total " shown")]]
      [:span.hidden.sm:inline.text-xs.text-gray-500 "filter by type"]]
     [:div.flex.flex-wrap.gap-2
      (for [etype (cons nil (concat ordered-types extra-types))
            :let [active? (nil? etype)
                  {:keys [code icon-key]} (if etype
                                            (type-meta etype)
                                            {:code "ALL" :icon-key nil})
                  count (if etype (get counts etype 0) total)]]
        [:button.inline-flex.items-center.gap-2.rounded-md.px-3.py-1.5.text-xs.font-semibold.uppercase.tracking-wide.transition-colors
         {:key              (or etype "all")
          :type             "button"
          :data-filter-type (or etype "")
          :onclick          "gleanmoFilterTimeline(this)"
          :class            (if active?
                              "bg-dark-light text-white"
                              "bg-dark-surface text-gray-500 hover:text-white hover:bg-dark-light")}
         (when icon-key
           [:span.text-gray-400 (icon-svg icon-key 13)])
         [:span code]
         [:span.text-gray-600 count]])]
     [:script (biff/unsafe timeline-filter-script)]]))

(defn- group-label
  [ctx date]
  (let [zone      (user-zone ctx)
        today     (t/date (t/in (t/now) zone))
        yesterday (t/<< today (t/new-period 1 :days))
        tomorrow  (t/>> today (t/new-period 1 :days))]
    (cond
      (t/> date tomorrow) "Upcoming"
      (= date today) "Today"
      (= date tomorrow) "Tomorrow"
      (= date yesterday) "Yesterday"
      :else (t/format (t/formatter "EEEE") date))))

(defn- group-date
  [date]
  (t/format (t/formatter "EEE MMM d") date))

(defn- timeline-groups
  "Group timeline items: all future items collapse into one leading
   'Upcoming' group; past/today items group per day as before."
  [ctx items]
  (let [today     (t/date (t/in (t/now) (user-zone ctx)))
        item-date (fn [entity]
                    (timeline-date ctx (get-in entity [::activity-time :instant])))
        future?   (fn [entity]
                    (some-> (item-date entity) (t/> today)))
        upcoming  (filter future? items)
        day-groups (->> items
                        (remove future?)
                        (reduce
                         (fn [groups entity]
                           (let [date (item-date entity)]
                             (if (= date (:date (peek groups)))
                               (conj (pop groups)
                                     (update (peek groups) :items conj entity))
                               (conj groups {:date date :items [entity]}))))
                         []))]
    (cond->> (seq day-groups)
      (seq upcoming) (cons {:upcoming? true :items (vec upcoming)}))))

(defn- render-group-header
  [ctx {:keys [date items upcoming?]}]
  (let [dates      (when upcoming?
                     (->> items
                          (keep #(timeline-date ctx (get-in % [::activity-time :instant])))
                          sort))
        date-text  (cond
                     (and upcoming? (seq dates))
                     (let [from (first dates)
                           to   (last dates)]
                       (if (= from to)
                         (group-date from)
                         (str (group-date from) " – " (group-date to))))

                     upcoming? nil
                     :else     (group-date date))
        scheduled? (or upcoming?
                       (t/> date (t/date (t/in (t/now) (user-zone ctx)))))]
    [:summary.sticky.top-0.z-20.flex.cursor-pointer.select-none.items-center.gap-3.bg-dark.py-3
     {:style {:list-style "none"}}
     [:span.flex.h-6.w-6.items-center.justify-center.rounded.bg-dark-surface.text-gray-500.transition-colors.hover:bg-dark-light.hover:text-white
      [:span.text-sm.transition-transform.group-open:rotate-90 "›"]]
     [:div.text-sm.font-semibold.text-gray-200
      (if upcoming? "Upcoming" (group-label ctx date))]
     (when date-text
       [:div.text-xs.text-gray-500 date-text])
     (when scheduled?
       [:span.rounded.px-2.py-0.5.text-xs.font-semibold.uppercase.tracking-wide
        {:style {:color "#a78bfa" :border "1px solid rgba(139,92,246,0.4)"}}
        "Scheduled"])
     [:div.h-px.flex-1.bg-dark-border]
     [:div.text-xs.text-gray-500
      (str (count items) " " (if (= 1 (count items)) "entry" "entries"))]]))

(defn- render-timeline-row
  [ctx entity & [{:keys [show-date?]}]]
  (let [etype           (name (::sm/type entity))
        {:keys [code]} (type-meta etype)
        href            (edit-form-url etype (:xt/id entity))
        priority-fields (priority-field-values entity ctx)
        title-field     (first priority-fields)
        detail-fields   (rest priority-fields)
        time-meta       (activity-time-meta entity ctx)
        id-short        (subs (str (:xt/id entity)) 0 8)
        start           (get-in entity [::activity-time :instant])
        status          (entity-status ctx entity)]
    [:a.group.flex.items-stretch.rounded-lg.no-underline.transition-colors.hover:bg-dark-surface
     {:href href
      :data-etype etype}
     [:div.w-20.shrink-0.py-3.pr-3.text-right
      (when show-date?
        [:div.text-xs.font-semibold.text-gray-200
         (or (some->> (timeline-date ctx start) (t/format (t/formatter "MMM d"))) "")])
      [:div.text-xs.text-gray-400.tabular-nums (or (timeline-time ctx start) "")]]
     [:div.w-14.shrink-0.relative
      [:div.absolute.top-0.bottom-0.left-0.right-0.mx-auto.w-px.bg-dark-border]
      (timeline-node {:type etype
                      :status status})]
     [:div.flex-1.min-w-0.py-3.pr-4
      [:div.flex.flex-wrap.items-center.gap-2
       [:span.rounded.border.border-dark-border.px-2.py-0.5.text-xs.font-semibold.uppercase.tracking-wide.text-gray-500 code]
       (render-title-field title-field (entity-title entity))
       (render-status-badge status)]
      (when (seq detail-fields)
        [:div.mt-2.flex.flex-wrap.gap-x-4.gap-y-1
         (for [field detail-fields]
           (render-priority-detail field))])
      (when-let [duration (:duration time-meta)]
        [:div.mt-2.text-xs.font-semibold.text-gray-400.md:hidden duration])]
     [:div.hidden.w-32.shrink-0.flex-col.items-end.gap-1.py-3.pr-4.text-right.md:flex
      (when-let [duration (:duration time-meta)]
        [:span.text-xs.font-semibold.text-gray-300 duration])
      (when-let [relative (:relative time-meta)]
        [:span.text-xs.text-gray-500 relative])
      [:span.text-xs.font-mono.text-gray-700 (str id-short "...")]]]))

(defn render-activity-feed
  "Render activity in a single chronological timeline across entity types.
   Type filtering happens client-side (see timeline-filter-script)."
  [ctx items]
  (let [groups (timeline-groups ctx items)]
    (if (seq items)
      [:div.space-y-5
       (render-type-filters items)
       [:div.space-y-2
        (for [group groups]
          [:details.group {:key (if (:upcoming? group) "upcoming" (str (:date group)))
                           :open true
                           :data-timeline-group true}
           (render-group-header ctx group)
           [:div
            (for [entity (:items group)]
              (render-timeline-row ctx entity
                                   {:show-date? (:upcoming? group)}))]])]]
      [:div.space-y-5
       (render-type-filters items)
       [:p.text-sm.text-gray-400 "No matching activity."]])))

(defn stats-section
  [ctx]
  [:div#overview-stats
   (stats-strip (dashboard-stats ctx))])

(defn upcoming-events-section
  [ctx]
  [:div#overview-events
   (render-upcoming-events ctx)])

(defn recent-activity-section
  "Fetch overview data once and render timers, timeline, and stats from it."
  [{:keys [params] :as ctx}]
  (let [limit (try
                (some-> (:limit params) Integer/parseInt)
                (catch Exception _ nil))
        ctx    (assoc ctx ::rel/rel-cache (atom {}))
        ;; Items and timers are independent reads — overlap them.
        items-future (future (doall (fetch-overview-items ctx)))
        timers (active-timer-summaries ctx)
        items  @items-future
        timeline-items (timeline-activity ctx items {:limit (or limit 18)})
        _      (rel/prewarm-relation-cache! ctx timeline-items)
        stats  (dashboard-stats ctx items timers)]
    [:div#overview-recent
     [:div.space-y-5
      (render-active-timers ctx timers)
      (render-activity-feed ctx timeline-items)
      [:div.opacity-70
       (stats-strip stats)]]]))

(def ^:private skeleton-type-cycle
  ["reading-log" "habit-log" "medication-log"
   "exercise-session" "meditation-log" "calendar-event"])

(defn- timeline-skeleton
  "Loading placeholder shaped like the real timeline: header shimmer, then
   rows cascading in down the node line with staggered pulse delays."
  []
  [:div
   [:div.flex.items-baseline.gap-3.pb-1
    [:span.text-sm.font-semibold.uppercase.tracking-wide.text-white
     "Activity timeline"]
    [:span.text-xs.text-gray-500 "loading…"]]
   [:div.flex.items-center.gap-3.py-3
    [:div.h-5.w-24.rounded.bg-dark-surface.animate-pulse]
    [:div.h-px.flex-1.bg-dark-border]]
   (for [[i etype] (map-indexed vector skeleton-type-cycle)
         :let [delay    {:animation-delay (str (* i 140) "ms")}
               icon-key (:icon-key (type-meta etype))
               {:keys [accent]} (accent-style etype)]]
     [:div.flex.items-stretch {:key i}
      [:div.w-20.shrink-0.py-3.pr-3.flex.justify-end
       [:div.h-3.w-10.rounded.bg-dark-light.animate-pulse {:style delay}]]
      [:div.w-14.shrink-0.relative
       [:div.absolute.top-0.bottom-0.left-0.right-0.mx-auto.w-px.bg-dark-border]
       [:div.relative.z-10.flex.justify-center
        [:span.relative.mt-2.flex.h-8.w-8.items-center.justify-center.rounded-full.bg-dark.animate-pulse
         {:style (merge delay {:border "1.5px solid #2a3140"
                               :color accent
                               :opacity 0.75})}
         (icon-svg icon-key 15)]]]
      [:div.flex-1.min-w-0.py-3.pr-4.space-y-2
       [:div.h-3.rounded.bg-dark-light.animate-pulse
        {:style delay :class "w-1/2"}]
       [:div.h-3.rounded.bg-dark-light.animate-pulse.opacity-60
        {:style delay :class "w-1/3"}]]])])

(defn overview-shell
  "Top-level layout for the home overview page; sections hydrate via HTMX."
  [_ctx]
  [:div.flex.flex-col.space-y-5
   [:div {:id "overview-recent"
          :hx-get "/app/overview/recent"
          :hx-trigger "load"
          :hx-swap "outerHTML"}
    (timeline-skeleton)]])

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
