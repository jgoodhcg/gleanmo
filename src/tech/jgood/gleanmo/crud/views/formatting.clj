(ns tech.jgood.gleanmo.crud.views.formatting
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [format-date-time-local get-user-time-zone]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; Multimethod for formatting cell values based on field type
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
     [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
      [:path {:fill-rule "evenodd" :d "M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" :clip-rule "evenodd"}]]]
    [:span.text-red-500.flex.items-center
     [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
      [:path {:fill-rule "evenodd" :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" :clip-rule "evenodd"}]]]))

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

(defmethod format-cell-value :boolean-or-enum
  [_ value _]
  (cond
    (nil? value) [:span.text-secondary "—"]
    (boolean? value) (if value
                       [:span.text-green-500.flex.items-center
                        [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
                         [:path {:fill-rule "evenodd" :d "M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" :clip-rule "evenodd"}]]]
                       [:span.text-red-500.flex.items-center
                        [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
                         [:path {:fill-rule "evenodd" :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" :clip-rule "evenodd"}]]])
    :else [:span.bg-enum.text-white.text-xs.font-medium.px-2.py-0.5.rounded-full
           (name value)]))

(defmethod format-cell-value :default
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.text-gray-600 (str value)]))
