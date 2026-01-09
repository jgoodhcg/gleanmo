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

(defn- get-tasks-by-state
  [db user-id state]
  (->> (xt/q db
             '{:find  [(pull ?e [*])]
               :where [[?e :user/id user-id]
                       [?e ::sm/type :task]
                       [?e :task/state state]
                       (not [?e ::sm/deleted-at])]
               :in    [[user-id state]]}
             [user-id state])
       (map first)
       (sort-by ::sm/created-at)
       reverse))

(defn- primary-button
  "Primary action button - visually prominent"
  [task-id target-state label]
  (biff/form
   {:action (str "/app/task/" task-id "/set-state")
    :method "post"
    :class "inline"}
   [:input {:type "hidden" :name "state" :value (name target-state)}]
   [:button.px-3.py-1.rounded.font-semibold.text-sm.transition-all
    {:type "submit"
     :class "bg-neon-lime text-dark hover:bg-neon-lime/80"}
    label]))

(defn- secondary-button
  "Secondary action - less prominent"
  [task-id target-state label]
  (biff/form
   {:action (str "/app/task/" task-id "/set-state")
    :method "post"
    :class "inline"}
   [:input {:type "hidden" :name "state" :value (name target-state)}]
   [:button.px-2.py-1.rounded.text-xs.transition-all
    {:type "submit"
     :class "border border-dark-border text-gray-400 hover:text-white hover:border-gray-500"}
    label]))

(defn- snooze-button
  "Snooze button with days parameter"
  [task-id days label]
  (biff/form
   {:action (str "/app/task/" task-id "/snooze")
    :method "post"
    :class "inline"}
   [:input {:type "hidden" :name "days" :value (str days)}]
   [:button.px-2.py-1.rounded.text-xs.transition-all
    {:type "submit"
     :class "text-gray-500 hover:text-neon-pink hover:bg-neon-pink/10"}
    label]))

(defn- task-row
  [task]
  (let [id (:xt/id task)
        state (:task/state task)]
    [:div.flex.items-center.justify-between.p-3.bg-dark-surface.rounded.mb-2.border.border-dark
     [:div.flex-1.min-w-0
      [:a.font-medium.text-white.hover:underline
       {:href (str "/app/crud/form/task/edit/" id)}
       (:task/label task)]
      (when-let [notes (:task/notes task)]
        [:p.text-sm.text-gray-400.truncate {:style {:max-width "300px"}} notes])]
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

(defn triage-view
  [{:keys [biff/db session] :as ctx}]
  (let [user-id (:uid session)
        inbox-tasks (get-tasks-by-state db user-id :inbox)
        now-tasks (get-tasks-by-state db user-id :now)]
    (ui/page
     ctx
     (side-bar
      ctx
      [:div.max-w-2xl.mx-auto.p-4
       [:h1.text-2xl.font-bold.mb-6 "Task Triage"]

       ;; Quick add
       [:div.mb-6
        (biff/form
         {:action "/app/task/quick-add"
          :method "post"
          :class "flex gap-2"}
         [:input.form-input.flex-1
          {:type "text"
           :name "label"
           :placeholder "Quick add to inbox..."
           :required true
           :autocomplete "off"}]
         [:button.form-button-primary {:type "submit"} "Add"])]

       ;; Inbox
       [:div.mb-8
        [:h2.text-lg.font-semibold.mb-3.text-neon-lime
         (str "Inbox (" (count inbox-tasks) ")")]
        (if (seq inbox-tasks)
          (for [task inbox-tasks]
            (task-row task))
          [:p.text-gray-400 "Inbox empty"])]

       ;; Now
       [:div
        [:h2.text-lg.font-semibold.mb-3.text-neon-cyan
         (str "Now (" (count now-tasks) ")")]
        (if (seq now-tasks)
          (for [task now-tasks]
            (task-row task))
          [:p.text-gray-400 "No tasks in Now"])]]))))

(defn quick-add!
  [{:keys [biff/db session params] :as ctx}]
  (let [user-id (:uid session)
        label (:label params)
        now (java.time.Instant/now)]
    (biff/submit-tx ctx
                    [{:db/doc-type :task
                      :xt/id (random-uuid)
                      :user/id user-id
                      :task/label label
                      :task/state :inbox
                      ::sm/type :task
                      ::sm/created-at now}])
    {:status 303
     :headers {"location" "/app/task/triage"}}))

(defn set-state!
  [{:keys [biff/db session path-params params] :as ctx}]
  (let [task-id (parse-uuid (:id path-params))
        new-state (keyword (:state params))
        now (java.time.Instant/now)
        task (xt/entity db task-id)
        snooze-count (or (:task/snooze-count task) 0)
        state-change-count (or (:task/state-change-count task) 0)
        tx-doc (cond-> {:db/op :update
                        :db/doc-type :task
                        :xt/id task-id
                        :task/state new-state
                        :task/last-state-change-at now
                        :task/state-change-count (inc state-change-count)
                        :task/snooze-count (if (= new-state :snoozed)
                                             (inc snooze-count)
                                             snooze-count)}
                 (= new-state :done) (assoc :task/done-at now))]
    (biff/submit-tx ctx [tx-doc])
    {:status 303
     :headers {"location" "/app/task/triage"}}))

(defn snooze!
  [{:keys [biff/db path-params params] :as ctx}]
  (let [task-id (parse-uuid (:id path-params))
        days (Integer/parseInt (:days params))
        now (java.time.Instant/now)
        snooze-until (.plusDays (java.time.LocalDate/now) days)
        task (xt/entity db task-id)
        snooze-count (or (:task/snooze-count task) 0)
        state-change-count (or (:task/state-change-count task) 0)]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/state :snoozed
                      :task/snooze-until snooze-until
                      :task/last-state-change-at now
                      :task/state-change-count (inc state-change-count)
                      :task/snooze-count (inc snooze-count)}])
    {:status 303
     :headers {"location" "/app/task/triage"}}))

(def routes
  ["/task" {}
   ["/triage" {:get triage-view}]
   ["/quick-add" {:post quick-add!}]
   ["/:id/set-state" {:post set-state!}]
   ["/:id/snooze" {:post snooze!}]])
