(ns tech.jgood.gleanmo.app.timers
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.timer.routes :as timer-routes]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(def timer-entities
  "List of entities that support timer functionality"
  [{:entity-key :project-log
    :entity-str "project-log"
    :display-name "Projects"
    :description "Track time spent working on projects"
    :icon "📋"
    :route "/app/timer/project-log"}
   {:entity-key :meditation-log
    :entity-str "meditation-log"
    :display-name "Meditation"
    :description "Log focused meditation sessions"
    :icon "🧘"
    :route "/app/timer/meditation-log"}
   {:entity-key :reading-log
    :entity-str "reading-log"
    :display-name "Reading"
    :description "Track time spent reading books"
    :icon "📖"
    :route "/app/timer/reading-log"}])

(def timer-entity-configs
  "Timer entities paired with their derived timer configs. Deref'd lazily so
   config derivation runs after all schemas are loaded."
  (delay
    (mapv (fn [{:keys [entity-key entity-str] :as entity-meta}]
            (assoc entity-meta
                   :config (timer-routes/timer-config {:entity-key entity-key
                                                       :entity-str entity-str})))
          timer-entities)))

(defn- parent-label-key
  [config]
  (schema-utils/entity-attr-key (:parent-entity-key config) "label"))

(defn- fetch-section
  "Fetch the per-type data the workspace needs. The active-timers poll
   fragment (include-recent unset) skips recent logs and only fetches parents
   for types that have a running timer to label."
  [ctx {:keys [config] :as entity-meta} & {:keys [include-recent]}]
  (let [active  (vec (timer-routes/fetch-active-timers ctx config))
        parents (when (or include-recent (seq active))
                  (vec (queries/all-for-user-query (:parent-query config) ctx)))]
    (assoc entity-meta
           :parents parents
           :active  active
           :recent  (when include-recent
                      (vec (timer-routes/fetch-completed-logs ctx config 5))))))

(defn- combined-active-section
  "All running timers across types, wrapped for the 30s HTMX poll. Also
   refreshes on the refresh-active-timers event the location chips trigger."
  [ctx sections locations]
  (let [cards (for [{:keys [config parents active]} sections
                    timer active]
                ^{:key (:xt/id timer)}
                (timer-routes/active-timer-card timer parents ctx config
                                                :redirect-target "/app/timers"
                                                :locations locations))]
    [:div
     {:id "active-timers-section"
      :hx-get "/app/timers/active"
      :hx-trigger "every 30s, refresh-active-timers from:body"
      :hx-swap "outerHTML"}
     (if (seq cards)
       [:div.space-y-4 cards]
       [:p.text-sm.text-gray-400 "Nothing running."])]))

(defn- latest-instant-by-parent
  "Map of parent id to the most recent log beginning across active and recent
   logs of every type — the recency signal for start-row ordering."
  [sections]
  (reduce
   (fn [acc {:keys [config active recent]}]
     (let [rel-key (:relationship-key config)
           beg-key (:beginning-key config)]
       (reduce (fn [acc log]
                 (let [parent-id (get log rel-key)
                       beginning (get log beg-key)]
                   (if (and parent-id
                            beginning
                            (or (nil? (get acc parent-id))
                                (t/> beginning (get acc parent-id))))
                     (assoc acc parent-id beginning)
                     acc)))
               acc
               (concat active recent))))
   {}
   sections))

(defn- start-row
  "One filterable row in the search-to-start list. The Start button submits
   the surrounding form to its own entity's endpoint via formaction, carrying
   the parent id as the button value — so the shared location chips ride along
   with every start."
  [{:keys [parent icon config label]}]
  [:div.flex.items-center.justify-between.gap-3.bg-dark-surface.rounded.p-3.border.border-dark.transition-all.duration-300.hover:border-neon-yellow
   {:data-filter-text label}
   [:div.flex.items-center.gap-3.min-w-0
    [:span.text-lg icon]
    [:span.text-sm.text-white.truncate label]]
   [:button.bg-neon-yellow.bg-opacity-20.text-neon-yellow.px-3.py-2.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all.shrink-0
    {:type "submit"
     :formaction (str "/app/timers/start/" (:entity-str config))
     :name "parent-id"
     :value (str (:xt/id parent))}
    "Start"]])

