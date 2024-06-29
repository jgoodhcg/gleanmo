(ns tasks.airtable
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [potpuri.core :as pot]))

(def table-ids {:activity     "tbleuZF29AmzFiWUw"
                :activity-log "tblmfUTKwMIGQnNLC"})

(defn get-page [url api-key]
  (-> (client/get
       url
       {:headers       {"Authorization" (str "Bearer " api-key)}
        :cookie-policy :none})
      (:body)
      (json/parse-string)))

(defn get-all-records [{:keys [api-key base-id table-id]} writer]
  (let [url (str "https://api.airtable.com/v0/" base-id "/" table-id)]
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
                       (-> table-name keyword (get table-ids)))
        file-name  (str "airtable_data/"
                        (-> table (str/replace "-" "_"))
                        "_"
                        (timestamp)
                        ".edn")
        options    (merge options (pot/map-of table-id))]
    (println (pot/map-of table table-id table-name))
    (with-open [writer (io/writer file-name)]
      #_(.write writer "[\n")
      (get-all-records options writer)
      #_(.write writer "]"))
    (println (str "Done, written to: " file-name))))
