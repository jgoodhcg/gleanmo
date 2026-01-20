(ns tech.jgood.gleanmo
  (:require
   [clojure.test :as test]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.repl :as tn-repl]
   [com.biffweb :as biff]
   [malli.core :as malc]
   [malli.registry :as malr]
   [nrepl.cmdline :as nrepl-cmd]
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

(def modules
  [app/module
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
   worker/module])

(def routes [["" {:middleware [mid/wrap-site-defaults]}
              (keep :routes modules)]
             ["" {:middleware [mid/wrap-api-defaults]}
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
  (generate-assets! ctx)
  (biff/catchall (require 'tech.jgood.gleanmo.test))
  (test/run-all-tests #"tech.jgood.gleanmo.test.*"))

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

(def components
  [biff/use-aero-config
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
  (let [{:keys [biff.nrepl/args]} (start)]
    (apply nrepl-cmd/-main args)))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start))
