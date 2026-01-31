(ns tech.jgood.gleanmo.e2e-auth
  "Dev-only auth bypass for E2E testing. This namespace is only loaded in dev mode
   and should never be included in production builds."
  (:require [com.biffweb :as biff]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [tick.core :as t]))

(defn e2e-login-handler
  "Dev-only endpoint that bypasses email verification.
   GET /auth/e2e-login?email=test@localhost -> sets session and redirects to /app"
  [{:keys [biff/db session params] :as ctx}]
  (let [email (get params :email "e2e-test@localhost")
        user (biff/lookup db :user/email email)]
    (if user
      ;; User exists, set session
      {:status 303
       :headers {"Location" "/app"}
       :session (assoc session :uid (:xt/id user))}
      ;; Create user first, then set session
      (let [user-id (random-uuid)
            now (t/now)]
        (biff/submit-tx ctx
                        [{:db/doc-type :user
                          :xt/id user-id
                          ::sm/type :user
                          ::sm/created-at now
                          :user/email email
                          :user/joined-at now}])
        {:status 303
         :headers {"Location" "/app"}
         :session (assoc session :uid user-id)}))))

(def module
  {:routes [["/auth/e2e-login" {:get e2e-login-handler}]]})
