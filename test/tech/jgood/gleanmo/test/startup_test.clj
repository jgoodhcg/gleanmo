(ns tech.jgood.gleanmo.test.startup-test
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb.config]
   [tech.jgood.gleanmo :as main]))

(defn- ctx-with
  "A config map shaped like Biff's: secrets behind :biff/secret, env values plain."
  [secrets env]
  (merge {:biff/secret (fn [k] (get secrets k))
          :gleanmo/require-sign-in-keys true}
         env))

(def ^:private all-secrets
  {:recaptcha/secret-key "s", :mailersend/api-key "m"})

(def ^:private all-env
  {:recaptcha/site-key "k"})

(deftest use-required-config-test
  (testing "passes ctx through when every sign-in key is present"
    (let [ctx (ctx-with all-secrets all-env)]
      (is (= ctx (main/use-required-config ctx)))))
  (testing "throws naming each missing key"
    (doseq [[secrets env missing]
            [[(dissoc all-secrets :recaptcha/secret-key) all-env [:recaptcha/secret-key]]
             [(dissoc all-secrets :mailersend/api-key) all-env [:mailersend/api-key]]
             [all-secrets {} [:recaptcha/site-key]]]]
      (let [e (try (main/use-required-config (ctx-with secrets env))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) (pr-str missing))
        (is (= missing (:missing (ex-data e)))))))
  (testing "does nothing when the flag is off"
    (let [ctx (assoc (ctx-with {} {}) :gleanmo/require-sign-in-keys false)]
      (is (= ctx (main/use-required-config ctx))))))

(deftest require-sign-in-keys-profile-test
  (let [read-flag (fn [profile]
                    (:gleanmo/require-sign-in-keys
                     (aero/read-config (io/resource "config.edn")
                                       {:profile profile :biff.aero/env {}})))]
    (is (true? (read-flag :prod)))
    (is (false? (read-flag :dev)))))
