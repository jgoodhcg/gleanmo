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

    ;; Target is the landing page, not /signin: the landing redesign
    ;; (d5808f7) retargeted both branches and the landing page renders the
    ;; not-signed-in message itself. What matters for the auth-expiry bug is
    ;; the 303-vs-HX-Redirect split, not which clean page we land on.
    (testing "anonymous browser navigation gets a 303 redirect to the landing page"
      (let [resp (handler {:session {}})]
        (is (= 303 (:status resp)))
        (is (= "/?error=not-signed-in"
               (get-in resp [:headers "location"])))
        (is (nil? (get-in resp [:headers "HX-Redirect"]))
            "must not set HX-Redirect for non-HTMX requests")))

    (testing "anonymous HTMX request gets HX-Redirect so HTMX does a full-page nav
              instead of swapping the sign-in page into the authenticated layout"
      (let [resp (handler {:session {}
                           :headers {"hx-request" "true"}})]
        (is (= 200 (:status resp))
            "HX-Redirect must ride on a 2xx; a 303 would be followed transparently by the XHR")
        (is (= "/?error=not-signed-in"
               (get-in resp [:headers "HX-Redirect"])))
        (is (nil? (get-in resp [:headers "location"]))
            "must not also emit a plain redirect that the XHR would follow")))

    (testing "a signed-in HTMX request still passes through"
      (let [resp (handler {:session {:uid (UUID/randomUUID)}
                           :headers {"hx-request" "true"}})]
        (is (= 200 (:status resp)))
        (is (= ::reached (:body resp)))))))

(deftest loggable-path-test
  (testing "magic sign-in link tokens are masked"
    (is (= "/auth/verify-link/[redacted]"
           (mid/loggable-path "/auth/verify-link/eyJhbGciOi.abc.def" nil)))
    (is (= "/auth/verify-link?token"
           (mid/loggable-path "/auth/verify-link" "token=eyJhbGciOi.abc"))))
  (testing "query values are dropped and names kept"
    (is (= "/api/v1/habit-log?q&limit"
           (mid/loggable-path "/api/v1/habit-log" "q=migraine%20notes&limit=5")))
    (is (= "/auth/e2e-login?email&token"
           (mid/loggable-path "/auth/e2e-login" "email=a%40localhost&token=s3cret"))))
  (testing "ordinary paths are unchanged"
    (is (= "/app/crud/habit" (mid/loggable-path "/app/crud/habit" nil)))
    (is (= "/app/crud/habit" (mid/loggable-path "/app/crud/habit" "")))
    (is (= "/app?flag" (mid/loggable-path "/app" "flag")))))
