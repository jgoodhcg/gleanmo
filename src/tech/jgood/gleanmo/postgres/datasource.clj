(ns tech.jgood.gleanmo.postgres.datasource
  "Builds `next.jdbc` data sources for Postgres using the project's env/config defaults.

  This namespace is intentionally Biff-agnostic so we can exercise the upcoming
  Postgres integration from the REPL without touching the running system."
  (:require
   [clojure.string :as str]
   [next.jdbc :as jdbc]))

(def ^:private default-config
  {:host     "localhost"
   :port     5432
   :dbname   "gleanmo_dev"
   :user     "gleanmo_app"
   :password "gleanmo_dev_pw"})

(defn- read-env [k default]
  (let [value (some-> (System/getenv k) str/trim)]
    (if (or (nil? value) (str/blank? value))
      default
      value)))

(defn- read-env-int [k default]
  (Integer/parseInt (str (read-env k default))))

(defn resolve-config
  "Return a map containing Postgres connection settings derived from env vars with sensible defaults.

  Environment variables honoured:
  - POSTGRES_HOST
  - POSTGRES_PORT
  - POSTGRES_DB
  - POSTGRES_USER
  - POSTGRES_PASSWORD
  - POSTGRES_SSLMODE (optional; pass-through when present)"
  []
  (let [sslmode (some-> (System/getenv "POSTGRES_SSLMODE") str/trim)]
    (cond-> {:host     (read-env "POSTGRES_HOST" (:host default-config))
             :port     (read-env-int "POSTGRES_PORT" (:port default-config))
             :dbname   (read-env "POSTGRES_DB" (:dbname default-config))
             :user     (read-env "POSTGRES_USER" (:user default-config))
             :password (read-env "POSTGRES_PASSWORD" (:password default-config))}
      (some? sslmode) (assoc :sslmode sslmode))))

(defn jdbc-spec
  "Convert a resolved config into the map shape `next.jdbc/get-datasource` expects."
  [{:keys [host port dbname user password sslmode]}]
  (cond-> {:dbtype   "postgresql"
           :host     host
           :port     port
           :dbname   dbname
           :user     user
           :password password}
    (some? sslmode) (assoc :sslmode sslmode)))

(defn datasource
  "Build a fresh `javax.sql.DataSource` for Postgres.

  Optionally accepts an override config map (same keys as `resolve-config`)."
  ([] (datasource (resolve-config)))
  ([cfg]
   (jdbc/get-datasource (jdbc-spec cfg))))

(defn with-connection
  "Execute `f` with an open JDBC connection derived from the datasource.

  Example:
    (with-connection (datasource) (fn [conn] ...))"
  [ds f]
  (with-open [conn (jdbc/get-connection ds)]
    (f conn)))
