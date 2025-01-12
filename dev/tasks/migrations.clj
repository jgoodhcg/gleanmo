(ns tasks.migrations
  (:require [migratus.core :as migratus]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]))

(defn replace-env-vars
  "Replaces placeholders in the format ${ENV_VAR} with corresponding environment variable values.
   Throws an error if an environment variable is missing."
  [input-str]
  (str/replace input-str
               #"\$\{(\w+)\}"
               (fn [[_ var]]
                 (let [value (System/getenv var)]
                   (if value
                     value
                     (throw (ex-info (str "Environment variable not set: " var)
                                     {:missing-var var})))))))

(defn load-config
  "Load Migratus configuration, replacing environment variable placeholders with actual values."
  []
  (let [raw-config (-> "migratus.edn" io/resource slurp edn/read-string)]
    (update raw-config :db
            (fn [db-config]
              (-> db-config
                  (update :dbname   replace-env-vars)
                  (update :host     replace-env-vars)
                  (update :port     replace-env-vars)
                  (update :user     replace-env-vars)
                  (update :password replace-env-vars))))))

(defn migrate
  "Manage database migrations.

  Usage:
    clj -M:dev migrate                  ; Apply all pending migrations
    clj -M:dev migrate rollback         ; Rollback the last migration
    clj -M:dev migrate reset            ; Down all migrations then up them all back
    clj -M:dev migrate create <name>    ; Create a new up/down migration with <name>"
  [& args]
  (let [command (first args)
        config  (load-config)]
    (case command
      "rollback"
      (do
        (println "Rolling back the last migration...")
        (migratus/rollback config)
        (println "Rollback complete."))

      "create"
      (let [name (second args)]
        (if (nil? name)
          (println "Error: You must provide a name for the migration.")
          (migratus/create config name)))

      "reset"
      (migratus/reset config)

      ;; Default action: run all pending migrations
      (do
        (println "Running migrations...")
        (migratus/migrate config)
        (println "Migrations complete.")))))
