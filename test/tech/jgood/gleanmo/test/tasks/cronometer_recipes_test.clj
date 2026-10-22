(ns tech.jgood.gleanmo.test.tasks.cronometer-recipes-test
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [tasks.cronometer :as task]
   [tasks.cronometer-recipes :as recipes]
   [tech.jgood.gleanmo.cronometer.client :as client]))

(def ^:private catalog
  {1 {:id 1, :owner 7, :name "Unused, \"recipe\"", :comments "Keep these notes"
      :ingredients [{:id 11, :foodId 2, :measureId 20, :grams 0.5, :value 1}]
      :nutrients [{:id 208, :amount 123.45}]}
   2 {:id 2, :owner 7, :name "Nested recipe"
      :measures [{:id 20, :name "batch", :type "Recipe", :amount 1, :value 1}]
      :ingredients [{:id 12, :foodId 3, :measureId 30, :grams 25}]}
   3 {:id 3, :owner 0, :name "Ingredient"
      :measures [{:id 30, :name "g", :type "Weight", :amount 1, :value 1}]}
   4 {:id 4, :owner 7, :name "Plain custom food"}
   5 {:id 5, :owner 8, :name "Shared recipe", :ingredients [{:foodId 3}]}})

(defn- connection
  []
  (client/connection {:store {:load (constantly {:user-id 7, :token "synthetic"})}}))

(deftest request-and-export-test
  (let [calls (atom [])
        conn (connection)]
    (with-redefs [http/post
                  (fn [url options]
                    (let [body (json/parse-string (:body options) true)]
                      (swap! calls conj [url body])
                      {:status 200
                       :body (json/generate-string
                              {:foods (if (.endsWith url "find_food")
                                        [{:id 1} {:id 1} {:id 4} {:id 5}]
                                        (mapv catalog (:ids body)))})}))]
      (let [bundle (recipes/collect conn 1)]
        (is (= [1] (mapv :id (:recipes bundle))))
        (is (= [1 2 3] (mapv :id (:foods bundle))))
        (is (= (catalog 1) (first (:recipes bundle))))
        (is (= "count-matched" (get-in bundle [:discovery :coverage])))
        (is (false? (get-in bundle [:discovery :live-verified])))
        (is (= :kcal (get-in bundle [:nutrient-definitions 208 :unit])))
        (is (= 0.5 (get-in (recipes/ingredient-rows bundle) [1 11])))
        (is (= "Recipe" (get-in (recipes/ingredient-rows bundle) [1 8])))
        (is (= "Keep these notes" (get-in bundle [:recipes 0 :comments]))))
      (let [[url payload] (first @calls)]
        (is (.endsWith url "/api/v2/find_food"))
        (is (= "CUSTOM" (:tab payload)))
        (is (= "" (:query payload)))
        (is (= ["All"] (:sources payload)))
        (is (every? #(= "synthetic" (get-in % [1 :auth :token])) @calls))
        (is (every? #(or (.endsWith (first %) "find_food")
                         (.endsWith (first %) "get_foods")) @calls))))))

(deftest incomplete-export-test
  (with-redefs [client/find-custom-foods (constantly [{:id 1}])
                client/get-foods (fn [_ ids] (mapv catalog ids))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected-count"
                          (recipes/collect (connection) 2)))
    (is (= "unverified" (get-in (recipes/collect (connection) nil)
                                [:discovery :coverage]))))
  (testing "missing nested foods stop the export"
    (with-redefs [client/find-custom-foods (constantly [{:id 1}])
                  client/get-foods (fn [_ ids] (keep #(when (= 1 %) (catalog %)) ids))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"omitted"
                            (recipes/collect (connection) nil)))))
  (testing "empty discovery cannot silently succeed"
    (with-redefs [client/find-custom-foods (constantly [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No custom recipes"
                            (recipes/collect (connection) nil)))
      (is (empty? (:recipes (recipes/collect (connection) 0)))))))

(deftest batching-and-cycles-test
  (let [ids (range 1 103)
        calls (atom [])]
    (with-redefs [client/find-custom-foods (constantly (mapv #(hash-map :id %) ids))
                  client/get-foods
                  (fn [_ batch]
                    (swap! calls conj batch)
                    (mapv (fn [id] {:id id, :owner 7, :name (str "Recipe " id)
                                    :ingredients [{:foodId (if (= 102 id) 1 (inc id))}]})
                          batch))]
      (is (= 102 (count (:recipes (recipes/collect (connection) 102))))))
    (is (= [50 50 2] (mapv count @calls)))
    (is (= (set ids) (set (mapcat identity @calls))))))

(deftest malformed-search-test
  (doseq [body [{} {:foods nil} {:foods [{:name "Missing ID"}]}]]
    (with-redefs [http/post (fn [& _] {:status 200, :body (json/generate-string body)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unexpected response"
                            (client/find-custom-foods (connection)))))))

(deftest files-test
  (let [bundle {:recipes [(catalog 1)], :foods (mapv catalog [1 2 3])}
        paths (atom [])]
    (try
      (dotimes [_ 2] (swap! paths conj (recipes/write-export! bundle)))
      (is (apply distinct? @paths))
      (doseq [path @paths]
        (is (= "Keep these notes"
               (get-in (json/parse-string (slurp (io/file path "recipes.json")) true)
                       [:recipes 0 :comments])))
        (with-open [reader (io/reader (io/file path "ingredients.csv"))]
          (let [rows (doall (csv/read-csv reader))]
            (is (= 3 (count rows)))
            (is (= "Unused, \"recipe\"" (get-in (vec rows) [1 1])))
            (is (= "0.5" (get-in (vec rows) [1 11]))))))
      (finally
        (doseq [path @paths]
          (doseq [file ["recipes.json" "ingredients.csv"]]
            (io/delete-file (io/file path file)))
          (io/delete-file (io/file path)))))))

(deftest cli-validation-test
  (with-redefs [client/find-custom-foods (fn [& _] (throw (Exception. "Unexpected network")))]
    (is (re-find #"Usage:" (with-out-str (task/export-recipes "--help"))))
    (doseq [args [["--expected-count" "-1"] ["--expected-count" "bad"] ["extra"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid recipe export arguments"
                            (apply task/export-recipes args))))))
