(ns tech.jgood.gleanmo.app.task
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [xtdb.api :as xt]))

(def crud-routes
  (crud/gen-routes {:entity-key :task,
                    :entity-str "task",
                    :plural-str "tasks",
                    :schema     schema}))

(def ^:private task-states
  [:inbox :now :later :waiting :snoozed :done])

(def ^:private task-state-labels
  {:inbox   "Inbox",
   :now     "Now",
   :later   "Later",
   :waiting "Waiting",
   :snoozed "Snoozed",
   :done    "Done"})

(def ^:private task-domains
  [:work :personal :home :health :admin])

(def ^:private review-staleness-threshold-days 14)

(def ^:private review-snooze-threshold 3)

(defn- get-tasks-by-state
  [db user-id state]
  (->> (xt/q db
             '{:find  [(pull ?e [*])],
               :where [[?e :user/id user-id]
                       [?e ::sm/type :task]
                       [?e :task/state state]
                       (not [?e ::sm/deleted-at])],
               :in    [[user-id state]]}
             [user-id state])
       (map first)
       (sort-by ::sm/created-at)
       reverse))

(defn- get-now-tasks
  "Get actionable Now tasks - state=now and snooze expired or no snooze"
  [db user-id limit]
  (let [today (java.time.LocalDate/now)
        tasks (->> (xt/q db
                         '{:find  [(pull ?e [*])],
                           :where [[?e :user/id user-id]
                                   [?e ::sm/type :task]
                                   [?e :task/state :now]
                                   (not [?e ::sm/deleted-at])],
                           :in    [[user-id]]}
                         [user-id])
                   (map first)
                   ;; Filter: no snooze-until OR snooze-until <= today
                   (filter (fn [task]
                             (let [snooze (:task/snooze-until task)]
                               (or (nil? snooze)
                                   (<= (.compareTo snooze today) 0)))))
                   (sort-by ::sm/created-at)
                   reverse)]
    (cond->> tasks
      limit (take limit))))

(defn- get-projects
  [db user-id]
  (->> (xt/q db
             '{:find  [(pull ?e [*])],
               :where [[?e :user/id user-id]
                       [?e ::sm/type :project]
                       (not [?e ::sm/deleted-at])],
               :in    [[user-id]]}
             [user-id])
       (map first)
       (sort-by :project/label)))

(defn- staleness-days
  [task now]
  (when-let [last-change (or (:task/last-state-change-at task)
                             (::sm/created-at task))]
    (.between java.time.temporal.ChronoUnit/DAYS last-change now)))

(defn- overdue?
  [task today]
  (when-let [due-on (:task/due-on task)]
    (.isBefore due-on today)))

(defn- review-queue-task?
  [task now today]
  (let [staleness    (or (staleness-days task now) 0)
        snooze-count (or (:task/snooze-count task) 0)
        snooze-until (:task/snooze-until task)
        snooze-ready? (or (nil? snooze-until)
                          (not (.isAfter snooze-until today)))]
    (and (or (not= (:task/state task) :snoozed) snooze-ready?)
         (or (> staleness review-staleness-threshold-days)
             (>= snooze-count review-snooze-threshold)
             (overdue? task today)))))

