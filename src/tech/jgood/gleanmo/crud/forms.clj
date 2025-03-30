(ns tech.jgood.gleanmo.crud.forms
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar
                                          get-user-time-zone
                                          str->instant!]]
   [tech.jgood.gleanmo.crud.fields :as f]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId LocalDateTime ZonedDateTime]))

(defn prepare-form-fields
  "Extract and prepare fields from a schema, filtering out system fields.
   Returns a sequence of prepared field maps."
  [schema]
  (let [raw-fields (schema-utils/extract-schema-fields schema)]
    (->> raw-fields
         (map schema-utils/prepare-field)
         ;; remove fields that aren't necessary for forms
         (remove schema-utils/should-remove-system-or-user-field?))))

(defn schema->form [schema ctx]
  (let [fields (prepare-form-fields schema)]
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
        (str->instant! value zone-id)
        (catch IllegalArgumentException e
          (throw (ex-info (.getMessage e) {:value value :type :instant})))))))

(defmethod convert-field-value :single-relationship [_ value _]
  (try
    (java.util.UUID/fromString value)
    (catch IllegalArgumentException e
      (throw (ex-info (str "Could not convert '" value "' to UUID: " (.getMessage e))
                      {:value value :type :single-relationship})))))

(defmethod convert-field-value :many-relationship [_ values _]
  (cond
    (string? values)
    (when (not-empty values)
      #{(try
          (java.util.UUID/fromString values)
          (catch IllegalArgumentException e
            (throw (ex-info (str "Could not convert '" values "' to UUID: " (.getMessage e))
                            {:value values :type :many-relationship}))))})
    :else
    (into #{}
          (map (fn [v]
                 (try
                   (java.util.UUID/fromString v)
                   (catch IllegalArgumentException e
                     (throw (ex-info (str "Could not convert '" v "' to UUID: " (.getMessage e))
                                     {:value v :type :many-relationship})))))
               values))))

(defmethod convert-field-value :enum [_ value _]
  (when (not-empty value)
    (keyword value)))

(defmethod convert-field-value :default [type value _]
  (throw (ex-info (str "Unknown field type for conversion: " type)
                  {:value value :type type})))

(defn form->schema [form-fields schema ctx]
  (-> form-fields
      (dissoc :__anti-forgery-token)
      (->> (reduce
            (fn [acc [k v]]
              (let [k                    (keyword k)
                    field-info           (schema-utils/get-field-info schema k)
                    optional?            (get-in field-info [:opts :optional])
                    type                 (:type field-info)
                    {:keys [input-type]} (schema-utils/determine-input-type type)]
                      ;; Skip empty values for optional fields, otherwise convert
                (if (and optional? (or (nil? v) (str/blank? v)))
                  acc  ; Skip this field
                  (let [converted-value (convert-field-value input-type v ctx)]
                    (assoc acc k converted-value)))))
            {}))))

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
                  ::sm/created-at (t/now)
                  :user/id        user-id}
                 data)]
    (pprint doc)
    (biff/submit-tx ctx
                    [(merge {:db/doc-type entity-key
                             :xt/id       (:xt/id doc)}
                            doc)])
    {:status  303
     :headers {"location" (str "/app/crud/new/" entity)}}))

(defn edit-form [{:keys [entity-key
                         schema
                         plural-str
                         entity-str]}
                 {:keys [session biff/db params path-params]
                  :as   ctx}]
  (let [user-id   (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        entity-id (java.util.UUID/fromString (:id path-params))
        entity    (xt/entity db entity-id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div
                 [:h1.text-xl.font-bold.mb-4 (str "Edit " (str/capitalize entity-str))]
                 [:div.bg-white.shadow.rounded.p-4
                  [:h2.text-lg.font-semibold "Entity Details:"]
                  [:pre.mt-4.bg-gray-100.p-4.rounded.text-sm.overflow-auto
                   (with-out-str (pprint entity))]]])])))

