(ns tech.jgood.gleanmo.schema.nutrition-schema
  "Foods, servings and per-day nutrition — see
   roadmap/056-cronometer-integration.md, Data Model.

   Provider-neutral: any source that logs food translates into these entities.
   Each provider adds its own optional namespaced attributes (`:cronometer/*`
   today) for identity and lineage; none is required, so a document from one
   provider never has to carry another's ids. No entity here has CRUD routes
   yet — imports write them and the app does not edit them.

   Nutrient maps are the stated exception to the sibling `-unit` rule: their
   units come from the nutrient table (`cronometer.nutrients`)."
  (:require
   [tech.jgood.gleanmo.schema.cronometer-schema :as crono]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def nutrient-map
  "Nutrient key (`cronometer.nutrients`) -> amount in that nutrient's unit."
  [:map-of :keyword :number])

(def food
  (-> [:map {:closed true}
       [:xt/id :food/id]
       [::sm/type [:enum :food]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:food/label :string]
       [:food/kind [:enum :food :recipe]]
       ;; "NCCDB:7772", "CRDB", "Custom"
       [:food/source {:optional true} :string]
       ;; Cronometer foodTags verbatim. Custom recipes carry none, so recipe
       ;; compliance comes from the ingredients' own food docs.
       [:food/tags {:optional true} [:set :string]]
       ;; Per 100 g for :food, per one reference serving for :recipe.
       [:food/nutrients {:optional true} nutrient-map]
       [:food/measures {:optional true}
        [:vector
         [:map {:closed true}
          [:label :string]
          [:type :string]
          [:amount :number]
          [:value :number]
          [:cronometer/measure-id {:optional true} :int]]]]
       ;; Recipes only. Embedded rather than an entity: an ingredient has no
       ;; life outside its recipe, and XTDB history keeps the list as it stood
       ;; when an old serving was eaten.
       [:food/ingredients {:optional true}
        [:vector
         [:map {:closed true}
          [:food-id :food/id]
          [:grams :number]
          [:portion-label {:optional true} :string]
          [:portion-quantity {:optional true} :number]
          [:cronometer/food-id {:optional true} :int]
          [:cronometer/ingredient-id {:optional true} :int]]]]
       [:cronometer/food-id {:optional true} :int]
       ;; Non-zero is the Cronometer user who owns a custom food or recipe.
       [:cronometer/owner {:optional true} :int]]
      (into crono/lineage)))

(def food-log
  (-> [:map {:closed true}
       [:xt/id :food-log/id]
       [::sm/type [:enum :food-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:food-log/timestamp :instant]
       [:food-log/timestamp-basis {:optional true} [:enum :logged :day-start]]
       [:food-log/food-id :food/id]
       ;; A copy of the food's label, so an agent reading logs needs no join.
       [:food-log/label {:optional true} :string]
       ;; For a recipe serving this is a serving count, not grams.
       [:food-log/grams {:optional true} :number]
       [:food-log/portion-label {:optional true} :string]
       [:food-log/portion-quantity {:optional true} :number]
       ;; Scaled to this serving.
       [:food-log/nutrients {:optional true} nutrient-map]
       [:cronometer/serving-id {:optional true} :int]
       [:cronometer/food-id {:optional true} :int]
       [:cronometer/measure-id {:optional true} :int]
       [:cronometer/translation-id {:optional true} :int]
       [:cronometer/order {:optional true} :int]]
      (into crono/lineage)))

(def nutrition-day
  ;; Cronometer's own per-day figures. The nested values are EDN strings
  ;; because nothing reads them yet; promote a field when something does.
  (-> [:map {:closed true}
       [:xt/id :nutrition-day/id]
       [::sm/type [:enum :nutrition-day]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       ;; The one entity where a day, not an instant, is the honest key.
       [:nutrition-day/day :local-date]
       ;; diary info.complete
       [:nutrition-day/completed {:optional true} :boolean]
       ;; diary `summary`: consumed, burned, macros
       [:nutrition-day/summary {:optional true} :string]
       ;; macro target template
       [:nutrition-day/template {:optional true} :string]
       [:nutrition-day/fasts {:optional true} :string]
       ;; diary Exercise entries (Oura activity, Google Health): calorie
       ;; estimates, not workouts
       [:nutrition-day/exercises {:optional true} :string]]
      (into crono/lineage)))
