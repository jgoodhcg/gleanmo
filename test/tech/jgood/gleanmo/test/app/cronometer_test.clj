(ns tech.jgood.gleanmo.test.app.cronometer-test
  "The Cronometer status page in the states the e2e test cannot reach without
   a credentials key: configured, connected, and synced."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [rum.core :as rum]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.cronometer :as cronometer]
   [tech.jgood.gleanmo.crypto :as crypto]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.cronometer.sync :as sync]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.test.cronometer.fixtures :as fx]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time LocalDate ZoneId]))

(def ^:private test-key (crypto/generate-key))

(defn- ctx
  [node & {:keys [credentials-key params] :or {credentials-key test-key}}]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts
   :biff/secret     (fn [k] (when (= k :gleanmo/credentials-key) credentials-key))
   :session         {:uid fx/user-id}
   :params          (or params {})})

(defn- html
  [c]
  (rum/render-static-markup (cronometer/page c)))

(defn- location
  [resp]
  (get-in resp [:headers "location"]))

(def ^:private today
  (str (LocalDate/now (ZoneId/of fx/zone))))

(defn- diary-for-today
  "The fixture diary moved to today, so it falls inside the page's window."
  []
  (-> fx/diary
      (assoc-in [:info :day] today)
      (update :diary (fn [entries] (mapv #(assoc % :day today) entries)))))

(deftest status-page-test
  (with-open [node (test-xtdb-node [])]
    (mutations/create-entity! (ctx node) {:entity-key :user
                                          :data {:xt/id          fx/user-id
                                                 :user/email     "u@example.com"
                                                 :user/joined-at (t/now)
                                                 :user/time-zone fx/zone}})
    (testing "without a key: a notice and no form"
      (let [page (html (ctx node :credentials-key nil))]
        (is (str/includes? page "cronometer-unconfigured"))
        (is (not (str/includes? page "cronometer-connect-form")))))

    (testing "with a key and no connection: the connect form, no Sync now"
      (let [page (html (ctx node))]
        (is (str/includes? page "cronometer-connect-form"))
        (is (not (str/includes? page "cronometer-sync-now")))
        (is (not (str/includes? page "cronometer-fake-notice")))))

    (testing "with the dev fake on: a notice says so"
      (is (str/includes? (html (assoc (ctx node) :gleanmo.cronometer/fake true))
                         "cronometer-fake-notice")))

    (testing "a blank submission is refused"
      (is (str/includes? (location (cronometer/connect!
                                    (ctx node :params {:email " ", :password "pw"})))
                         "status=connect-invalid")))

    (testing "connecting stores credentials and 303s back"
      (let [resp (cronometer/connect! (ctx node :params {:email    "someone@example.com"
                                                         :password "hunter2"}))]
        (is (= 303 (:status resp)))
        (is (str/includes? (location resp) "status=connected"))
        (let [page (html (ctx node))]
          (is (str/includes? page "someone@example.com"))
          (is (str/includes? page "cronometer-sync-now"))
          (testing "the password is never rendered"
            (is (not (str/includes? page "hunter2")))))))

    (testing "Sync now writes, then 303s back with the counts"
      (binding [sync/*day-pause-ms* 0]
        (with-redefs [client/get-metrics (fn [_] fx/metric-catalog)
                      client/get-diary   (fn [_ day]
                                           (if (= today (str day))
                                             (diary-for-today)
                                             {:info {:complete false} :diary []}))
                      client/get-foods   (fn [_ ids] (filterv #((set ids) (:id %)) fx/foods))]
          (let [resp (cronometer/sync! (ctx node))]
            (is (= 303 (:status resp)))
            (is (str/includes? (location resp) "status=synced"))
            (is (str/includes? (location resp) "days=7"))))))

    (testing "the page shows the synced rows and the latest day's macros"
      (let [page (html (ctx node :params {:status "synced" :days "7" :created "22"
                                          :updated "0" :deleted "0"}))]
        (is (str/includes? page "Synced 7 days: 22 created"))
        (is (str/includes? page "cronometer-macros"))
        (is (str/includes? page (str "Latest day with food: " today)))
        ;; 320 + 60 + 660 kcal
        (is (str/includes? page ">1040<"))
        (is (not (str/includes? page ">never<")))
        (testing "with the day's activities and its one timed session"
          (is (re-find #"Activities</span><span[^>]*>3<" page))
          (is (re-find #"Timed activity sessions</span><span[^>]*>1<" page)))))

    (testing "disconnecting evicts the connection"
      (let [id   (:xt/id (queries/cronometer-connection-for-user (xt/db node) fx/user-id))
            resp (cronometer/disconnect! (ctx node))]
        (is (str/includes? (location resp) "status=disconnected"))
        (is (empty? (xt/entity-history (xt/db node) id :asc)))
        (is (not (str/includes? (html (ctx node)) "cronometer-sync-now")))))))
