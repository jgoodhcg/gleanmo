(ns tech.jgood.gleanmo.crud
  (:require
   [clojure.pprint :refer [pprint]]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [clojure.string :as str]
   [xtdb.api :as xt]
   [tech.jgood.gleanmo.ui :as ui]
   [clojure.string :as str]))

(defn parse-field [[key-or-opts & more :as entry]]
  (let [has-opts (map? (second entry))
        k       (if has-opts key-or-opts (first entry))
        opts      (if has-opts (second entry) {})
        type      (if has-opts (nth entry 2) (second entry))]
    {:field-key k
     :opts opts
     :type type}))

(defn string-field [{:keys [field-key]}]
  (let [n (-> field-key str rest str/join (str/replace "/" "-"))
        l (-> n (str/split #"-") (->> (map str/capitalize)) (->> (str/join " ")))]
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

(defn field->input [{:keys [field-key type opts]}]
  (case type
    :uuid    nil
    :string  (string-field (pot/map-of field-key))
    :boolean [:input {:type "checkbox" :name (name field-key)}]
    :number  [:input {:type "number" :step "any" :name (name field-key)}]
    :int     [:input {:type "number" :step "1" :name (name field-key)}]
    :float   [:input {:type "number" :step "0.001" :name (name field-key)}]
    :instant [:input {:type "datetime-local" :name (name field-key)}]
    ;; handle special references, sets, enums, etc.
    (str [:div (str "unsupported type: " type)])))

(comment
  (field->input
   (parse-field
    (nth '([:xt/id :uuid]
           [:tech.jgood.gleanmo.schema/type [:enum :cruddy]]
           [:cruddy/name :string]
           [:cruddy/num :number]
           [:cruddy/bool :boolean]
           [:cruddy/integer :int]
           [:cruddy/single-relation :habit/id]
           [:cruddy/set-relation [:set :habit/id]]
           [:cruddy/enum [:enum :a :b :c]]
           [:cruddy/timestamp :instant]
           [:cruddy/float {:optional true} :float])
         (rand-int 10)))
   )

  (-> :cruddy/name str rest str/join (str/replace "/" "-"))
  ;;
  )

(defn schema->form [schema]
  (let [has-opts (map? (second schema))
        fields   (if has-opts (drop 2 schema) (rest schema))
        fields   (->> fields
                      (map parse-field)
                      ;; remove schema fields that aren't necessary for new forms
                      (remove (fn [{:keys [field-key]}]
                                (let [n (namespace field-key)]
                                  (= "tech.jgood.gleanmo.schema" n)))))]
    (for [field fields]
      (field->input field))))

(comment
  (schema->form [:map
                 {:closed true}
                 [:xt/id :uuid]
                 [:tech.jgood.gleanmo.schema/type [:enum :cruddy]]
                 [:cruddy/name :string]
                 [:cruddy/num :number]
                 [:cruddy/bool :boolean]
                 [:cruddy/integer :int]
                 [:cruddy/single-relation :habit/id]
                 [:cruddy/set-relation [:set :habit/id]]
                 [:cruddy/enum [:enum :a :b :c]]
                 [:cruddy/timestamp :instant]
                 [:cruddy/float {:optional true} :float]])

  ;;
  )

(defn new-form [{:keys [entity-key
                        schema
                        plural-str
                        entity-name-str]}
                {:keys [session biff/db]}]
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
                  (doall (schema->form schema))
                  [:button {:type "submit"} "Create"]])]
                )])))

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
