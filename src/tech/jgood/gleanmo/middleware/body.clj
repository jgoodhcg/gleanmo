(ns tech.jgood.gleanmo.middleware.body
  "Guards on request bodies that must run outside the body parsers.

   Site and API route middleware decode form and JSON bodies before CSRF,
   sign-in, or token checks run, so anything that has to hold for
   unauthenticated requests belongs here, ahead of that. Each guard takes a
   `refuse` fn of `[req status message]` so the API can answer in JSON and the
   site in plain text. See roadmap/100-agent-api-security.md."
  (:import
   [java.io FilterInputStream InputStream]))

(def max-bytes
  "The largest request body read. A full 200-op API batch of task edits with
   long notes is well under this, and site forms are urlencoded text with no
   uploads; nothing legitimate comes close."
  (* 1024 1024))

(defn- too-large
  []
  (ex-info "Request body too large" {:type ::too-large}))

(defn too-large?
  "Whether `e`, or anything in its cause chain, is a body over `max-bytes`."
  [e]
  (some #(= ::too-large (:type (ex-data %)))
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

(defn wrap-limit
  "Refuse bodies over `max-bytes` with 413: up front when the declared length
   is too large, else as soon as a parser reads past the limit. Must sit inside
   anything that turns exceptions into responses."
  [handler refuse]
  (fn [{:keys [headers body] :as req}]
    (let [declared (some-> (get headers "content-length") parse-long)]
      (if (and declared (< max-bytes declared))
        (refuse req 413 "Request body too large.")
        (try
          (handler (cond-> req
                     (instance? InputStream body)
                     (assoc :body (bounded-stream body max-bytes))))
          (catch Exception e
            (if (too-large? e)
              (refuse req 413 "Request body too large.")
              (throw e))))))))

(defn- malformed?
  [e]
  (= :muuntaja/decode (:type (ex-data e))))

(defn wrap-malformed
  "Answer a body muuntaja cannot decode with 400. Left alone, the decode error
   escapes as a 500, and its ex-data — the whole request, headers included —
   is logged at error level. Any client can trigger that without signing in."
  [handler refuse]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (if (and (malformed? e) (not (too-large? e)))
          (refuse req 400 "Malformed request body.")
          (throw e))))))
