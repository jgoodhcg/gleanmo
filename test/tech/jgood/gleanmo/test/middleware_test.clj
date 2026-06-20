(ns tech.jgood.gleanmo.test.middleware-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.middleware :as mid])
  (:import
   [java.util UUID]))

(defn ok-handler
  "A stand-in downstream handler that records it was reached."
  [_ctx]
  {:status 200 :body ::reached})

(deftest wrap-signed-in-test
  (let [handler (mid/wrap-signed-in ok-handler)]
    (testing "signed-in requests pass through to the wrapped handler"
      (let [resp (handler {:session {:uid (UUID/randomUUID)}})]
        (is (= 200 (:status resp)))
        (is (= ::reached (:body resp)))))

    (testing "anonymous browser navigation gets a 303 redirect to sign-in"
      (let [resp (handler {:session {}})]
        (is (= 303 (:status resp)))
        (is (= "/signin?error=not-signed-in"
               (get-in resp [:headers "location"])))
        (is (nil? (get-in resp [:headers "HX-Redirect"]))
            "must not set HX-Redirect for non-HTMX requests")))

    (testing "anonymous HTMX request gets HX-Redirect so HTMX does a full-page nav
              instead of swapping the sign-in page into the authenticated layout"
      (let [resp (handler {:session {}
                           :headers {"hx-request" "true"}})]
        (is (= 200 (:status resp))
            "HX-Redirect must ride on a 2xx; a 303 would be followed transparently by the XHR")
        (is (= "/signin?error=not-signed-in"
               (get-in resp [:headers "HX-Redirect"])))
        (is (nil? (get-in resp [:headers "location"]))
            "must not also emit a plain redirect that the XHR would follow")))

    (testing "a signed-in HTMX request still passes through"
      (let [resp (handler {:session {:uid (UUID/randomUUID)}
                           :headers {"hx-request" "true"}})]
        (is (= 200 (:status resp)))
        (is (= ::reached (:body resp)))))))
