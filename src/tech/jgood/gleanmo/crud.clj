(ns tech.jgood.gleanmo.crud
  (:require
   [clojure.pprint :refer [pprint]]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.app.shared :refer [side-bar param-true?]]
   [clojure.string :as str]
   [xtdb.api :as xt]
   [tech.jgood.gleanmo.ui :as ui]))

(defn all-for-user-query [{:keys [entity-type-str]}
                          {:keys [biff/db session params]}]
  (let [sensitive      (some-> params :sensitive param-true?)
        archived       (some-> params :archived param-true?)
        entity-type    (keyword entity-type-str)
        time-stamp-key (keyword entity-type-str "timestamp")
        sensitive-key  (keyword entity-type-str "sensitive")
        archived-key   (keyword entity-type-str "archived")
        raw-results    (q db {:find  '(pull ?e [*])
                              :where ['[?e :user/id user-id]
                                      ['?e ::sm/type entity-type]
                                      '[?user :xt/id user-id]
                                      '(not [?e ::sm/deleted-at])]
                              :in    ['user-id]}
                          (:uid session))]
    (cond->> raw-results
      :always         (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
      :always         (reverse)
      (not sensitive) (remove sensitive-key)
      (not archived)  (remove archived-key))))

(defn parse-field [[key-or-opts & more :as entry]]
  (let [has-opts (map? (second entry))
        k       (if has-opts key-or-opts (first entry))
        opts      (if has-opts (second entry) {})
        type      (if has-opts (nth entry 2) (second entry))]
    {:field-key k
     :opts opts
     :type type}))

(defn parse-field-key [{:keys [field-key]}]
  (let [n (-> field-key str rest str/join (str/replace "/" "-"))
        l (-> field-key
              name
              (str/split #"-")
              (->> (map str/capitalize))
              (->> (str/join " ")))]
    (pot/map-of n l)))

(defn string-field [{:keys [field-key] :as args}]
  (let [{:keys [n l]} (parse-field-key args)]
    (cond
      (str/includes? n "label")
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900
        {:for n} l]
       [:div.mt-2
        [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:type "text" :name n :autocomplete "off"}]]]

      :else
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900
        {:for n} l]
       [:div.mt-2
        [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:name         n
          :rows         3
          :placeholder  "..."
          :autocomplete "off"}]]])))

(defn checkbox-field [{:keys [field-key] :as args}]
  (let [{:keys [n l]} (parse-field-key args)]
    [:div.flex.items-center
     [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
      {:type "checkbox" :name n :autocomplete "off"}]
     [:label.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]]))

