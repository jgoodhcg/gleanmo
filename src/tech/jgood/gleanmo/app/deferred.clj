(ns tech.jgood.gleanmo.app.deferred
  "Shell-then-full page loading (roadmap/098-perceived-performance.md).

   A slow page answers on one URL in two shapes:

   - `/app/<page>` is the **shell**: layout, header, filters, and anything else
     cheap, with a shaped skeleton in place of each slow region and one
     `loader` per tier.
   - `/app/<page>?full=1` is the **full page**: a normal standalone page with
     every region computed. Other query params (filters) are kept.

   The loader fetches the full page after the shell renders and swaps each
   listed region id over its skeleton with `hx-select-oob`, so one request
   fills several regions and the server never marks fragments up for
   out-of-band swapping.

   A page with a wide spread of region costs can split its regions into
   tiers. Each tier's loader asks for `?full=1&tier=<tier>`; the full render
   computes only that tier (`wants?`) and leaves the others as skeletons,
   which the loader ignores. Tiers run one after another, never at once, so
   their queries do not compete on the small prod box."
  (:require
   [clojure.string :as str]
   [ring.util.codec :as codec]))

(defn full?
  "Whether this request asked for the full page rather than the shell."
  [{:keys [params]}]
  (= "1" (:full params)))

(defn wants?
  "In a full render, whether to compute the regions of `tier`. A plain
   `?full=1` computes every tier; a tier loader's request computes its own."
  [{:keys [params]} tier]
  (let [requested (:tier params)]
    (or (str/blank? requested) (= (name tier) requested))))

(defn full-url
  "The current URL with its query params kept, asking for the full page,
   or for one `tier` of it."
  [{:keys [uri query-string]} & [tier]]
  (let [params (-> (if (str/blank? query-string)
                     {}
                     (let [decoded (codec/form-decode query-string)]
                       (if (map? decoded) decoded {})))
                   (dissoc "full" "tier")
                   (assoc "full" "1")
                   (cond-> tier (assoc "tier" (name tier))))]
    (str uri "?" (codec/form-encode (into (sorted-map) params)))))

(defn- loader-id
  [tier]
  (str "deferred-loader-" (if tier (name tier) "page")))

(defn loader
  "The element that fills `region-ids` from the full page once the shell has
   rendered. With `:after`, it waits for that tier's loader to finish instead
   of firing on load. Place it outside the regions it replaces."
  [ctx {:keys [tier after region-ids]}]
  [:div {:id            (loader-id tier)
         :hidden        true
         :hx-get        (full-url ctx tier)
         :hx-trigger    (if after
                          (str "htmx:afterRequest from:#" (loader-id after))
                          "load")
         :hx-swap       "none"
         :hx-select-oob (str/join "," (map #(str "#" %) region-ids))}])

(defn handler
  "One Ring handler for a deferred page: `shell` by default, `full` for
   `?full=1`. Both take the request and return a response."
  [{:keys [shell full]}]
  (fn [ctx]
    (if (full? ctx)
      (full ctx)
      (shell ctx))))

(defn skeleton-attrs
  "Attributes for a region rendered as a skeleton: its id, so the full page's
   region replaces it, and `aria-busy` while it waits."
  [id]
  {:id id :aria-busy "true" :data-deferred-pending true})

(defn pulse-delay
  "Staggered pulse for the `i`th skeleton row, like `timeline-skeleton`."
  [i]
  {:animation-delay (str (* i 140) "ms")})
