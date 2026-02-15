(ns tasks.migrate
  "Runner for data migrations. Boots XTDB node, runs migration, shuts down.

   Usage:
     clj -M:dev migrate m001-airtable-import-medications \\
       --file airtable_data/medication_log.edn \\
       --target dev \\
       --email user@example.com"
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [com.biffweb :as biff]
   [tasks.migrations.m001-airtable-import-medications :as m001]
   [tasks.util :as u]
   [tech.jgood.gleanmo :as main]))

;; =============================================================================
;; CLI Options
;; =============================================================================

(def cli-options
  [["-f" "--file FILE" "Path to the EDN data file"]
   ["-p" "--mapping-file FILE" "Path to EDN overrides map for label reconciliation"]
   ["-t" "--target TARGET" "Target database: dev or prod"
    :default "dev"]
   ["-e" "--email EMAIL" "Email of the user to associate records with"]
   ["-d" "--dry-run" "Generate artifacts only, skip database writes"]
   ["-h" "--help"]])

;; =============================================================================
;; Node Lifecycle
;; =============================================================================

(defn start-node
  "Start an XTDB node for the given target."
  [target]
  (case target
    "dev"  (biff/start-node {:topology :standalone
                             :dir      "storage/xtdb"})
    "prod" (let [config   (biff/use-aero-config {:biff.config/profile "prod"})
                 jdbc-url ((:biff.xtdb.jdbc/jdbcUrl config))]
             (biff/start-node {:topology  :jdbc
                               :jdbc-spec {:jdbcUrl jdbc-url}
                               :dir       "prod-storage/"}))))

(defn build-ctx
  "Build a minimal Biff context from an XTDB node."
  [node]
  (biff/assoc-db {:biff.xtdb/node  node
                  :biff/malli-opts #'main/malli-opts}))

;; =============================================================================
;; Migration Registry
;; =============================================================================

(def registry
  "Map of migration name to run fn. Populated as migrations are added."
  {"m001-airtable-import-medications" m001/run})

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn run
  "Run a migration by name.

   First arg is the migration name, remaining args are migration options."
  [& args]
  (let [migration-name             (first args)
        {:keys [options errors summary]} (parse-opts (rest args) cli-options)
        migration-fn               (get registry migration-name)]

    (when (or (:help options) (nil? migration-name) (#{"--help" "-h"} migration-name))
      (println "Usage: clj -M:dev migrate <migration-name> [options]")
      (println)
      (println summary)
      (println)
      (println "Available migrations:")
      (if (seq registry)
        (doseq [k (sort (keys registry))]
          (println (str "  " k)))
        (println "  (none registered yet)"))
      (System/exit 0))

    (when errors
      (doseq [e errors]
        (u/print-red (str "ERROR: " e)))
      (System/exit 1))

    (when-not migration-fn
      (u/print-red (str "Unknown migration: " migration-name))
      (println)
      (println "Available migrations:")
      (if (seq registry)
        (doseq [k (sort (keys registry))]
          (println (str "  " k)))
        (println "  (none registered yet)"))
      (System/exit 1))

    ;; For now, just test node startup and user lookup
    (let [{:keys [target email file mapping-file]} options]
      (u/print-cyan (str "Starting XTDB node for target: " target))
      (let [node (start-node target)]
        (try
          (let [ctx (build-ctx node)
                db  (:biff/db ctx)]
            (u/print-green "Node started.")
            (println)
            (if email
              (let [user (first (biff/q db
                                        '{:find (pull ?e [*])
                                          :where [[?e :user/email e]]
                                          :in [e]}
                                        email))]
                (if user
                  (do
                    (u/print-green (str "Found user: " (:xt/id user)))
                    (println "  Email:" (:user/email user)))
                  (u/print-yellow (str "No user found for email: " email))))
              (u/print-yellow "No --email provided, skipping user lookup"))
            (println)
            (println "Migration:" migration-name)
            (println "Options:" (pr-str options))
            (println)
            (migration-fn {:ctx          ctx
                           :db           db
                           :file         file
                           :mapping-file mapping-file
                           :email        email
                           :target       target
                           :dry-run      (:dry-run options)
                           :options      options}))
          (finally
            (.close node)
            (println)
            (u/print-cyan "Node closed.")))))))
