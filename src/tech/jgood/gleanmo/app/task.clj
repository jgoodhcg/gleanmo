(ns tech.jgood.gleanmo.app.task
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.task-focus :as task-focus]
   [tech.jgood.gleanmo.app.task-today :as task-today]
   [tech.jgood.gleanmo.crud.routes :as crud]
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
        snooze-until (.plusDays (java.time.LocalDate/now) days)
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
        today (java.time.LocalDate/now)
        ;; Get max focus-order for today and add 1
        existing-tasks (xt/q db
                             '{:find [(pull ?e [:task/focus-order])]
                               :where [[?e :task/focus-date today]
                                       [?e :user/id user-id]]
                               :in [today user-id]}
                             today
                             (:uid (:session ctx)))
        max-order (or (->> existing-tasks
                           (map first)
                           (keep :task/focus-order)
                           (apply max 0))
                      0)]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/focus-date today
                      :task/focus-order (inc max-order)}])
    (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))))

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
        tomorrow (.plusDays (java.time.LocalDate/now) 1)]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/focus-date tomorrow}])
    (task-today/today-content (assoc ctx :biff/db (xt/db node)))))

(defn remove-from-today!
  "Remove a task from today's focus (back to backlog).
   Returns appropriate view based on referer (Focus page vs Today page)."
  [{:keys [biff.xtdb/node path-params headers], :as ctx}]
  (let [task-id (parse-uuid (:id path-params))]
    (biff/submit-tx ctx
                    [{:db/op :update
                      :db/doc-type :task
                      :xt/id task-id
                      :task/focus-date nil
                      :task/focus-order nil}])
    ;; Return appropriate view based on where the request came from
    (let [referer (get headers "referer" "")
          from-focus-page? (or (str/includes? referer "/task/focus")
                               (str/includes? referer "/task?")
                               (str/ends-with? referer "/task"))]
      (if from-focus-page?
        (task-focus/focus-view (assoc ctx :biff/db (xt/db node)))
        (task-today/today-content (assoc ctx :biff/db (xt/db node)))))))

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
   ["/:id/set-state" {:post set-state!}]
   ["/:id/snooze" {:post snooze!}]
   ["/:id/clear-snooze" {:post clear-snooze!}]
   ["/:id/focus-today" {:post focus-today!}]
   ["/:id/complete-today" {:post complete-today!}]
   ["/:id/defer-today" {:post defer-today!}]
   ["/:id/remove-from-today" {:post remove-from-today!}]])
