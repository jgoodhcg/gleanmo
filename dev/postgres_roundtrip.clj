(ns dev.postgres-roundtrip
  "Ad-hoc helper for verifying the Postgres round trip from the REPL. Require this namespace
  (`(require 'dev.postgres-roundtrip)`) and call `(dev.postgres-roundtrip/run!)` once your
  Docker Postgres container is up."
  (:require
   [tech.jgood.gleanmo.postgres.client :as pg]
   [tech.jgood.gleanmo.postgres.datasource :as ds]
   [tech.jgood.gleanmo.schema.meta :as sm])
  (:import
   [java.time Instant]
   [java.util UUID]))

(defn sample-entity
  []
  (let [entity-id (UUID/randomUUID)
        user-id   (UUID/randomUUID)
        now       (Instant/now)]
    {:xt/id            entity-id
     ::sm/type         :cruddy
     ::sm/created-at   now
     :user/id          user-id
     :cruddy/label     "REPL round trip"
     :cruddy/notes     "Inserted via dev.postgres-roundtrip/run-roundtrip!"
     :cruddy/enum      :cruddy.status/active
     :cruddy/set       #{user-id}
     :cruddy/metadata  {:inserted-at (str now)}}))

(defn run!
  "Insert a sample entity into Postgres, read it back, optionally clean up, and return a summary map.

  Options:
  - `:cleanup?` (default true) – when true, deletes the inserted row after the fetch so repeated runs stay tidy."
  ([] (run! {:cleanup? true}))
  ([{:keys [cleanup?] :or {cleanup? true}}]
   (let [ds        (ds/datasource)
         entity    (sample-entity)
         inserted  (pg/insert-entity! ds entity)
         fetched   (pg/fetch-entity ds (:xt/id entity))]
     (when cleanup?
       (pg/delete-entity! ds (:xt/id entity)))
     {:input entity
     :inserted inserted
     :fetched fetched
     :matched? (= entity fetched)})))

(comment 
  
  (run!)
  ;;
  )
