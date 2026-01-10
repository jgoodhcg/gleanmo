(ns tech.jgood.gleanmo.app.task
  (:require
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.task-focus :as task-focus]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [xtdb.api :as xt]))

(def crud-routes
  (crud/gen-routes {:entity-key :task,
                    :entity-str "task",
                    :plural-str "tasks",
                    :schema     schema}))

(defn set-state!
  [{:keys [biff/db biff.xtdb/node session path-params params], :as ctx}]
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

(def routes
  ["/task" {}
   ["" {:get task-focus/focus-view}]
   ["/focus" {:get task-focus/focus-view}]
   ["/:id/set-state" {:post set-state!}]
   ["/:id/snooze" {:post snooze!}]
   ["/:id/clear-snooze" {:post clear-snooze!}]])
