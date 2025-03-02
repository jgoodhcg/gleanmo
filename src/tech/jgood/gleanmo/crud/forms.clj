(ns tech.jgood.gleanmo.crud.forms
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar get-user-time-zone str->instant]]
   [tech.jgood.gleanmo.crud.fields :as f]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId LocalDateTime ZonedDateTime]))

(defn schema->form [schema ctx]
  (let [has-opts   (map? (second schema))
        raw-fields (if has-opts (drop 2 schema) (rest schema))
        fields     (->> raw-fields
                      (map f/prepare)
                      ;; remove fields that aren't necessary for new forms
                      (remove (fn [{:keys [field-key]}]
                                (let [n (namespace field-key)]
                                  (or
                                   (= :xt/id field-key)
                                   (= :user/id field-key)
                                   (= "tech.jgood.gleanmo.schema" n)
                                   (= "tech.jgood.gleanmo.schema.meta" n))))))]
    (for [field fields]
      (f/input field ctx))))

(defn new-form [{:keys [entity-key
                        schema
                        plural-str
                        entity-str]}
                {:keys [session biff/db params]
                 :as   ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        form-id              (str entity-str "-new-form")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/crud/" entity-str)
                   :hx-swap   "outerHTML"
                   :hx-select (str "#" form-id)
                   :id        form-id}
                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900
                    (str "New " (str/capitalize entity-str))]
                   [:p.mt-1.text-sm.leading-6.text-gray-600
                    (str "Create a new " entity-str)]]
                  [:div.grid.grid-cols-1.gap-y-6
                   (doall (schema->form schema ctx))
                   [:button {:type "submit"} "Create"]])])])))

(defn get-field-info 
  "Returns a map with :type and :opts for a given field key"
  [schema k]
  (some (fn [[field-key & rest]]
          (when (= field-key k)
            (if (map? (first rest))
              {:type (second rest)
               :opts (first rest)}  ; When an options map is present
              {:type (first rest)
               :opts {}}))) ; When no options map is present
        (drop 2 schema)))

(defn get-type [schema k]
  (:type (get-field-info schema k)))

(defmulti convert-field-value (fn [type _value _ctx] type))

(defmethod convert-field-value :string [_ value _]
  value)

(defmethod convert-field-value :int [_ value _]
  (try
    (Integer/parseInt value)
    (catch Exception e
      (throw (ex-info (str "Could not convert '" value "' to int: " (.getMessage e)) 
                     {:value value :type :int})))))

(defmethod convert-field-value :float [_ value _]
  (try
    (Float/parseFloat value)
    (catch Exception e
      (throw (ex-info (str "Could not convert '" value "' to float: " (.getMessage e)) 
                     {:value value :type :float})))))

(defmethod convert-field-value :number [_ value _]
  (try
    (Double/parseDouble value)
    (catch Exception e
      (throw (ex-info (str "Could not convert '" value "' to number: " (.getMessage e)) 
                     {:value value :type :number})))))

(defmethod convert-field-value :boolean [_ value _]
  (boolean value))

(defmethod convert-field-value :instant [_ value ctx]
  (when (not-empty value)
    (let [time-zone (get-user-time-zone ctx)
          zone-id (ZoneId/of (or time-zone "UTC"))]
      (try
        (tech.jgood.gleanmo.app.shared/str->instant value zone-id)
        (catch Exception e
          (throw (ex-info (str "Could not convert '" value "' to instant: " (.getMessage e)) 
                         {:value value :type :instant})))))))

(defmethod convert-field-value :single-relationship [_ value _]
  value)

(defmethod convert-field-value :many-relationship [_ values _]
  (if (string? values)
    #{values}
    (set values)))

(defmethod convert-field-value :enum [_ value _]
  (when (not-empty value)
    (keyword value)))

(defmethod convert-field-value :default [type value _]
  (throw (ex-info (str "Unknown field type for conversion: " type) 
                  {:value value :type type})))

(defn form->schema [form-fields schema ctx]
  (pprint form-fields)
  (let [result (-> form-fields
                    (dissoc :__anti-forgery-token)
                    (->> (reduce (fn [acc [k v]]
                                   (let [k (keyword k)
                                         field-info (get-field-info schema k)
                                         optional? (get-in field-info [:opts :optional])
                                         type (:type field-info)
                                         input-type (cond
                                                      (and (vector? type) (= :enum (first type))) :enum
                                                      (and (vector? type) (= :set (first type))
                                                           (let [elem (second type)]
                                                             (and (keyword? elem) (= "id" (name elem))))) :many-relationship
                                                      (and (keyword? type) (= "id" (name type))) :single-relationship
                                                      :else type)]
                                     ;; Skip empty values for optional fields, otherwise convert
                                     (if (and optional? (or (nil? v) (str/blank? v)))
                                       acc  ; Skip this field
                                       (let [converted-value (convert-field-value input-type v ctx)]
                                         (assoc acc k converted-value)))))
                                 {})))]
    result))

(defn create-entity! [{:keys [schema entity-key]
                       :as   args}
                      {:keys [session biff/db params]
                       :as   ctx}]
  (let [user-id (:uid session)
        user    (xt/entity db user-id)
        entity  (-> args :entity-key name)
        data    (form->schema params schema ctx)
        doc     (merge 
                 {:xt/id          (random-uuid)
                  ::sm/type       entity-key
                  ::sm/created-at (java.time.Instant/now)
                  :user/id        user-id}
                 data)]
    (pprint doc)
    {:status  303
     :headers {"location" (str "/app/crud/new/" entity)}}))
