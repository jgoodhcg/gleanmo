;; TODO rename this to not collide with other dashboards
(ns tech.jgood.gleanmo.app.dashboard-view
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [tech.jgood.gleanmo.crud.views :as crud-views]
   [tech.jgood.gleanmo.crud.views.formatting :as fmt]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.timer.routes :as timer-routes]
   [tech.jgood.gleanmo.app.timers :as timers-app]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

(def recent-activity-types
  ["habit-log"
   "meditation-log"
   "bm-log"
   "medication-log"
   "project-log"])

(def recent-activity-order-keys
  {"habit-log"      :habit-log/timestamp,
   "meditation-log" :meditation-log/beginning,
   "bm-log"         :bm-log/timestamp,
   "medication-log" :medication-log/timestamp,
   "project-log"    :project-log/beginning})

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
  {"habit-log"      {:accent "#06b6d4", :muted "rgba(6,182,212,0.16)"},
   "meditation-log" {:accent "#0ea5e9", :muted "rgba(14,165,233,0.16)"},
   "bm-log"         {:accent "#f59e0b", :muted "rgba(245,158,11,0.16)"},
   "medication-log" {:accent "#22c55e", :muted "rgba(34,197,94,0.16)"},
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

(defn- relative-time
  [ctx inst]
  (when inst
    (let [zone    (user-zone ctx)
          now     (t/in (t/now) zone)
          ts      (t/in inst zone)
          future? (t/> ts now)
          dur     (if future?
                    (t/between now ts)
                    (t/between ts now))
          secs    (t/seconds dur)
          suffix  (when-not future? " ago")]
      (cond
        (< secs 60)     (if future? "in under a minute" (str "just now"))
        (< secs 3600)   (let [m (t/minutes dur)]
                          (str (when future? "in ")
                               m
                               " min"
                               (when (not= m 1) "s")
                               suffix))
        (< secs 86400)  (let [h (t/hours dur)]
                          (str (when future? "in ")
                               h
                               " hr"
                               (when (not= h 1) "s")
                               suffix))
        (< secs 604800) (let [d (t/days dur)]
                          (str (when future? "in ")
                               d
                               " day"
                               (when (not= d 1) "s")
                               suffix))
        :else           (let [w (int (/ (t/days dur) 7))]
                          (str (when future? "in ")
                               w
                               " week"
                               (when (not= w 1) "s")
                               suffix))))))

(defn- activity-time
  "Pick the most meaningful instant for an entity so recent activity can be ordered consistently."
  [entity]
  (let [etype      (some-> entity
                           ::sm/type
                           name)
        timestamp  (when etype (get entity (keyword etype "timestamp")))
        beginning  (when etype (get entity (keyword etype "beginning")))
        created-at (::sm/created-at entity)
        instant    (or timestamp beginning created-at)
        source     (cond
                     timestamp  :timestamp
                     beginning  :beginning
                     created-at :created-at
                     :else      :unknown)]
    {:instant      instant,
     :sort-instant (or instant created-at (t/epoch)),
     :source       source}))

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

(defn- primary-field-value
  "Find the highest-priority display field with a value and return a display map."
  [entity {:keys [biff/db], :as ctx}]
  (when-let [etype (some-> entity
                           ::sm/type
                           name)]
    (when-let [entity-schema (get schema-registry/schema (keyword etype))]
      (let [display-fields (crud-views/get-display-fields entity-schema)
            prioritized    (sort-by #(or (:crud/priority (:opts %)) 99)
                                    display-fields)]
        (when-let [{:keys [input-type], :as field}
                     (some (fn [f]
                             (let [v (get entity (:field-key f))]
                               (when (present-value? v)
                                 (assoc f :value v))))
                           prioritized)]
          (case input-type
            :single-relationship
              {:kind   :relationship,
               :labels (keep #(relationship-label db %) [(:value field)])}

            :many-relationship
              {:kind   :relationship,
               :labels (keep #(relationship-label db %) (:value field))}

            {:kind :formatted,
             :node (fmt/format-cell-value input-type (:value field) ctx)}))))))

(defn dashboard-stats
  "Compute lightweight dashboard stats using a bounded set of entities."
  [ctx]
  (let [user-id       (-> ctx :session :uid)
        zone          (user-zone ctx)
        items         (->> (db/dashboard-recent-entities
                            (:biff/db ctx)
                            user-id
                            {:entity-types    recent-activity-types
                             :per-type-limit  200
                             :order-keys      recent-activity-order-keys})
                           (map #(assoc % ::activity-time (activity-time %))))
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
        distinct-types (count (distinct (map ::sm/type items)))]
    (log/info "Dashboard stats"
              {:entries-today entries-today
               :entries-week  entries-week
               :active-timers active-timers
               :distinct-types distinct-types
               :sample-count (count items)
               :week-start week-start})
    {"Entries today"    entries-today
     "This week"        entries-week
     "Active timers"    active-timers
     "Entities tracked" distinct-types}))

(defn upcoming-events
  "Fetch near-future calendar events."
  [ctx {:keys [limit], :or {limit 5}}]
  (let [user-id (-> ctx :session :uid)]
    (db/dashboard-upcoming-events
     (:biff/db ctx)
     user-id
     {:limit limit})))

(defn recent-activity
  "Fetch recent entities across primary log types."
  [ctx {:keys [limit], :or {limit 10}}]
  (let [user-id (-> ctx :session :uid)]
    (->> (db/dashboard-recent-entities
          (:biff/db ctx)
          user-id
          {:entity-types   recent-activity-types
           :per-type-limit (* 2 limit)
           :order-keys     recent-activity-order-keys})
         (map #(assoc % ::activity-time (activity-time %)))
         (sort-by (comp :sort-instant ::activity-time) #(compare %2 %1))
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
                    {:keys [accent muted]} (event-accent (:calendar-event/color-neon e))
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

(defn render-recent-activity
  "Render recent activity in a single chronological stream across entity types."
  [ctx recent-items]
  (let [items recent-items
        stats (dashboard-stats ctx)]
    [:div.space-y-4
     (stats-strip stats)
     (render-upcoming-events ctx)
     (if (seq items)
       [:div.relative
        [:div {:class "absolute left-[14px] top-1 bottom-1 w-px bg-dark-border pointer-events-none"}]
        [:div.space-y-3
         (for [entity items]
                 (let [etype         (name (::sm/type entity))
                       {:keys [instant source]} (::activity-time entity)
                       {:keys [accent muted]} (accent-style etype)
                       activity-inst (or instant (::sm/created-at entity))
                       relative      (relative-time ctx activity-inst)
                       href          (str "/app/crud/form/" etype "/edit/" (:xt/id entity))
                       primary       (or (primary-field-value entity ctx)
                                         {:kind :fallback
                                          :node (entity-title entity)})
                 id-short      (subs (str (:xt/id entity)) 0 8)]
             [:a.group.relative.block.pl-12.pr-4.py-4.rounded-xl.border.transition-all.duration-200
              {:key   (str (:xt/id entity))
               :href  href
               :style {:background   (str "linear-gradient(90deg," muted ", rgba(13,17,23,0.85))")
                       :border-color "rgba(48,54,61,0.9)"
                       :box-shadow   "0 10px 30px rgba(0,0,0,0.35)"}}
              [:span {:class "absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 rounded-full"
                      :style {:background accent
                              :box-shadow (str "0 0 0 6px rgba(13,17,23,1), 0 0 0 10px " muted)}}]

              [:div.space-y-3.min-w-0
               [:div.flex.items-center.flex-wrap.gap-2.text-xs.text-gray-400
                [:span.inline-flex.items-center.rounded-full.border.px-3.py-1.font-semibold
                 {:style {:color accent
                          :border-color accent
                          :background muted}}
                 (readable-label etype)]
                (when relative
                  [:span.text-xs.uppercase.tracking-wide.text-gray-500 relative])]

               (case (:kind primary)
                 :relationship
                 [:div.flex.flex-wrap.items-center.gap-2
                  (for [label (:labels primary)]
                    [:span.inline-flex.items-center.rounded-full.border.border-dark.bg-dark-light.px-3.py-1.text-sm.font-semibold.text-white
                     {:key (str label)}
                     label])]

                 :formatted
                 [:div.text-lg.font-semibold.text-white.truncate
                  (:node primary)]

                 [:div.text-lg.font-semibold.text-white.truncate
                  (:node primary)])

               [:div.flex.items-center.justify-between.text-xs.text-gray-500
                [:span.font-mono.opacity-70 (str id-short "...")]]]]))]]
       [:p.text-sm.text-gray-400 "No recent activity yet. Keep logging!"])]))