(defn- get-review-queue-tasks
  [db user-id]
  (let [now   (java.time.Instant/now)
        today (java.time.LocalDate/now)
        tasks (mapcat #(get-tasks-by-state db user-id %)
                      [:now :later :waiting :snoozed])]
    (->> tasks
         (filter #(review-queue-task? % now today))
         (sort-by #(or (staleness-days % now) 0) >))))

(defn- normalize-param
  [value]
  (let [value (some-> value str/trim)]
    (when (seq value) value)))

(defn- parse-keyword-param
  [value]
  (some-> value normalize-param keyword))

(defn- parse-local-date-param
  [value]
  (some-> value normalize-param java.time.LocalDate/parse))

(defn- matches-search?
  [task term]
  (let [term  (str/lower-case term)
        label (some-> (:task/label task) str/lower-case)
        notes (some-> (:task/notes task) str/lower-case)]
    (or (and label (str/includes? label term))
        (and notes (str/includes? notes term)))))

(defn- due-on-or-before?
  [task due-date]
  (when-let [task-due (:task/due-on task)]
    (not (.isAfter task-due due-date))))

(defn- snoozed-filter-match?
  [task snoozed-filter]
  (case snoozed-filter
    "only" (= (:task/state task) :snoozed)
    "exclude" (not= (:task/state task) :snoozed)
    true))

(defn- preset-match?
  [task preset now today]
  (case preset
    "review" (review-queue-task? task now today)
    "stale"  (> (or (staleness-days task now) 0)
                review-staleness-threshold-days)
    "waiting" (= (:task/state task) :waiting)
    "now"    (= (:task/state task) :now)
    true))

(defn- focus-base-tasks
  [db user-id state-filter preset state-any?]
  (cond
    state-filter
    (if (= state-filter :now)
      (get-now-tasks db user-id nil)
      (get-tasks-by-state db user-id state-filter))

    (#{"review" "stale"} preset)
    (mapcat #(get-tasks-by-state db user-id %)
            [:now :later :waiting :snoozed])

    (= preset "waiting")
    (get-tasks-by-state db user-id :waiting)

    state-any?
    (mapcat #(get-tasks-by-state db user-id %) task-states)

    :else
    (get-now-tasks db user-id nil)))

(defn- sort-focus-tasks
  [tasks sort-key now]
  (case sort-key
    "due-asc"
    (sort-by (fn [task]
               [(nil? (:task/due-on task)) (:task/due-on task)])
             tasks)
    "stale-desc"
    (sort-by (fn [task] (or (staleness-days task now) 0)) > tasks)
    (sort-by ::sm/created-at > tasks)))

(defn- primary-button
  "Primary action button - visually prominent"
  [task-id target-state label]
  (biff/form
   {:action (str "/app/task/" task-id "/set-state"),
    :method "post",
    :class  "inline"}
   [:input {:type "hidden", :name "state", :value (name target-state)}]
   [:button.px-3.py-1.rounded.font-semibold.text-sm.transition-all
    {:type  "submit",
     :class "bg-neon-lime text-dark hover:bg-neon-lime/80"}
    label]))

(defn- secondary-button
  "Secondary action - less prominent"
  [task-id target-state label]
  (biff/form
   {:action (str "/app/task/" task-id "/set-state"),
    :method "post",
    :class  "inline"}
   [:input {:type "hidden", :name "state", :value (name target-state)}]
   [:button.px-2.py-1.rounded.text-xs.transition-all
    {:type "submit",
     :class
     "border border-dark-border text-gray-400 hover:text-white hover:border-gray-500"}
    label]))

(defn- snooze-button
  "Snooze button with days parameter"
  [task-id days label]
  (biff/form
   {:action (str "/app/task/" task-id "/snooze"),
    :method "post",
    :class  "inline"}
   [:input {:type "hidden", :name "days", :value (str days)}]
   [:button.px-2.py-1.rounded.text-xs.transition-all
    {:type  "submit",
     :class "text-gray-500 hover:text-neon-pink hover:bg-neon-pink/10"}
    label]))

(defn- task-row
  [task]
  (let [id    (:xt/id task)
        state (:task/state task)]
    [:div.flex.items-center.justify-between.p-3.bg-dark-surface.rounded.mb-2.border.border-dark
     [:div.flex-1.min-w-0
      [:a.font-medium.text-white.hover:underline
       {:href (str "/app/crud/form/task/edit/" id)}
       (:task/label task)]
      (when-let [notes (:task/notes task)]
        [:p.text-sm.text-gray-400.truncate {:style {:max-width "300px"}}
         notes])]
     [:div.flex.items-center.gap-2.ml-4.flex-shrink-0
      ;; Primary action: move to Now
      (when (not= state :now)
        (primary-button id :now "Now"))
      ;; Secondary actions
      [:div.flex.items-center.gap-1
       (when (not= state :later)
         (secondary-button id :later "later"))
       (when (not= state :waiting)
         (secondary-button id :waiting "waiting"))
       (when (not= state :done)
         (secondary-button id :done "done"))]
      ;; Snooze options - subtle
      (when (not= state :snoozed)
        [:div.flex.items-center.gap-0.border-l.border-dark-border.pl-2.ml-1
         (snooze-button id 1 "+1d")
         (snooze-button id 3 "+3d")
         (snooze-button id 7 "+1w")])]]))

(defn set-state!
  [{:keys [biff/db session path-params params], :as ctx}]
  (let [task-id      (parse-uuid (:id path-params))
        new-state    (keyword (:state params))
        now          (java.time.Instant/now)
        task         (xt/entity db task-id)
        snooze-count (or (:task/snooze-count task) 0)
        state-change-count (or (:task/state-change-count task) 0)
        tx-doc       (cond-> {:db/op             :update,
                              :db/doc-type       :task,
                              :xt/id             task-id,
                              :task/state        new-state,
                              :task/last-state-change-at now,
                              :task/state-change-count (inc state-change-count),
                              :task/snooze-count (if (= new-state :snoozed)
                                                   (inc snooze-count)
                                                   snooze-count)}
                       (= new-state :done) (assoc :task/done-at now))]
    (biff/submit-tx ctx [tx-doc])
    {:status  303,
     :headers {"location" "/app/task/focus"}}))

(defn snooze!
  [{:keys [biff/db path-params params], :as ctx}]
  (let [task-id      (parse-uuid (:id path-params))
        days         (Integer/parseInt (:days params))
        now          (java.time.Instant/now)
        snooze-until (.plusDays (java.time.LocalDate/now) days)
        task         (xt/entity db task-id)
        snooze-count (or (:task/snooze-count task) 0)
        state-change-count (or (:task/state-change-count task) 0)]
    (biff/submit-tx ctx
                    [{:db/op :update,
                      :db/doc-type :task,
                      :xt/id task-id,
                      :task/state :snoozed,
                      :task/snooze-until snooze-until,
                      :task/last-state-change-at now,
                      :task/state-change-count (inc state-change-count),
                      :task/snooze-count (inc snooze-count)}])
    {:status  303,
     :headers {"location" "/app/task/focus"}}))

(defn focus-view
  "Actionable task list with filters and presets."
  [{:keys [biff/db session params], :as ctx}]
  (let [user-id         (:uid session)
        preset          (normalize-param (:preset params))
        search          (normalize-param (:search params))
        state-param     (normalize-param (:state params))
        domain-param    (normalize-param (:domain params))
        project-param   (normalize-param (:project params))
        due-param       (normalize-param (:due-on params))
        snoozed-filter  (or (normalize-param (:snoozed params)) "any")
        sort-key        (or (normalize-param (:sort params)) "created-desc")
        state-any?      (= state-param "any")
        state-filter    (cond
                          (= state-param "any") nil
                          state-param (keyword state-param)
                          (#{"review" "stale"} preset) nil
                          (= preset "waiting") :waiting
                          (= preset "now") :now
                          :else :now)
        domain-filter   (parse-keyword-param domain-param)
        due-filter      (parse-local-date-param due-param)
        now             (java.time.Instant/now)
        today           (java.time.LocalDate/now)
        projects        (get-projects db user-id)
        base-tasks      (focus-base-tasks db user-id state-filter preset state-any?)
        filtered-tasks  (->> base-tasks
                             (filter #(or (nil? state-filter)
                                          (= (:task/state %) state-filter)))
                             (filter #(or (nil? domain-filter)
                                          (= (:task/domain %) domain-filter)))
                             (filter #(or (nil? project-param)
                                          (= project-param
                                             (some-> (:task/project-id %) str))))
                             (filter #(or (nil? search) (matches-search? % search)))
                             (filter #(or (nil? due-filter)
                                          (due-on-or-before? % due-filter)))
                             (filter #(snoozed-filter-match? % snoozed-filter))
                             (filter #(preset-match? % preset now today)))
        tasks           (sort-focus-tasks filtered-tasks sort-key now)
        task-count      (count tasks)
        preset-selected (or preset "now")
        state-selected  (or state-param
                            (when (nil? state-filter) "any")
                            (name state-filter))
        domain-selected (or domain-param "")
        project-selected (or project-param "")
        due-selected    due-param
        sort-selected   sort-key]
    (ui/page
     ctx
     (side-bar
      ctx
      [:div.max-w-5xl.mx-auto.p-4
       [:div.mb-6
        [:h1.text-2xl.font-bold "Task Focus"]]

       [:div.mb-4.flex.flex-wrap.gap-2
        (for [{:keys [label value]}
              [{:label "Now" :value "now"}
               {:label "Review" :value "review"}
               {:label "Waiting" :value "waiting"}
               {:label "Stale" :value "stale"}]]
          ^{:key value}
          [:a.rounded-full.border.px-3.py-1.text-xs.font-semibold.transition-all
           {:href (str "/app/task/focus?preset=" value)
            :class (if (= preset-selected value)
                     "bg-neon-lime text-dark border-neon-lime"
                     "border-dark-border text-gray-400 hover:text-white")}
           label])]

       [:div.mb-8.bg-dark-surface.border.border-dark.rounded.p-4
        (biff/form
         {:action "/app/task/focus"
          :method "get"
          :class  "space-y-4"}
         (when preset
           [:input {:type "hidden" :name "preset" :value preset}])
         [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
          [:div
           [:label.form-label {:for "task-search"} "Search"]
           [:div.mt-2
            [:input.form-input
             {:type "search"
              :id "task-search"
              :name "search"
              :value search
              :placeholder "Search label or notes..."
              :autocomplete "off"}]]]

          [:div
           [:label.form-label {:for "task-project"} "Project"]
           [:div.mt-2
            [:select.form-select
             {:id "task-project" :name "project"}
             [:option {:value "" :selected (str/blank? project-selected)}
              "All projects"]
             (for [project projects
                   :let [project-id (str (:xt/id project))]]
               ^{:key project-id}
               [:option {:value project-id
                         :selected (= project-selected project-id)}
                (:project/label project)])]]]

          [:div
           [:label.form-label {:for "task-state"} "State"]
           [:div.mt-2
            [:select.form-select
             {:id "task-state" :name "state"}
             [:option {:value "any" :selected (= state-selected "any")}
              "Any state"]
             (for [state task-states
                   :let [state-value (name state)]]
               ^{:key state-value}
               [:option {:value state-value
                         :selected (= state-selected state-value)}
                (task-state-labels state)])]]]

          [:div
           [:label.form-label {:for "task-domain"} "Domain"]
           [:div.mt-2
            [:select.form-select
             {:id "task-domain" :name "domain"}
             [:option {:value "" :selected (str/blank? domain-selected)}
              "All domains"]
             (for [domain task-domains
                   :let [domain-value (name domain)]]
               ^{:key domain-value}
               [:option {:value domain-value
                         :selected (= domain-selected domain-value)}
                (str/capitalize domain-value)])]]]

          [:div
           [:label.form-label {:for "task-due-on"} "Due by"]
           [:div.mt-2
            [:input.form-input
             {:type "date"
              :id "task-due-on"
              :name "due-on"
              :value due-selected
              :autocomplete "off"}]]]

          [:div
           [:label.form-label {:for "task-snoozed"} "Snoozed"]
           [:div.mt-2
            [:select.form-select
             {:id "task-snoozed" :name "snoozed"}
             [:option {:value "any" :selected (= snoozed-filter "any")} "Any"]
             [:option {:value "only" :selected (= snoozed-filter "only")}
              "Only snoozed"]
             [:option {:value "exclude" :selected (= snoozed-filter "exclude")}
              "Exclude snoozed"]]]]

          [:div
           [:label.form-label {:for "task-sort"} "Sort"]
           [:div.mt-2
            [:select.form-select
             {:id "task-sort" :name "sort"}
             [:option {:value "created-desc"
                       :selected (= sort-selected "created-desc")}
              "Created (newest)"]
             [:option {:value "due-asc"
                       :selected (= sort-selected "due-asc")}
              "Due date (soonest)"]
             [:option {:value "stale-desc"
                       :selected (= sort-selected "stale-desc")}
              "Staleness (oldest)"]]]]]

         [:div.flex.items-center.gap-3
          [:button.form-button-primary {:type "submit"} "Apply"]
          [:a.text-sm.text-gray-400.hover:text-white {:href "/app/task/focus"}
           "Clear"]])]

       [:div.flex.items-center.justify-between.mb-3
        [:h2.text-lg.font-semibold.text-neon-cyan "Tasks"]
        [:span.text-sm.text-gray-500 (str task-count " tasks")]]
       (if (seq tasks)
         (for [task tasks]
           ^{:key (str (:xt/id task))}
           (task-row task))
         [:p.text-gray-400 "No tasks match these filters."])

       [:div.mt-8.pt-4.border-t.border-dark
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/crud/task"}
         "View all tasks →"]]]))))

(def routes
  ["/task" {}
   ["" {:get focus-view}]
   ["/focus" {:get focus-view}]
   ["/:id/set-state" {:post set-state!}]
   ["/:id/snooze" {:post snooze!}]])