(defn number-field [{:keys [field-key] :as args}]
  (let [{:keys [n l]} (parse-field-key args)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900
      {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" :step "any" :name n :autocomplete "off"}]]]))

(defn int-field [{:keys [field-key] :as args}]
  (let [{:keys [n l]} (parse-field-key args)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900
      {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" :step "1" :name n :autocomplete "off"}]]]))

(defn float-field [{:keys [field-key] :as args}]
  (let [{:keys [n l]} (parse-field-key args)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900
      {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" :step "0.001" :name n :autocomplete "off"}]]]))

(defn instant-field [{:keys [field-key current-time] :as args}]
  (let [{:keys [n l]} (parse-field-key args)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900
      {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "datetime-local"
        :name n
        :required true
        :value current-time}]]]))

(defn single-relation-field [{:keys [field-key related-entity-str]
                              :as   args}
                             ctx]
  (let [{:keys [n l]} (parse-field-key args)
        label-key     (keyword related-entity-str "label")
        id-key        (keyword related-entity-str "id")
        options       (->> (all-for-user-query
                            {:entity-type-str related-entity-str}
                            ctx)
                           (map (fn [e] {:id    (id-key e)
                                         :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900
      {:for n} l]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name n :required true}
      (for [{:keys [id label]} options]
        [:option {:value id} label])]]))

(defn many-relation-field
  "Renders a multiple select for a set of related entities."
  [{:keys [field-key related-entity-str] :as args} ctx]
  (let [{:keys [n l]} (parse-field-key args)
        label-key (keyword related-entity-str "label")
        id-key    (keyword related-entity-str "id")
        options   (->> (all-for-user-query {:entity-type-str related-entity-str} ctx)
                       (map (fn [e]
                              {:id    (id-key e)
                               :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900
      {:for n} l]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name n
       :multiple true} ; allow multiple selections
      (for [{:keys [id label]} options]
        [:option {:value id} label])]]))

(defn enum-field
  "Renders a select input for enum fields. Expects :enum-options in the descriptor."
  [{:keys [field-key enum-options]} ctx]
  (let [n (-> field-key str rest str/join (str/replace "/" "-"))
        l (-> n
              (str/split #"-")
              (->> (map str/capitalize))
              (str/join " "))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:div.mt-2
      [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:name n :required true}
       (for [opt enum-options]
         [:option {:value (name opt)} (name opt)])]]]))

(defn has-id-in-set [type]
  (and (vector? type)
       (= :set (first type))
       (let [elem (second type)]
         (and (keyword? elem)
              (= "id" (name elem))))))

(defn field-type-descriptor
  "Transforms a field definition into an abstract descriptor with an input type.
   If the type is an enum (e.g. [:enum :a :b :c]), returns :enum with :enum-options."
  [{:keys [field-key type opts]}]
  (if (and (vector? type) (= :enum (first type)))
    {:field-key   field-key
     :input-type  :enum
     :enum-options (vec (rest type))}
    (let [has-id-in-set? (and (vector? type)
                              (= :set (first type))
                              (let [elem (second type)]
                                (and (keyword? elem)
                                     (= "id" (name elem)))))
          is-keyword (keyword? type)
          is-id      (and is-keyword (= "id" (name type)))
          related-entity-str (cond
                               is-id          (-> type namespace)
                               has-id-in-set? (-> type second namespace))
          input-type (cond
                       has-id-in-set? :many-relationship
                       is-id         :single-relationship
                       :else         type)]
      {:field-key          field-key
       :input-type         input-type
       :related-entity-str related-entity-str})))

(def renderers
  {:string              (fn [{:keys [field-key]} _]
                          (string-field (pot/map-of field-key)))
   :boolean             (fn [{:keys [field-key]} _]
                          (checkbox-field (pot/map-of field-key)))
   :number              (fn [{:keys [field-key]} _]
                          (number-field (pot/map-of field-key)))
   :int                 (fn [{:keys [field-key]} _]
                          (int-field (pot/map-of field-key)))
   :float               (fn [{:keys [field-key]} _]
                          (float-field (pot/map-of field-key)))
   :instant             (fn [{:keys [field-key]} _]
                          (instant-field (pot/map-of field-key)))
   :single-relationship (fn [{:keys [field-key related-entity-str]} ctx]
                          (single-relation-field (pot/map-of field-key related-entity-str) ctx))
   :many-relationship   (fn [{:keys [field-key related-entity-str]} ctx]
                          (many-relation-field (pot/map-of field-key related-entity-str) ctx))
   :enum                (fn [descriptor ctx]
                          (enum-field descriptor ctx))})

(defn render-field-input [field ctx]
  (let [desc     (field-type-descriptor field)
        renderer (get renderers (:input-type desc))]
    (if renderer
      (renderer desc ctx)
      [:div "unsupported field type: " (pr-str desc)])))

(defn schema->form [schema ctx]
  (let [has-opts (map? (second schema))
        fields   (if has-opts (drop 2 schema) (rest schema))
        fields   (->> fields
                      (map parse-field)
                      ;; remove fields that aren't necessary for new forms
                      (remove (fn [{:keys [field-key]}]
                                (let [n (namespace field-key)]
                                  (or
                                   (= :xt/id field-key)
                                   (= "tech.jgood.gleanmo.schema" n)
                                   (= "tech.jgood.gleanmo.schema.meta" n))))))]
    (for [field fields]
      (render-field-input field ctx))))

(defn new-form [{:keys [entity-key
                        schema
                        plural-str
                        entity-name-str]}
                {:keys [session biff/db params]
                 :as   ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {}
                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900
                    (str "New " (str/capitalize entity-name-str))]
                   [:p.mt-1.text-sm.leading-6.text-gray-600
                    (str "Create a new " entity-name-str)]]
                  [:div.grid.grid-cols-1.gap-y-6
                   (doall (schema->form schema ctx))
                   [:button {:type "submit"} "Create"]])])])))

(defn gen-routes [{:keys [entity-key schema plural-str]}]
  (let [schema          (entity-key schema)
        entity-name-str (name entity-key)
        args            (pot/map-of entity-key
                                    schema
                                    plural-str
                                    entity-name-str)]
    ["/crud" {}
     ;; new is preppended because the trie based router can't distinguish between
     ;; /entity/new and /entity/:id
     ;; this could be fixed with a linear based router but I think this is a fine REST convention to break from
     [(str "/new/" entity-name-str) {:get (partial new-form args)}]]))
