(ns tech.jgood.gleanmo
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.repl :as tn-repl]
   [com.biffweb :as biff]
   [malli.core :as malc]
   [malli.registry :as malr]
   [nrepl.cmdline :as nrepl-cmd]
   [tech.jgood.gleanmo.api :as api]
   [tech.jgood.gleanmo.api.middleware :as api-mid]
   [tech.jgood.gleanmo.app :as app]
   [tech.jgood.gleanmo.email :as email]
   [tech.jgood.gleanmo.home :as home]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.observability :as observability]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema :as schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.worker :as worker]
   [tick.core :as t])
  (:gen-class))

(def ^:private dev-modules
  "Modules under `dev/`, which only the dev classpath has. The uberjar leaves
   `dev/` out, so in production each require fails and the module is absent."
  '[tech.jgood.gleanmo.e2e-auth/module
    tech.jgood.gleanmo.fake-cronometer/module])

(defn- dev-module
  [sym]
  (try
    (require (symbol (namespace sym)))
    @(resolve sym)
    (catch java.io.FileNotFoundException _ nil)
    (catch Exception e
      (log/warn e "Dev module failed to load:" sym)
      nil)))

(def modules
  (let [base-modules [app/module
                      api/module
                      observability/module
                      (biff/authentication-module
                       {:biff.auth/new-user-tx
                        (fn [_ctx email]
                          (let [now (t/now)]
                            [{:db/doc-type    :user
                              ::sm/type       :user
                              ::sm/created-at now
                              :db.op/upsert   {:user/email email}
                              :user/joined-at now}]))})
                      home/module
                      schema/module
                      worker/module]]
    (into base-modules (keep dev-module) dev-modules)))

(def routes [["" {:middleware [mid/wrap-site-defaults]}
              (keep :routes modules)]
             ["" {:middleware [api-mid/wrap-no-store
                               api-mid/wrap-malformed-body
                               mid/wrap-api-defaults]}
              (keep :api-routes modules)]])

(def handler (-> (biff/reitit-handler {:routes routes})
                 mid/wrap-base-defaults))

(def static-pages (apply biff/safe-merge (map :static modules)))

(defn generate-assets! [_ctx]
  (biff/export-rum static-pages "target/resources/public")
  (biff/delete-old-files {:dir "target/resources/public"
                          :exts [".html"]}))

(defn on-save [ctx]
  (biff/add-libs)
  (biff/eval-files! ctx)
  (generate-assets! ctx))

(def malli-opts
  {:registry (malr/composite-registry
              malc/default-registry
              (apply biff/safe-merge
                     (keep :schema modules)))})

(def initial-system
  {:biff/modules #'modules
   :biff/send-email #'email/send-email
   :biff/handler #'handler
   :biff/malli-opts #'malli-opts
   :biff.beholder/on-save #'on-save
   :biff.middleware/on-error #'ui/on-error
   :biff.xtdb/tx-fns biff/tx-fns})

(defonce system (atom {}))

(def ^:private sign-in-keys
  "Config keys whose absence silently weakens sign-in rather than breaking it:
   Biff skips the captcha without the secret, and email falls back to printing
   sign-in links to the console without the MailerSend key."
  {:recaptcha/secret-key :secret
   :recaptcha/site-key   :env
   :mailersend/api-key   :secret})

(defn use-required-config
  "When `:gleanmo/require-sign-in-keys` is set (the prod profile), throw at
   startup if any sign-in key is missing, so a misconfigured deploy fails
   instead of running without a captcha or real email."
  [{:keys [biff/secret gleanmo/require-sign-in-keys] :as ctx}]
  (when require-sign-in-keys
    (let [missing (for [[k kind] sign-in-keys
                        :let     [v (if (= kind :secret) (secret k) (get ctx k))]
                        :when    (or (nil? v) (and (string? v) (str/blank? v)))]
                    k)]
      (when (seq missing)
        (throw (ex-info (str "Missing required config: " (str/join ", " missing))
                        {:missing (vec missing)})))))
  ctx)

(def components
  [biff/use-aero-config
   use-required-config
   biff/use-xtdb
   biff/use-queues
   biff/use-xtdb-tx-listener
   biff/use-htmx-refresh
   observability/use-observability
   biff/use-jetty
   biff/use-chime
   biff/use-beholder])

(defn start []
  (let [new-system (reduce (fn [sys component]
                             (log/info "starting:" (str component))
                             (component sys))
                           initial-system
                           components)]
    (reset! system new-system)
    (generate-assets! new-system)
    (log/info "System started.")
    (log/info "Go to" (:biff/base-url new-system))
    new-system))

(defn -main []
  (let [{:keys [biff.nrepl/args biff.nrepl/enabled]} (start)]
    (when enabled
      (apply nrepl-cmd/-main args))))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start))
