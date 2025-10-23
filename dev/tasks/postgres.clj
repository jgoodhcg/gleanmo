(ns tasks.postgres
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-config
  {:host "localhost"
   :port 5432
   :db "gleanmo_dev"
   :user "gleanmo_app"
   :password "gleanmo_dev_pw"})

(defn- env-config []
  {:host (or (System/getenv "POSTGRES_HOST") (:host default-config))
   :port (-> (or (System/getenv "POSTGRES_PORT")
                 (str (:port default-config)))
             (Integer/parseInt))
   :db (or (System/getenv "POSTGRES_DB") (:db default-config))
   :user (or (System/getenv "POSTGRES_USER") (:user default-config))
   :password (or (System/getenv "POSTGRES_PASSWORD")
                 (:password default-config))
   :sslmode (System/getenv "POSTGRES_SSLMODE")})

(defn- psql-cmd [{:keys [host port db user password sslmode]} sql-path]
  (let [command ["psql"
                 "-h" host
                 "-p" (str port)
                 "-U" user
                 "-d" db
                 "-v" "ON_ERROR_STOP=1"
                 "-f" sql-path]
        process-builder (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String command))
        env (.environment process-builder)]
    (when (seq password)
      (.put env "PGPASSWORD" password))
    (when (seq sslmode)
      (.put env "PGSSLMODE" sslmode))
    (.redirectErrorStream process-builder false)
    (let [process (.start process-builder)
          stdout-fut (future
                       (with-open [r (io/reader (.getInputStream process))]
                         (slurp r)))
          stderr-fut (future
                       (with-open [r (io/reader (.getErrorStream process))]
                         (slurp r)))
          exit-code (.waitFor process)
          stdout @stdout-fut
          stderr @stderr-fut]
      (when-not (zero? exit-code)
        (throw (ex-info (format "psql failed for %s" sql-path)
                        {:command command
                         :stdout stdout
                         :stderr stderr
                         :exit exit-code})))
      {:stdout stdout :stderr stderr :exit exit-code})))

(defn apply-migrations
  "Apply SQL migrations using psql.

  Usage:
    clj -M:dev migrate-postgres
    clj -M:dev migrate-postgres 005_add_index.sql"
  [& [target]]
  (let [cfg (env-config)
        migrations (->> (file-seq (io/file "resources/migrations"))
                        (filter #(.isFile ^java.io.File %))
                        (filter #(str/ends-with? (.getName ^java.io.File %) ".sql"))
                        (sort-by #(.getName ^java.io.File %)))
        selection (if target
                    (filter #(= (.getName ^java.io.File %) target) migrations)
                    migrations)]
    (cond
      (and target (empty? selection))
      (println "No migration matching" target "found in resources/migrations.")

      (seq selection)
      (do
        (println "Applying migrations with configuration:" (select-keys cfg [:host :port :db :user]))
        (doseq [file selection]
          (let [path (.getAbsolutePath ^java.io.File file)]
            (println "->" (.getName ^java.io.File file))
            (psql-cmd cfg path)))
        (println "Migrations applied."))

      :else
      (println "No migrations found in resources/migrations."))))
