(ns tech.jgood.gleanmo.airtable-extract
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [babashka.cli :as cli]
            [clojure.string :refer [replace]]))

(defn get-page [url api-key]
  (-> (curl/get
       url
       {:headers {"Authorization" (str "Bearer " api-key)}})
      (:body)
      (json/parse-string)))

(defn get-all-records [{:keys [api-key base-id table-id]} writer]
  (let [url (str "https://api.airtable.com/v0/" base-id "/" table-id)]
    (loop [response (get-page url api-key)]
      (let [offset      (-> response (get "offset"))
            new-records (-> response (get "records"))]
        (doseq [record new-records]
          (.write writer (pr-str record))
          (.write writer "\n"))
        (if (nil? offset)
          nil
          (recur (get-page (str url "?offset=" offset) api-key)))))))

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
                       :t :table-id}})
      file-name (str (timestamp) "_"
                     (-> opts :table-id (replace "-" "_"))
                     ".edn")]
  (with-open [writer (io/writer file-name)]
    #_(.write writer "[\n")
    (get-all-records opts writer)
    #_(.write writer "]")))
