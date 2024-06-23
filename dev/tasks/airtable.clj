(ns tasks.airtable
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

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
  (let [opts (parse-opts
              args
              [["-k" "--api-key API-KEY" "API Key" :required true]
               ["-b" "--base-id BASE-ID" "Base ID" :required true]
               ["-t" "--table-id TABLE-ID" "Table ID" :required true]])
        options (opts :options)
        file-name (str "airtable_data/"
                       (-> (options :table-id) (str/replace "-" "_"))
                       "_"
                       (timestamp)
                       ".edn")]
    (with-open [writer (io/writer file-name)]
      #_(.write writer "[\n")
      (get-all-records options writer)
      #_(.write writer "]"))
    (println (str "Done, written to: " file-name))))
