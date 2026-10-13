(ns tech.jgood.gleanmo.cronometer.connection
  "Each user's Cronometer credentials, encrypted at rest in a
   `cronometer-connection` document (roadmap/056-cronometer-integration.md,
   Credentials).

   Without `:gleanmo/credentials-key` configured nothing here can encrypt or
   decrypt: the connect form is disabled and the sync stays dormant."
  (:require
   [clojure.edn :as edn]
   [tech.jgood.gleanmo.crypto :as crypto]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [xtdb.api :as xt]))

(defn credentials-key
  "The parsed credentials key from config, or nil when it is absent or
   malformed."
  [{:keys [biff/secret]}]
  (when secret
    (crypto/parse-key (secret :gleanmo/credentials-key))))

(defn configured?
  [ctx]
  (some? (credentials-key ctx)))

(defn- fresh-db
  [{:keys [biff.xtdb/node biff/db]}]
  (if node (xt/db node) db))

(defn disconnect!
  "Evict the user's connection, so no version of the ciphertext stays in
   history, and drop the in-memory session."
  [ctx user-id]
  (when-let [{:keys [xt/id]} (queries/cronometer-connection-for-user
                              (fresh-db ctx) user-id)]
    (mutations/evict-entity! ctx id))
  (client/forget-session! user-id)
  nil)

(defn connect!
  "Store `email` and `password` for `user-id`, replacing any earlier
   connection. The old one is evicted rather than overwritten, because an
   overwrite would leave the old ciphertext in history."
  [ctx user-id email password]
  (let [k (or (credentials-key ctx)
              (throw (ex-info "No credentials key configured"
                              {:type ::not-configured})))
        {:keys [ciphertext nonce key-version]}
        (crypto/encrypt k (pr-str {:email email, :password password}) (str user-id))]
    (disconnect! ctx user-id)
    (mutations/create-entity!
     ctx
     {:entity-key :cronometer-connection
      :data       {:user/id                          user-id
                   :cronometer-connection/ciphertext  ciphertext
                   :cronometer-connection/nonce       nonce
                   :cronometer-connection/key-version key-version}})))

(defn credentials
  "`{:email :password}` decrypted from `connection-doc`. Throws when the key
   does not match, e.g. after the key was replaced."
  [ctx connection-doc]
  (let [k (or (credentials-key ctx)
              (throw (ex-info "No credentials key configured"
                              {:type ::not-configured})))]
    (edn/read-string
     (crypto/decrypt k
                     {:ciphertext (:cronometer-connection/ciphertext connection-doc)
                      :nonce      (:cronometer-connection/nonce connection-doc)}
                     (str (:user/id connection-doc))))))

(defn connected-email
  "The email of the user's connection, or nil when there is none or it cannot
   be decrypted."
  [ctx user-id]
  (when-let [doc (queries/cronometer-connection-for-user (:biff/db ctx) user-id)]
    (try
      (:email (credentials ctx doc))
      (catch Exception _ nil))))

(def fake-path
  "Where the dev-only fake Cronometer API is mounted on this server."
  "/dev/cronometer")

(defn fake?
  "Whether config points the sync at the dev-only fake API. The config key is
   hard false under the prod profile."
  [ctx]
  (true? (:gleanmo.cronometer/fake ctx)))

(defn base-url
  "The Cronometer API base URL for this server: the fake on this server when
   `fake?`, otherwise nil, which the client reads as Cronometer itself."
  [{:keys [biff/base-url], :as ctx}]
  (when (fake? ctx)
    (str base-url fake-path)))

(defn client-connection
  "A `client/connection` for the user's stored credentials, or nil when the
   user has no connection."
  [ctx user-id]
  (when-let [doc (queries/cronometer-connection-for-user (fresh-db ctx) user-id)]
    (let [{:keys [email password]} (credentials ctx doc)]
      (client/connection {:id       user-id
                          :email    email
                          :password password
                          :base-url (base-url ctx)}))))
