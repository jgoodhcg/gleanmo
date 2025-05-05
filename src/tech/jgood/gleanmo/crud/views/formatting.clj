(ns tech.jgood.gleanmo.crud.views.formatting
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [format-date-time-local get-user-time-zone]]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [xtdb.api :as xt]))

;; Multimethod for formatting cell values based on field type
(defmulti format-cell-value (fn [type _ _] type))

(defmethod format-cell-value :string
  [_ value _]
  (if (or (nil? value) (str/blank? value))
    [:span.text-gray-400 "—"]
    (let [str-value    (str value)
          truncated?   (> (count str-value) 50)
          display-text (if truncated?
                         (str (subs str-value 0 47) "...")
                         str-value)]
      [:div.max-w-xs.truncate
       {:title (if truncated? str-value ""),
        :style {:max-width "200px"}}
       display-text])))

(defmethod format-cell-value :boolean
  [_ value _]
  (if value
    [:span.text-green-600 "✓"]
    [:span.text-red-600 "✗"]))

(defmethod format-cell-value :number
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (str value)]))

(defmethod format-cell-value :float
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (format "%.2f" value)]))

(defmethod format-cell-value :int
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (str value)]))

(defmethod format-cell-value :instant
  [_ value ctx]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    (let [time-zone (get-user-time-zone ctx)]
      [:span (format-date-time-local value time-zone)])))

(defmethod format-cell-value :single-relationship
  [_ value {:keys [biff/db]}]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    (let [entity      (xt/entity db value)
          entity-type (or (-> entity
                              ::sm/type
                              name)
                          (some-> entity
                                  keys
                                  first
                                  namespace))
          label-key   (when entity-type (keyword entity-type "label"))
          label       (if (and label-key (contains? entity label-key))
                        (get entity label-key)
                        (str (subs (str value) 0 8) "..."))]
      [:span.text-blue-600 label])))

(defmethod format-cell-value :many-relationship
  [_ values {:keys [biff/db]}]
  (cond
    (nil? values) [:span.text-gray-400 "—"]
    (empty? values) [:span.text-gray-400 "Empty set"]
    :else
    (let [labels         (for [value values]
                           ;; TODO replace xt/entity with db query to get by id
                           (let [entity      (xt/entity db value)
                                 entity-type (or (-> entity
                                                     ::sm/type
                                                     name)
                                                 (some-> entity
                                                         keys
                                                         first
                                                         namespace))
                                 label-key   (when entity-type
                                               (keyword entity-type "label"))]
                             (if (and label-key (contains? entity label-key))
                               (get entity label-key)
                               (str (subs (str value) 0 8) "..."))))
          tooltip-labels (str/join ", " labels)]
      [:div.text-xs.text-gray-500.mt-1.truncate {:title tooltip-labels}
       (when (seq labels)
         (str
          (str/join ", " (take 2 labels))
          (when (-> labels
                    count
                    (> 2))
            " ...")))])))

(defmethod format-cell-value :enum
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.bg-purple-100.text-purple-800.text-xs.font-medium.px-2.py-0.5.rounded-full
     (name value)]))

(defmethod format-cell-value :default
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.text-gray-600 (str value)]))
