(ns tech.jgood.gleanmo.api.middleware
  "Request guards for the agent API that must run outside the body parsers.

   `wrap-api-defaults` decodes JSON and form bodies before any route
   middleware runs, so `api/wrap-api-token` sees a request only after its body
   has been read. Anything that has to hold for unauthenticated requests
   belongs here, ahead of that. The body size limit covers every route, so it
   lives in `wrap-base-defaults`. See roadmap/100-agent-api-security.md."
  (:require
   [tech.jgood.gleanmo.middleware.body :as body]))

(defn refuse
  "A JSON error response built by hand: these guards run outside muuntaja, so
   a map body would not be encoded."
  [_req status message]
  {:status  status
   :headers {"Content-Type"  "application/json; charset=utf-8"
             "Cache-Control" "no-store"}
   :body    (str "{\"error\":\"" message "\"}")})

(defn wrap-malformed-body
  "Answer a body muuntaja cannot decode with a JSON 400."
  [handler]
  (body/wrap-malformed handler refuse))

(defn wrap-no-store
  "Mark every API response `Cache-Control: no-store`. Responses carry health
   records; a request with an `Authorization` header is already kept out of
   shared caches by default, but nothing stopped a browser, proxy or client
   library from keeping a private copy."
  [handler]
  (fn [req]
    (some-> (handler req)
            (assoc-in [:headers "Cache-Control"] "no-store"))))
