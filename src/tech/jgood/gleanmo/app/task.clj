(ns tech.jgood.gleanmo.app.task
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.app.task-focus :as task-focus]
   [tech.jgood.gleanmo.app.task-today :as task-today]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.ui.sortable :as sortable]
   [xtdb.api :as xt]))

(def crud-routes
  (crud/gen-routes {:entity-key :task,
                    :entity-str "task",
                    :plural-str "tasks",
                    :schema     schema}))

(defn set-state!
  [{:keys [biff/db biff.xtdb/node path-params params], :as ctx}]
  (let [task-id      (parse-uuid (:id path-params))
        new-state    (keyword (or (:target-state params)
                                  (:state params)))
        now          (java.time.Instant/now)
        task         (xt/entity db task-id)
        state-change-count (or (:task/state-change-count task) 0)
        tx-doc       (cond-> {:db/op             :update,
                              :db/doc-type       :task,
                              :xt/id             task-id,
                              :task/state        new-state,
                              :task/last-state-change-at now,
                              :task/state-change-count (inc state-change-count)}
                       (= new-state :done) (assoc :task/done-at now))]
    (biff/submit-tx ctx [tx-doc])
    (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))))

(defn snooze!
  [{:keys [biff/db biff.xtdb/node path-params params], :as ctx}]
  (let [task-id      (parse-uuid (:id path-params))
        days         (Integer/parseInt (:days params))
        snooze-until (.plusDays (shared/user-local-date ctx) days)
        task         (xt/entity db task-id)
        snooze-count (or (:task/snooze-count task) 0)]
    (biff/submit-tx ctx
                    [{:db/op :update,
                      :db/doc-type :task,
                      :xt/id task-id,
                      :task/snooze-until snooze-until,
                      :task/snooze-count (inc snooze-count)}])
    (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))))

(defn clear-snooze!
  [{:keys [biff.xtdb/node path-params], :as ctx}]
  (let [task-id (parse-uuid (:id path-params))]
    (biff/submit-tx ctx
                    [{:db/op            :update,
                      :db/doc-type      :task,
                      :xt/id            task-id,
                      :task/snooze-until nil}])
    (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))))

;; Daily focus actions

(defn focus-today!
  "Add a task to today's focus list."
  [{:keys [biff/db biff.xtdb/node path-params], :as ctx}]
  (let [task-id (parse-uuid (:id path-params))
        today (shared/user-local-date ctx)
        next-order (queries/next-focus-order-for-date db
                                                      (:uid (:session ctx))
                                                      today)]
    (mutations/update-entity! ctx
                              {:entity-key :task
                               :entity-id task-id
                               :data {:task/focus-date today
                                      :task/focus-order next-order}})
    (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))))

(defn quick-add-today!
  "Quickly create a task and add it to today's focus list."
  [{:keys [biff/db biff.xtdb/node session params], :as ctx}]
  (let [label (some-> (:label params) str/trim)
        user-id (:uid session)
        today (shared/user-local-date ctx)]
    (when (seq label)
      (let [next-order (queries/next-focus-order-for-date db user-id today)]
        (mutations/create-entity! ctx
                                  {:entity-key :task
                                   :data {:user/id user-id
                                          :task/label label
                                          :task/state :now
                                          :task/focus-date today
                                          :task/focus-order next-order}})))
    (task-today/today-content (assoc ctx :biff/db (xt/db node)))))

(defn complete-today!
  "Mark a task as done from the today view."
  [{:keys [biff/db biff.xtdb/node path-params], :as ctx}]
  (let [task-id (parse-uuid (:id path-params))
        now (java.time.Instant/now)
        task (xt/entity db task-id)
        state-change-count (or (:task/state-change-count task) 0)]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/state :done
                      :task/done-at now
                      :task/last-state-change-at now
                      :task/state-change-count (inc state-change-count)}])
    (task-today/today-content (assoc ctx :biff/db (xt/db node)))))

(defn defer-today!
  "Move a task to tomorrow."
  [{:keys [biff.xtdb/node path-params], :as ctx}]
  (let [task-id (parse-uuid (:id path-params))
        tomorrow (.plusDays (shared/user-local-date ctx) 1)]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/focus-date tomorrow}])
    (task-today/today-content (assoc ctx :biff/db (xt/db node)))))

(defn remove-from-today!
  "Remove a task from today's focus (back to backlog).
   Returns appropriate view based on referer (Focus page vs Today page)."
  [{:keys [biff.xtdb/node path-params headers params], :as ctx}]
  (let [task-id (parse-uuid (:id path-params))]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/focus-date :db/dissoc
                      :task/focus-order :db/dissoc}])
    ;; Return appropriate view based on where the request came from
    (let [origin          (:origin params)
          hx-target       (get headers "hx-target")
          current-url     (or (get headers "hx-current-url")
                              (get headers "referer")
                              "")
          from-focus-page? (or (= origin "focus")
                               (= hx-target "task-list")
                               (str/includes? current-url "/task/focus")
                               (str/includes? current-url "/task?")
                               (str/ends-with? current-url "/task"))
          from-today-page? (or (= origin "today")
                               (= hx-target "today-content")
                               (str/includes? current-url "/task/today"))]
      (cond
        from-focus-page?
        (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))

        from-today-page?
        (task-today/today-content (assoc ctx :biff/db (xt/db node)))

        :else
        (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))))))

(defn reorder-today!
  "Reorder tasks in the today list via drag-and-drop."
  [ctx]
  (let [new-db (sortable/reorder-entities! ctx :task :task/focus-order)]
    (task-today/today-content (assoc ctx :biff/db new-db))))

(def routes
  ["/task" {}
   ["" {:get task-focus/focus-view}]
   ["/focus" {:get task-focus/focus-view}]
   ["/today" {:get task-today/today-view}]
   ["/reorder-today" {:post reorder-today!}]
   ["/quick-add-today" {:post quick-add-today!}]
   ["/:id/set-state" {:post set-state!}]
   ["/:id/snooze" {:post snooze!}]
   ["/:id/clear-snooze" {:post clear-snooze!}]
   ["/:id/focus-today" {:post focus-today!}]
   ["/:id/complete-today" {:post complete-today!}]
   ["/:id/defer-today" {:post defer-today!}]
   ["/:id/remove-from-today" {:post remove-from-today!}]])
