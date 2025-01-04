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
        key       (if has-opts key-or-opts (first entry))
        opts      (if has-opts (second entry) {})
        type      (if has-opts (nth entry 2) (second entry))]
    {:key key
     :opts opts
     :type type}))

(defn string-field [{:keys [name placeholder]}]
  [:div
   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for name} (str/capitalize name)]
   [:div.mt-2
    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
     {:name         name
      :rows         3
      :placeholder  (or placeholder "...")
      :autocomplete "off"}]]])

(defn field->input [{:keys [key type opts]}]
  (case type
    :string  (string-field {:name (name key) :placeholder nil})
    :boolean [:input {:type "checkbox" :name (name key)}]
    :number  [:input {:type "number" :step "any" :name (name key)}]
    :int     [:input {:type "number" :step "1" :name (name key)}]
    :float   [:input {:type "number" :step "0.001" :name (name key)}]
    :instant [:input {:type "datetime-local" :name (name key)}]
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
         (rand-int 10))))
  ;;
  )

(defn schema->form [schema]
  (let [has-opts (map? (second schema))
        fields   (if has-opts (drop 2 schema) (rest schema))]
    (for [field fields]
      (field->input (parse-field field)))))

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
                [:div.flex.flex-col.md:flex-row.justify-center
                 [:h1.text-3xl.font-bold plural-str]]
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {}
                  [:div.flex.flex-col
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
