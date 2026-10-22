(ns tasks.cronometer-recipes
  "Standalone recipe exports. Reads Cronometer; never opens an XTDB node."
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.cronometer.nutrients :as nutrients])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

(defn- fetch-all
  [conn ids]
  (loop [foods {}, pending (set ids)]
    (if (empty? pending)
      foods
      (let [batch (vec (take 50 (sort pending)))
            fetched (client/get-foods conn batch)
            by-id (into {} (map (juxt :id identity)) fetched)
            missing (remove #(contains? by-id %) batch)]
        (when (seq missing)
          (throw (ex-info "Cronometer omitted requested foods; export stopped"
                          {:missing-food-ids (vec missing)})))
        (let [foods (merge foods (select-keys by-id batch))
              ingredients (mapcat :ingredients (vals (select-keys by-id batch)))
              ingredient-ids (map :foodId ingredients)]
          (when-not (every? integer? ingredient-ids)
            (throw (ex-info "Recipe contains an ingredient without a food ID" {})))
          (recur foods
                 (into (set (remove #(contains? foods %) pending))
                       (remove #(contains? foods %) ingredient-ids))))))))

(defn- recipe?
  [food]
  (or (seq (:ingredients food))
      (some #(= "Recipe" (:type %)) (:measures food))))

(defn collect
  "Collect custom recipes and all ingredient foods without changing units.
   `expected-count`, when supplied, must match the discovered recipe count.
   Catalog discovery uses an unverified Custom-tab search, not diary history."
  [conn expected-count]
  (let [summaries (client/find-custom-foods conn)
        foods (fetch-all conn (map :id summaries))
        owner (:user-id ((get-in conn [:store :load])))
        _ (when-not (integer? owner)
            (throw (ex-info "Cronometer session has no account ID" {})))
        recipes (->> summaries
                     (map #(get foods (:id %)))
                     (filter #(and (= owner (:owner %)) (recipe? %)))
                     (reduce #(assoc %1 (:id %2) %2) {})
                     vals (sort-by :id) vec)
        ;; Exclude unrelated custom foods returned by the search from the
        ;; artifact, keeping only the recipe graph reachable from the roots.
        reachable (loop [ids #{}, pending (set (map :id recipes))]
                    (if (empty? pending)
                      ids
                      (let [ids (into ids pending)
                            more (mapcat #(map :foodId (:ingredients (get foods %)))
                                         pending)]
                        (recur ids (set (remove ids more))))))]
    (when (and (some? expected-count) (not= expected-count (count recipes)))
      (throw (ex-info "Recipe count differs from --expected-count; export stopped"
                      {:expected expected-count, :actual (count recipes)})))
    (when (and (empty? recipes) (not= 0 expected-count))
      (throw (ex-info "No custom recipes found; verify the Custom-tab search request" {})))
    {:format-version 1
     :exported-at (str (Instant/now))
     :discovery {:method "find_food CUSTOM with empty query"
                 :live-verified false
                 :coverage (if (some? expected-count) "count-matched" "unverified")
                 :expected-count expected-count
                 :recipe-count (count recipes)}
     :recipes recipes
     :nutrient-definitions nutrients/by-id
     :foods (mapv foods (sort reachable))}))

(defn ingredient-rows
  "CSV rows for direct ingredient edges, preserving upstream amounts and IDs.
   Nested recipes have their own rows; no flattening or unit conversion occurs."
  [{:keys [foods]}]
  (let [by-id (into {} (map (juxt :id identity)) foods)]
    (into [["recipe_id" "recipe_name" "ingredient_index" "ingredient_id"
            "food_id" "food_name" "measure_id" "measure_name" "measure_type"
            "measure_amount" "measure_value" "grams_raw" "value_raw"]]
          (for [recipe foods
                [index ingredient] (map-indexed vector (:ingredients recipe))
                :let [food (get by-id (:foodId ingredient))
                      measure (some #(when (= (:id %) (:measureId ingredient)) %)
                                    (:measures food))]]
            [(:id recipe) (:name recipe) index (:id ingredient)
             (:foodId ingredient) (:name food) (:measureId ingredient)
             (:name measure) (:type measure) (:amount measure) (:value measure)
             (:grams ingredient) (:value ingredient)]))))

(defn write-export!
  "Write JSON and ingredient CSV in a new directory under `cronometer_data`.
   Each run gets a unique directory, so existing exports are never overwritten."
  [bundle]
  (let [parent (io/file "cronometer_data")]
    (.mkdirs parent)
    (let [dir (.toFile (Files/createTempDirectory
                        (.toPath parent) "recipes-" (make-array FileAttribute 0)))]
      (spit (io/file dir "recipes.json") (json/generate-string bundle {:pretty true}))
      (with-open [writer (io/writer (io/file dir "ingredients.csv"))]
        (csv/write-csv writer (ingredient-rows bundle)))
      (.getPath dir))))
