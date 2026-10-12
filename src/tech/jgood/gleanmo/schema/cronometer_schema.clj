(ns tech.jgood.gleanmo.schema.cronometer-schema
  "Entities imported from Cronometer — see roadmap/056-cronometer-integration.md,
   Data Model. All four are written only by the sync
   (`tech.jgood.gleanmo.cronometer.sync`); the app never edits them, so none
   has CRUD routes.

   Ids are name-based UUIDs of `<user-id>/<kind>/<cronometer key>`
   (`cronometer.transform/doc-id`), so a re-sync addresses the same documents
   without a lookup.

   Every measured value has a sibling `-unit` enum holding the unit Cronometer
   gave. Nutrient maps are the stated exception: their units come from
   `cronometer.nutrients`."
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def ^:private lineage
  "Cronometer lineage carried by every imported entity. The raw day, time and
   offset make a time-zone correction a recompute, not a re-fetch."
  [[:cronometer/raw {:optional true, :hide true} :string]
   [:cronometer/day {:optional true} :string]
   [:cronometer/time {:optional true} :string]
   [:cronometer/offset {:optional true} :int]
   [:cronometer/synced-at {:optional true} :instant]])

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
          [:cronometer/measure-id :int]]]]
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
          [:cronometer/food-id :int]
          [:cronometer/ingredient-id :int]]]]
       [:cronometer/food-id :int]
       ;; Non-zero is the Cronometer user who owns a custom food or recipe.
       [:cronometer/owner {:optional true} :int]]
      (into lineage)))

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
       [:cronometer/serving-id :int]
       [:cronometer/food-id :int]
       [:cronometer/measure-id {:optional true} :int]
       [:cronometer/translation-id {:optional true} :int]
       [:cronometer/order {:optional true} :int]]
      (into lineage)))

(def biometric-log
  ;; Flat typed fields per supported metric (see `cronometer.biometrics`). A
  ;; reading of an unmapped metric gets only `label` and raw. Field names match
  ;; `symptom-log`'s vitals so the values are comparable.
  (-> [:map {:closed true}
       [:xt/id :biometric-log/id]
       [::sm/type [:enum :biometric-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:biometric-log/timestamp :instant]
       [:biometric-log/timestamp-basis {:optional true}
        [:enum :logged :wake-time :day-start]]
       ;; Cronometer metric name
       [:biometric-log/label :string]
       ;; "Oura"; absent = entered by hand
       [:biometric-log/source {:optional true} :string]
       [:biometric-log/weight {:optional true} :number]
       [:biometric-log/weight-unit {:optional true} [:enum :lbs :kg :st]]
       [:biometric-log/body-fat {:optional true} :number]
       [:biometric-log/body-fat-unit {:optional true} [:enum :percent :kg :lbs]]
       [:biometric-log/bp-systolic {:optional true} :number]
       [:biometric-log/bp-systolic-unit {:optional true} [:enum :mmhg]]
       [:biometric-log/bp-diastolic {:optional true} :number]
       [:biometric-log/bp-diastolic-unit {:optional true} [:enum :mmhg]]
       [:biometric-log/blood-glucose {:optional true} :number]
       [:biometric-log/blood-glucose-unit {:optional true} [:enum :mg-dl :mmol-l]]
       [:biometric-log/heart-rate {:optional true} :number]
       [:biometric-log/heart-rate-unit {:optional true} [:enum :bpm]]
       [:biometric-log/hrv {:optional true} :number]
       [:biometric-log/hrv-unit {:optional true} [:enum :ms]]
       [:biometric-log/respiration-rate {:optional true} :number]
       [:biometric-log/respiration-rate-unit {:optional true} [:enum :brpm]]
       [:biometric-log/sleep {:optional true} :number]
       [:biometric-log/sleep-unit {:optional true} [:enum :hours]]
       [:biometric-log/sleep-score {:optional true} :number]
       [:biometric-log/sleep-score-unit {:optional true} [:enum :percent]]
       [:biometric-log/recovery {:optional true} :number]
       [:biometric-log/recovery-unit {:optional true} [:enum :percent]]
       [:biometric-log/temp-variation {:optional true} :number]
       [:biometric-log/temp-variation-unit {:optional true}
        [:enum :celsius :fahrenheit :kelvin]]
       [:cronometer/biometric-id :int]
       [:cronometer/metric-id :int]
       [:cronometer/unit-id {:optional true} :int]
       [:cronometer/external-id {:optional true} :string]]
      (into lineage)))

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
      (into lineage)))

(def cronometer-connection
  ;; One per user: the Cronometer email and password, encrypted together with
  ;; AES-256-GCM under `:gleanmo/credentials-key` (see `tech.jgood.gleanmo.crypto`).
  ;; Nothing here is plaintext. Disconnecting or changing the password evicts
  ;; the document, because XTDB keeps every version of it.
  ;;
  ;; Reachable from nowhere but the Cronometer status page and the sync: it is
  ;; in no API group and not in `crud-entities`.
  [:map {:closed true}
   [:xt/id :cronometer-connection/id]
   [::sm/type [:enum :cronometer-connection]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   ;; base64 AES-GCM ciphertext of the EDN `{:email :password}`
   [:cronometer-connection/ciphertext :string]
   ;; base64 random 12-byte nonce
   [:cronometer-connection/nonce :string]
   [:cronometer-connection/key-version :int]])
