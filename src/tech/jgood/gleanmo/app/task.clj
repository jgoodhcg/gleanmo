(ns tech.jgood.gleanmo.app.task
  (:require
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
  (let [today (java.time.LocalDate/now)]
    (->> (xt/q db
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
         reverse
         (take limit))))

(defn- get-task-counts
  [db user-id]
  (->> task-states
       (map (fn [state]
              [state (count (get-tasks-by-state db user-id state))]))
       (into {})))

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
        snooze-count (or (:task/snooze-count task) 0)]
    (or (> staleness review-staleness-threshold-days)
        (>= snooze-count review-snooze-threshold)
        (overdue? task today))))

(defn- get-review-queue-tasks
  [db user-id]
  (let [now   (java.time.Instant/now)
        today (java.time.LocalDate/now)
        tasks (mapcat #(get-tasks-by-state db user-id %)
                      [:now :later :waiting :snoozed])]
    (->> tasks
         (filter #(review-queue-task? % now today))
         (sort-by #(or (staleness-days % now) 0) >))))

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

(defn- now-task-row
  [task]
  (let [id (:xt/id task)]
    [:div.flex.items-center.gap-3.p-4.bg-dark-surface.rounded-lg.border.border-dark
     ;; Done button
     (biff/form
      {:action (str "/app/task/" id "/set-state"),
       :method "post",
       :class  "inline"}
      [:input {:type "hidden", :name "state", :value "done"}]
      [:button.w-6.h-6.rounded-full.border-2.border-gray-500.hover:border-neon-lime.hover:bg-neon-lime.transition-all
       {:type "submit"}])
     ;; Task label
     [:a.flex-1.text-white.hover:underline
      {:href (str "/app/crud/form/task/edit/" id)}
      (:task/label task)]
     ;; Snooze options
     [:div.flex.gap-1.text-xs.text-gray-500
      (snooze-button id 1 "+1d")
      (snooze-button id 3 "+3d")]]))

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

(defn- state-summary
  [task-counts]
  [:div.mt-10
   [:h2.text-lg.font-semibold.mb-3.text-gray-200 "State Summary"]
   [:div.grid.grid-cols-2.md:grid-cols-3.gap-3
    (for [state task-states]
      ^{:key (name state)}
      [:div.flex.items-center.justify-between.bg-dark-surface.border.border-dark.rounded.p-3
       [:span.text-sm.text-gray-400 (task-state-labels state)]
       [:span.text-base.font-semibold.text-white (get task-counts state 0)]])]])

(defn triage-view
  [{:keys [biff/db session], :as ctx}]
  (let [user-id     (:uid session)
        inbox-tasks (get-tasks-by-state db user-id :inbox)
        task-counts (get-task-counts db user-id)]
    (ui/page
     ctx
     (side-bar
      ctx
      [:div.max-w-2xl.mx-auto.p-4
       [:div.flex.items-center.justify-between.mb-6
        [:h1.text-2xl.font-bold "Task Triage"]
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/task/focus"}
         "focus →"]]

         ;; Quick add
       [:div.mb-6
        (biff/form
         {:action "/app/task/quick-add",
          :method "post",
          :class  "flex gap-2"}
         [:input.form-input.flex-1
          {:type         "text",
           :name         "label",
           :placeholder  "Quick add to inbox...",
           :required     true,
           :autocomplete "off"}]
         [:button.form-button-primary {:type "submit"} "Add"])]

         ;; Inbox
       [:div.mb-8
        [:h2.text-lg.font-semibold.mb-3.text-neon-lime
         (str "Inbox (" (count inbox-tasks) ")")]
        (if (seq inbox-tasks)
          (for [task inbox-tasks]
            ^{:key (str (:xt/id task))}
            (task-row task))
          [:p.text-gray-400 "Inbox empty"])]

       (state-summary task-counts)]))))

(defn quick-add!
  [{:keys [biff/db session params], :as ctx}]
  (let [user-id (:uid session)
        label   (:label params)
        now     (java.time.Instant/now)]
    (biff/submit-tx ctx
                    [{:db/doc-type    :task,
                      :xt/id          (random-uuid),
                      :user/id        user-id,
                      :task/label     label,
                      :task/state     :inbox,
                      ::sm/type       :task,
                      ::sm/created-at now}])
    {:status  303,
     :headers {"location" "/app/task/triage"}}))

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
     :headers {"location" "/app/task/triage"}}))

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
     :headers {"location" "/app/task/triage"}}))

(defn focus-view
  "Actionable and reviewable task list."
  [{:keys [biff/db session], :as ctx}]
  (let [user-id        (:uid session)
        tasks          (get-now-tasks db user-id 10)
        review-tasks   (get-review-queue-tasks db user-id)
        review-count   (count review-tasks)
        overflow-count (- (count (get-tasks-by-state db user-id :now))
                          (count tasks))]
    (ui/page
     ctx
     (side-bar
      ctx
      [:div.max-w-3xl.mx-auto.p-4
       [:div.flex.items-center.justify-between.mb-6
        [:h1.text-2xl.font-bold "Task Focus"]
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/task/triage"}
         "triage →"]]

       [:div.mb-10
        [:h2.text-lg.font-semibold.mb-3.text-neon-cyan "Now"]
        (if (seq tasks)
          [:div.space-y-2
           (for [task tasks]
             ^{:key (str (:xt/id task))}
             (now-task-row task))]
          [:p.text-gray-400.text-center.py-6
           "No tasks in Now. Go to triage to add some."])

        (when (pos? overflow-count)
          [:p.text-sm.text-gray-500.mt-4.text-center
           (str "+" overflow-count " more in Now list")])]

       [:div
        [:div.flex.items-center.justify-between.mb-3
         [:h2.text-lg.font-semibold.text-neon-lime
          (str "Review Queue (" review-count ")")]
         [:span.text-xs.text-gray-500
          "stale > 14d · snoozed 3+ · overdue"]]
        (if (seq review-tasks)
          (for [task review-tasks]
            ^{:key (str (:xt/id task))}
            (task-row task))
          [:p.text-gray-400 "Review queue empty."])]

       [:div.mt-8.pt-4.border-t.border-dark
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/crud/task"}
         "View all tasks →"]]]))))

(defn now-view
  "Focused view of actionable Now tasks - the short list"
  [{:keys [biff/db session], :as ctx}]
  (let [user-id        (:uid session)
        tasks          (get-now-tasks db user-id 10)
        overflow-count (- (count (get-tasks-by-state db user-id :now))
                          (count tasks))]
    (ui/page
     ctx
     (side-bar
      ctx
      [:div.max-w-xl.mx-auto.p-4
       [:div.flex.items-center.justify-between.mb-6
        [:h1.text-2xl.font-bold "Now"]
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/task/triage"}
         "triage →"]]

       (if (seq tasks)
         [:div.space-y-2
          (for [task tasks]
            ^{:key (str (:xt/id task))}
            (now-task-row task))]
         [:p.text-gray-400.text-center.py-8
          "No tasks in Now. Go to triage to add some."])

       (when (pos? overflow-count)
         [:p.text-sm.text-gray-500.mt-4.text-center
          (str "+" overflow-count " more in Now list")])

       [:div.mt-8.pt-4.border-t.border-dark
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/crud/task"}
         "View all tasks →"]]]))))

(def routes
  ["/task" {}
   ["" {:get focus-view}]
   ["/focus" {:get focus-view}]
   ["/now" {:get now-view}]
   ["/triage" {:get triage-view}]
   ["/quick-add" {:post quick-add!}]
   ["/:id/set-state" {:post set-state!}]
   ["/:id/snooze" {:post snooze!}]])