(defn- location-usage
  "Latest log beginning per location across all sections' active and recent
   logs, plus the overall most recent location — the default start selection."
  [sections]
  (reduce
   (fn [acc {:keys [config active recent]}]
     (let [loc-key (schema-utils/entity-field-key (:entity-str config)
                                                  "location-id")
           beg-key (:beginning-key config)]
       (reduce
        (fn [acc log]
          (let [location  (get log loc-key)
                beginning (get log beg-key)]
            (if-not (and location beginning)
              acc
              (cond-> (update-in acc
                                 [:latest-by-location location]
                                 (fn [prev]
                                   (if (or (nil? prev) (t/> beginning prev))
                                     beginning
                                     prev)))
                (or (nil? (:latest-at acc)) (t/> beginning (:latest-at acc)))
                (assoc :latest-location location :latest-at beginning)))))
        acc
        (concat active recent))))
   {:latest-by-location {}}
   sections))

(defn- current-location-picker
  "Global current-location control: a Choices select that persists on change
   (offering to relocate running timers) and rides along with every start via
   the form attribute. Options are ordered by recency of use (Choices keeps
   DOM order); the persisted setting is preselected."
  [locations {:keys [latest-by-location]} current-location]
  (when (seq locations)
    (let [ordered (sort-by (fn [{id :xt/id, label :location/label}]
                             (let [label* (str/lower-case (or label ""))]
                               (if-let [used-at (get latest-by-location id)]
                                 [0 (- (t/long used-at)) label*]
                                 [1 0 label*])))
                           locations)]
      [:div.space-y-2
       [:div.flex.items-center.gap-2
        [:span.text-lg {:aria-hidden "true"} "📍"]
        [:div.flex-1.min-w-0
         [:select#start-location-select.form-select.w-full
          {:name "location-id"
           :form "start-timer-form"
           :aria-label "Current location"
           :data-enhance "choices"
           :hx-post "/app/timers/current-location"
           :hx-trigger "change"
           :hx-target "#relocate-prompt"
           :hx-swap "innerHTML"}
          (timer-routes/location-options ordered current-location
                                         :include-empty true)]]]
       [:div#relocate-prompt]])))

(defn- relocate-prompt
  "Confirmation offered when the current location changes while timers run:
   end them all now and start continuations at the new location."
  [location active-count]
  [:div.bg-dark-surface.border.border-neon-yellow.rounded-lg.p-3.flex.flex-wrap.items-center.justify-between.gap-3
   [:p.text-sm.text-gray-300
    (str "Restart " active-count " running timer"
         (when (< 1 active-count) "s")
         " at " (or (:location/label location) "Unnamed") "?")]
   [:div.flex.items-center.gap-2
    [:button.bg-neon-yellow.bg-opacity-20.text-neon-yellow.px-3.py-1.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all
     {:type "button"
      :hx-post "/app/timers/relocate"
      :hx-vals (cheshire/generate-string {:location-id (str (:xt/id location))})
      :hx-target "#relocate-prompt"
      :hx-swap "innerHTML"}
     "Restart here"]
    [:button.text-sm.text-gray-400.hover:text-white.transition-all
     {:type "button"
      :onclick "document.getElementById('relocate-prompt').innerHTML = '';"}
     "Dismiss"]]])

(defn- create-parent-links
  "Buttons to create parents via the CRUD new-form, returning here after."
  [sections]
  (let [redirect (java.net.URLEncoder/encode "/app/timers" "UTF-8")]
    [:div.flex.flex-wrap.gap-3
     (for [{:keys [entity-key icon config]} sections]
       (let [parent-str (:parent-entity-str config)]
         ^{:key entity-key}
         [:a.bg-neon-yellow.bg-opacity-20.text-neon-yellow.px-3.py-2.rounded.text-sm.font-medium.hover:bg-opacity-30.transition-all.no-underline
          {:href (str "/app/crud/form/" parent-str "/new?redirect=" redirect)}
          (str icon " New " parent-str)]))]))

(defn- search-to-start-section
  "One text input filtering a single list of all parent entities across types.
   Empty-filter order: recently-timed first, remainder alphabetical. The
   current-location picker sits above the filter, associated with the start
   form via the form attribute, so every Start posts the visible location;
   the filter input belongs to no form to avoid implicit submission. Types
   with no parents yet get create links so the page is never a dead end."
  [sections locations current-location]
  (let [recency (latest-instant-by-parent sections)
        rows    (->> (for [{:keys [config icon] :as section} sections
                           parent (:parents section)]
                       {:parent    parent
                        :icon      icon
                        :config    config
                        :label     (or (get parent (parent-label-key config))
                                       "Unnamed")
                        :recent-at (get recency (:xt/id parent))})
                     (sort-by (fn [{:keys [recent-at label]}]
                                (if recent-at
                                  [0 (- (t/long recent-at)) (str/lower-case label)]
                                  [1 0 (str/lower-case label)]))))
        missing (filter #(empty? (:parents %)) sections)]
    [:div.space-y-3
     (when (seq rows)
       (current-location-picker locations (location-usage sections) current-location))
     (when (seq rows)
       [:input.form-input.w-full
        {:type "search"
         :placeholder "Type to filter…"
         :aria-label "Filter timers"
         :data-filter-list "#start-timer-list"}])
     (when (seq rows)
       (biff/form
        {:id     "start-timer-form"
         :action "/app/timers"
         :class  "space-y-3"}
        [:input {:type "hidden" :name "redirect" :value "/app/timers"}]
        [:div#start-timer-list.space-y-2
         (for [{:keys [parent config] :as row} rows]
           ^{:key (str (:entity-str config) "-" (:xt/id parent))}
           (start-row row))]))
     (when (seq missing)
       [:div.space-y-2
        [:p.text-gray-400
         (if (seq rows)
           "Nothing to time in some categories yet:"
           "Nothing to time yet — create one first:")]
        (create-parent-links missing)])]))

(defn- combined-recent-logs
  "Last ~5 completed logs across types as edit links returning here."
  [ctx sections]
  (let [tz        (t/zone (or (get-user-time-zone ctx) "UTC"))
        formatter (java.time.format.DateTimeFormatter/ofPattern "MMM d, h:mm a")
        redirect  (java.net.URLEncoder/encode "/app/timers" "UTF-8")
        rows      (->> sections
                       (mapcat
                        (fn [{:keys [config icon parents recent]}]
                          (let [label-key (parent-label-key config)
                                labels    (into {}
                                                (map (juxt :xt/id #(get % label-key)))
                                                parents)]
                            (for [log recent]
                              {:log         log
                               :icon        icon
                               :config      config
                               :parent-name (or (get labels
                                                     (get log (:relationship-key config)))
                                                "Unknown")}))))
                       (sort-by #(t/long (get-in % [:log (:beginning-key (:config %))])) >)
                       (take 5))]
    (if (seq rows)
      [:div.space-y-2
       (for [{:keys [log icon config parent-name]} rows]
         (let [{:keys [entity-str beginning-key end-key]} config
               duration    (timer-routes/log-duration-seconds log beginning-key end-key)
               start-local (t/in (get log beginning-key) tz)
               edit-url    (str "/app/crud/form/" entity-str "/edit/" (:xt/id log)
                                "?redirect=" redirect)]
           ^{:key (:xt/id log)}
           [:a.block.no-underline {:href edit-url}
            [:div.bg-dark-surface.rounded.p-3.border.border-dark.flex.items-center.justify-between.transition-all.duration-300.hover:border-neon-cyan
             [:div.flex.items-center.gap-2.min-w-0
              [:span icon]
              [:div.min-w-0
               [:span.text-sm.text-white parent-name]
               [:span.text-xs.text-gray-500.ml-2
                (str (t/format formatter start-local))]]]
             [:span.text-sm.text-neon-cyan
              (when duration (timer-routes/format-duration duration))]]]))]
      [:p.text-sm.text-gray-400 "No completed logs yet."])))

(defn- per-type-links
  "Footer links to the per-entity pages, which keep today-stats and the
   longer recent-log lists."
  [sections]
  [:div.flex.flex-wrap.gap-4.pt-2.border-t.border-dark
   (for [{:keys [entity-key route icon display-name]} sections]
     ^{:key entity-key}
     [:a.link {:href route} (str icon " " display-name " stats →")])])

(defn timer-workspace
  "Unified timer workspace: every running timer across types, search-to-start
   for any parent entity, and combined recent logs."
  [ctx]
  (let [sections         (mapv #(fetch-section ctx % :include-recent true)
                               @timer-entity-configs)
        locations        (timer-routes/fetch-locations ctx)
        current-location (timer-routes/current-location-id ctx)]
    (ui/page
     ctx
     (layout/page-shell
      ctx {:width :normal}
      (layout/page-header {:title "⏱️ Timers"})

      [:div.space-y-3
       (layout/section-header "ACTIVE TIMERS")
       (combined-active-section ctx sections locations)]

      [:div.space-y-3
       (layout/section-header "START TIMER")
       (search-to-start-section sections locations current-location)]

      [:div.space-y-3
       (layout/section-header "RECENT LOGS")
       (combined-recent-logs ctx sections)]

      (per-type-links sections)))))

(defn active-timers-fragment
  "HTMX fragment refreshing all running timers across types."
  [ctx]
  (let [sections  (mapv #(fetch-section ctx %) @timer-entity-configs)
        locations (when (some (comp seq :active) sections)
                    (timer-routes/fetch-locations ctx))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (ui/fragment (combined-active-section ctx sections locations))}))

(defn- with-timer-config
  "Validate the entity segment against the registered timer entities, then
   call handler with its derived config."
  [ctx handler]
  (let [entity-str  (get-in ctx [:path-params :entity-str])
        entity-meta (first (filter #(= entity-str (:entity-str %))
                                   @timer-entity-configs))]
    (if entity-meta
      (handler ctx (:config entity-meta))
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Unknown timer entity"})))

(defn start-timer!
  "Direct-start endpoint for the workspace and per-entity start buttons."
  [ctx]
  (with-timer-config ctx timer-routes/start-timer))

(defn set-location!
  "Set or clear a log's location from an active-card select."
  [ctx]
  (with-timer-config ctx timer-routes/set-timer-location))

(defn- count-active-timers
  [ctx]
  (transduce (map #(count (timer-routes/fetch-active-timers ctx (:config %))))
             +
             @timer-entity-configs))

(defn set-current-location!
  "Persist the user's current location. When a real location was chosen and
   timers are running, respond with the relocate confirmation; otherwise
   clear the prompt area."
  [ctx]
  (let [user-id     (-> ctx :session :uid)
        location-id (timer-routes/uuid-param ctx "location-id")
        location    (when location-id
                      (queries/get-entity-for-user (:biff/db ctx)
                                                   location-id
                                                   user-id
                                                   :location))]
    (mutations/update-user!
     ctx
     user-id
     {:user/current-location-id (or (:xt/id location) :db/dissoc)})
    (let [active-count (when location (count-active-timers ctx))]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (if (and location (pos? active-count))
               (ui/fragment (relocate-prompt location active-count))
               "")})))

(defn relocate!
  "End every running timer now and start continuations at the new location.
   Clears the prompt and tells the active sections to refresh."
  [ctx]
  (when-let [location-id (timer-routes/uuid-param ctx "location-id")]
    (doseq [{:keys [config]} @timer-entity-configs
            timer (timer-routes/fetch-active-timers ctx config)]
      (timer-routes/relocate-timer! ctx config timer location-id)))
  {:status 200
   :headers {"Content-Type" "text/html"
             "HX-Trigger" "refresh-active-timers"}
   :body ""})

(def routes
  ["/timers" {}
   ["" {:get timer-workspace}]
   ["/active" {:get active-timers-fragment}]
   ["/start/:entity-str" {:post start-timer!}]
   ["/location/:entity-str" {:post set-location!}]
   ["/current-location" {:post set-current-location!}]
   ["/relocate" {:post relocate!}]])
