(ns tech.jgood.gleanmo.ui.sortable
  "Reusable drag-and-drop sortable list components.

   Usage:
   (sortable-list {:endpoint \"/app/task/reorder-today\"}
     (sortable-item task-id-1 [:div \"Task 1\"])
     (sortable-item task-id-2 [:div \"Task 2\"]))

   The JS auto-discovers containers with .sortable-list and initializes
   drag-and-drop. On drop, it POSTs the new order to the endpoint.

   Data attribute contract (for JS):
   - .sortable-list: container element
   - data-sortable-endpoint: POST endpoint for reorder
   - .sortable-item: draggable items
   - data-sortable-id: unique ID for each item"
  (:require
   [cheshire.core :as json]
   [com.biffweb :as biff]
   [xtdb.api :as xt]))

(defn sortable-list
  "Wrap children in a sortable container.

   Options:
   - :endpoint - Required. URL to POST new order to.
   - :class - Optional. Additional CSS classes.
   - :id - Optional. Element ID (useful for HTMX targeting)."
  [{:keys [endpoint id] css-class :class} & children]
  (into [:div
         (cond-> {:class (str "sortable-list " (or css-class ""))
                  :data-sortable-endpoint endpoint}
           id (assoc :id id))]
        children))

(defn sortable-item
  "Wrap content in a sortable item.

   Arguments:
   - id - Required. Unique identifier (usually entity UUID).
   - content - The hiccup content to render inside."
  [id & content]
  (into [:div.sortable-item
         {:data-sortable-id (str id)}]
        content))

(defn reorder-entities!
  "Generic handler to reorder entities by updating an order field.

   Arguments:
   - ctx - Request context with :biff/db, :biff.xtdb/node, :params
   - entity-type - Keyword like :task
   - order-field - Keyword like :task/focus-order
   - id-param - Keyword for the param containing JSON array of IDs (default :ids)

   Returns the new db for chaining."
  [{:keys [params biff.xtdb/node] :as ctx} entity-type order-field & {:keys [id-param] :or {id-param :ids}}]
  ;; Try both string and keyword keys for param lookup
  (let [ids-json (or (get params id-param)
                     (get params (name id-param)))
        ids (when ids-json
              (-> ids-json
                  (json/parse-string)
                  (->> (map parse-uuid))))]
    (when (seq ids)
      (let [tx-docs (map-indexed
                     (fn [idx id]
                       {:db/op :update
                        :db/doc-type entity-type
                        :xt/id id
                        order-field idx})
                     ids)]
        (biff/submit-tx ctx tx-docs)))
    ;; Return fresh db
    (xt/db node)))
