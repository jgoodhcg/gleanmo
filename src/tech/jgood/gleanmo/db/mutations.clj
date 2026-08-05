(ns tech.jgood.gleanmo.db.mutations
  (:require
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tick.core :as t]
   [xtdb.api :as xt]))

;; ---------------------------------------------------------------------------
;; Running-timer flag
;;
;; `<entity>/running` is derived here, on every write, rather than set by the
;; timer handlers. XTDB indexes presence, not absence, so "has a beginning and
;; no end" is a set difference over the entity's whole history; the sparse flag
;; turns it into one index lookup (see roadmap/timer-running-flag.md).
;;
;; Deriving it at the single mandatory write chokepoint is what keeps it
;; honest. Setting it in `start-timer`/`stop-timer` would turn a property that
;; cannot lie into one every future call site has to remember — the generic
;; CRUD edit form, `resume-set!`, imports, migrations and REPL writes all reach
;; the document without going near a timer handler. Here the flag stays derived
;; state; it just gets derived at write time instead of read time.
;;
;; Which entities participate is read off the schema (`running-flag-entities`),
;; not from a list in the app layer.
;; ---------------------------------------------------------------------------

(defn- running-config
  [entity-key]
  (get @schema-utils/running-flag-entities entity-key))

(defn- running?
  [beginning end]
  (and (some? beginning) (nil? end)))

(defn- merged-value
  "Value of `k` once `data` has been merged into `stored`, honouring Biff's
   `:db/dissoc` sentinel."
  [stored data k]
  (if (contains? data k)
    (let [v (get data k)]
      (when-not (= :db/dissoc v) v))
    (get stored k)))

(defn- with-derived-running
  "Attach the derived `running` flag to the `data` of a partial update.

   `:db/op :update` merges `data` into the stored document, so the flag cannot
   be read off `data` alone: an update setting only `beginning` leaves `end` to
   the stored document, and one clearing `end` leaves `beginning` to it. When
   `data` touches either interval field we read the current document — a single
   by-id lookup — and derive from the merged result. An update touching neither
   field leaves the flag alone rather than paying for that read.

   The read takes a fresh snapshot, not ctx's `:biff/db`. `submit-tx` merges
   against a fresh one of its own (`assoc-db` overwrites `:biff/db` on the way
   in), and deriving from an older document than Biff merges into is precisely
   how the flag would go stale."
  [{:keys [biff.xtdb/node biff/db]} entity-key entity-id data]
  (if-let [{:keys [beginning-key end-key running-key]} (running-config
                                                        entity-key)]
    (if (or (contains? data beginning-key)
            (contains? data end-key))
      (let [stored (xt/entity (if node (xt/db node) db) entity-id)]
        (assoc data
               running-key (if (running? (merged-value stored data beginning-key)
                                         (merged-value stored data end-key))
                             true
                             :db/dissoc)))
      data)
    data))

(defn entity-doc
  "Shape `data` into the document `create-entity!` would submit, without
   submitting it. Callers that need to validate before writing (e.g. inline
   create, which re-renders a form on failure instead of throwing) build the
   doc with this so their check sees exactly the meta fields that get stored.

   For timer entities that means the derived `running` flag too — `data` is the
   whole document on a create, so no read is needed."
  [entity-key data]
  (let [doc (merge {:xt/id          (random-uuid),
                    ::sm/type       entity-key,
                    ::sm/created-at (t/now)}
                   data)]
    (if-let [{:keys [beginning-key end-key running-key]} (running-config
                                                          entity-key)]
      (if (running? (get doc beginning-key) (get doc end-key))
        (assoc doc running-key true)
        (dissoc doc running-key))
      doc)))

(defn create-entity!
  "Create a new entity in the database."
  [ctx {:keys [entity-key data]}]
  (let [doc (entity-doc entity-key data)]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type entity-key,
                             :xt/id       (:xt/id doc)}
                            doc)])
    (:xt/id doc)))

(defn create-entities!
  "Create multiple entities in one transaction and return their IDs in input order."
  [ctx entity-specs]
  (let [docs    (mapv (fn [{:keys [entity-key data]}]
                        [entity-key (entity-doc entity-key data)])
                      entity-specs)
        tx-docs (mapv (fn [[entity-key doc]]
                        (merge {:db/doc-type entity-key
                                :xt/id       (:xt/id doc)}
                               doc))
                      docs)]
    (when (seq tx-docs)
      (biff/submit-tx ctx tx-docs))
    (mapv (comp :xt/id second) docs)))

(defn update-entity!
  "Update an existing entity in the database.

   `data` is merged into the stored document. For timer entities the derived
   `running` flag rides along whenever `data` touches an interval field, so
   every path — CRUD form included — maintains it without knowing it exists."
  [ctx {:keys [entity-key entity-id data]}]
  (let [tx-op   {:db/op       :update,
                 :db/doc-type entity-key,
                 ::sm/type    entity-key,
                 :xt/id       entity-id}
        tx-data (merge tx-op
                       (with-derived-running ctx entity-key entity-id data))]
    (biff/submit-tx ctx [tx-data])
    entity-id))

(defn soft-delete-entity!
  "Soft-delete an entity by setting its deleted-at timestamp."
  [ctx {:keys [entity-key entity-id]}]
  (let [tx-op {:db/op          :update,
               :db/doc-type    entity-key,
               :xt/id          entity-id,
               ::sm/deleted-at (t/now)}]
    (biff/submit-tx ctx [tx-op])
    entity-id))

(defn update-user!
  "Update user entity with provided data."
  [ctx user-id user-data]
  (let [tx-data (merge {:db/op       :update
                        :db/doc-type :user
                        :xt/id       user-id}
                       user-data)]
    (biff/submit-tx ctx [tx-data])
    user-id))

(defn create-event!
  "Create a new calendar event."
  [ctx event-data]
  (create-entity! ctx {:entity-key :event
                       :data event-data}))
