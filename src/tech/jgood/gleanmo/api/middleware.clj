(ns tech.jgood.gleanmo.api.middleware
  "Request guards for the agent API that must run outside the body parsers.

   `wrap-api-defaults` decodes JSON and form bodies before any route
   middleware runs, so `api/wrap-api-token` sees a request only after its body
   has been read. Anything that has to hold for unauthenticated requests
   belongs here, ahead of that. See roadmap/100-agent-api-security.md."
  (:import
   [java.io FilterInputStream InputStream]))

(def max-body-bytes
  "The largest request body the API reads. A full 200-op batch of task edits
   with long notes is well under this; nothing legitimate comes close."
  (* 1024 1024))

(defn- json-response
  "A JSON response built by hand: these guards run outside muuntaja, so a map
   body would not be encoded."
  [status message]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (str "{\"error\":\"" message "\"}")})

(defn- too-large
  []
  (ex-info "Request body too large" {:type ::body-too-large}))

(defn- too-large?
  [e]
  (some #(= ::body-too-large (:type (ex-data %)))
        (take-while some? (iterate ex-cause e))))

(defn- bounded-stream
  "`in`, throwing once more than `limit` bytes have been read from it. Covers
   bodies that declare no length (chunked) or declare a false one."
  ^InputStream [^InputStream in limit]
  (let [n (volatile! 0)
        count! (fn [k]
                 (when (pos? k)
                   (when (< limit (vswap! n + k))
                     (throw (too-large))))
                 k)]
    (proxy [FilterInputStream] [in]
      (read
        ([] (let [b (.read in)]
              (when (<= 0 b) (count! 1))
              b))
        ([^bytes b] (count! (.read in b)))
        ([^bytes b off len] (count! (.read in b (int off) (int len))))))))

(defn wrap-body-limit
  "Refuse bodies over `max-body-bytes` with 413: up front when the declared
   length is too large, else as soon as the parser reads past the limit."
  [handler]
  (fn [{:keys [headers body] :as req}]
    (let [declared (some-> (get headers "content-length") parse-long)]
      (if (and declared (< max-body-bytes declared))
        (json-response 413 "Request body too large.")
        (try
          (handler (cond-> req
                     (instance? InputStream body)
                     (assoc :body (bounded-stream body max-body-bytes))))
          (catch Exception e
            (if (too-large? e)
              (json-response 413 "Request body too large.")
              (throw e))))))))
