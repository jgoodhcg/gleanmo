(ns tasks.airtable
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [potpuri.core :as pot])
  (:import (java.net URLEncoder)))

(def table-ids {:activity     "tbleuZF29AmzFiWUw"
                :activity-log "tblmfUTKwMIGQnNLC"})

(defn get-page [url api-key]
  (println "  GET" url)
  (println "  Auth: Bearer" (str (subs api-key 0 (min 8 (count api-key))) "..."))
  (try
    (let [response (client/get
                    url
                    {:headers       {"Authorization" (str "Bearer " api-key)}
                     :cookie-policy :none})]
      (println "  Status:" (:status response))
      (-> response :body (json/parse-string)))
    (catch Exception e
      (let [data (ex-data e)]
        (println "  ERROR Status:" (:status data))
        (println "  ERROR Body:" (some-> data :body (subs 0 (min 500 (count (:body data))))))
        (throw e)))))

(defn get-all-records [{:keys [api-key base-id table-id table-name]} writer]
  (let [table-path (or table-id (some-> table-name (URLEncoder/encode "UTF-8")))
        url        (str "https://api.airtable.com/v0/" base-id "/" table-path)]
    (println "  Table path:" table-path)
    (println "  Full URL:" url)
    (loop [response (get-page url api-key)
           page-num 1]
      (println (str "Getting page: " page-num))
      (let [offset      (get response "offset")
            new-records (get response "records")]
        (doseq [record new-records]
          (.write writer (pr-str record))
          (.write writer "\n"))
        (if (nil? offset)
          nil
          (recur (get-page (str url "?offset=" offset) api-key) (-> page-num (+ 1))))))))

(defn timestamp []
  (-> (java.time.LocalDateTime/now)
      str
      (str/replace "-" "_")
      (str/replace "T" "_")
      (str/replace ":" "_")
      (str/replace "." "_")))

(defn download-all-records
  "Downloads all records from specified table"
  [& args]
  (let [opts       (parse-opts
                    args
                    [["-k" "--api-key API-KEY" "API Key" :required true]
                     ["-b" "--base-id BASE-ID" "Base ID" :required true]
                     ["-t" "--table-id TABLE-ID" "Table ID"]
                     ["-n" "--table-name TABLE-NAME" "Table Name"]])
        options    (opts :options)
        table-name (-> options :table-name)
        table-id   (-> options :table-id)
        table      (or table-id
                       table-name)
        table-id   (or table-id
                       (->> table-name keyword (get table-ids)))
        file-name  (str "airtable_data/"
                        (-> table (str/replace "-" "_"))
                        "_"
                        (timestamp)
                        ".edn")
        options    (cond-> options
                     table-id   (assoc :table-id table-id)
                     (and (nil? table-id) table-name) (assoc :table-name table-name))]
    (with-open [writer (io/writer file-name)]
      #_(.write writer "[\n")
      (get-all-records options writer)
      #_(.write writer "]"))
    (println (str "Done, written to: " file-name))))
