(ns tech.jgood.gleanmo.crud.views.formatting
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [format-date-time-local get-user-time-zone]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.duration :as duration]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui.icons :as icons]))

;; Multimethod for formatting cell values based on field type
#_{:clj-kondo/ignore [:shadowed-var]}
(defmulti format-cell-value (fn [type _ _] type))

(defmethod format-cell-value :string
  [_ value _]
  (if (or (nil? value) (str/blank? value))
    [:span.text-secondary "—"]
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
    [:span.text-green-500.flex.items-center
     (icons/check)]
    [:span.text-red-500.flex.items-center
     (icons/x)]))

(defmethod format-cell-value :number
  [_ value _]
  (if (nil? value)
    [:span.text-secondary "—"]
    [:span (str value)]))

(defmethod format-cell-value :float
  [_ value _]
  (if (nil? value)
    [:span.text-secondary "—"]
    [:span (format "%.2f" value)]))

(defmethod format-cell-value :double
  [_ value _]
  (if (nil? value)
    [:span.text-secondary "—"]
    [:span (format "%.2f" value)]))

(defmethod format-cell-value :int
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (str value)]))

(defmethod format-cell-value :positive-int
  [_ value ctx]
  (format-cell-value :int value ctx))

(defmethod format-cell-value :nonnegative-int
  [_ value ctx]
  (format-cell-value :int value ctx))

(defmethod format-cell-value :hms-duration
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.tabular-nums (duration/format-hms value)]))

(defmethod format-cell-value :local-date
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
    (let [entity      (db/get-entity-by-id db value)
          entity-type (or (some-> entity
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
      [:span.text-relationship label])))

(defmethod format-cell-value :many-relationship
  [_ values {:keys [biff/db]}]
  (cond
    (nil? values) [:span.text-secondary "—"]
    (empty? values) [:span.text-secondary "Empty set"]
    :else
    (let [labels         (for [value values]
                           (let [entity      (db/get-entity-by-id db value)
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
    [:span.text-secondary "—"]
    [:span.bg-enum.text-white.text-xs.font-medium.px-2.py-0.5.rounded-full
     (name value)]))

(defmethod format-cell-value :set-enum
  [_ values _]
  (cond
    (nil? values)   [:span.text-secondary "—"]
    (empty? values) [:span.text-secondary "—"]
    :else
    [:div.flex.flex-wrap.gap-1
     (for [v (sort-by name values)]
       [:span.bg-enum.text-white.text-xs.font-medium.px-2.py-0.5.rounded-full
        (name v)])]))

(defmethod format-cell-value :boolean-or-enum
  [_ value _]
  (cond
    (nil? value) [:span.text-secondary "—"]
    (boolean? value) (if value
                       [:span.text-green-500.flex.items-center
                        (icons/check)]
                       [:span.text-red-500.flex.items-center
                        (icons/x)])
    :else [:span.bg-enum.text-white.text-xs.font-medium.px-2.py-0.5.rounded-full
           (name value)]))

(defmethod format-cell-value :default
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.text-gray-600 (str value)]))
