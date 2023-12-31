(ns tech.jgood.gleanmo.airtable-extract
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.set :refer [rename-keys]]
            [clojure.pprint :refer [pprint]]
            [babashka.cli :as cli]
            [clojure.string :refer [replace]]
            ))

(defn get-all-records [{:keys [api-key base-id table-id]}]
  (let [url      (str "https://api.airtable.com/v0/" base-id "/" table-id)]
    (loop [response (-> (curl/get
                         url
                         {:headers {"Authorization" (str "Bearer " api-key)}})
                        (:body)
                        (json/parse-string))
           records []]
      (let [offset (-> response (get "offset"))]
        (if (nil? offset)
          (concat records (-> response (get "records")))
          (recur
           (-> (curl/get
                (str url "?offset=" offset)
                {:headers {"Authorization" (str "Bearer " api-key)}})
               (:body)
               (json/parse-string))
           (concat records (-> response (get "records")))))))))

(defn timestamp []
  (-> (java.time.LocalDateTime/now)
      str
      (replace "T" "__")
      (replace ":" "_")
      (replace "." "_")))

(let [opts (cli/parse-opts
            *command-line-args*
            {:require [:api-key :base-id :table-id]
             :alias   {:k :api-key
                       :b :base-id
                       :t :table-id}})]

  (-> (get-all-records opts)
    (#(with-out-str (pprint %)))
    (->> (spit (str (timestamp) "_"
                    (-> opts :table-id (replace "-" "_"))
                    ".edn")))))
