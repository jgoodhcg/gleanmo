(ns tech.jgood.gleanmo.app.api-tokens
  "Create, list, and revoke API tokens for the agent API (`tech.jgood.gleanmo.api`)."
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.app.shared :refer [param-true?]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt]))

(def ^:private base-path "/app/api-tokens")

(defn- fmt-instant
  [inst]
  (if inst
    (str (t/format (t/formatter "yyyy-MM-dd HH:mm") (t/in inst (t/zone "UTC")))
         " UTC")
    "never"))

(defn- access-label
  [token-doc]
  (let [{:keys [read write sensitive archived]} (access/permissions-summary token-doc)
        read-only (remove (set write) read)]
    (str/join " · "
              (cond-> []
                (seq write)     (conj (str "write: " (str/join ", " write)))
                (seq read-only) (conj (str "read: " (str/join ", " read-only)))
                (empty? read)   (conj "no access")
                sensitive       (conj "sensitive")
                archived        (conj "archived")))))

(defn- new-token-callout
  [token]
  (layout/card
   {:class "border-neon-cyan" :data-testid "new-api-token"}
   [:p.text-sm.text-gray-300.mb-2
    "Copy this token now. It is stored only as a hash and will not be shown again."]
   [:div.flex.items-center.gap-2
    [:code.flex-1.font-mono.text-sm.break-all.text-white.bg-dark-bg.rounded.p-2
     {:id "new-api-token-value"} token]
    [:button.form-button-secondary
     {:type    "button"
      :onclick "navigator.clipboard.writeText(document.getElementById('new-api-token-value').textContent)"}
     "Copy"]]))

(defn- group-row
  [{:keys [key label types]}]
  (let [field (str "group-" (name key))]
    [:tr.border-t.border-dark {:key field}
     [:td.py-2.pr-3
      [:div.text-sm.text-gray-200 label]
      [:div.text-xs.text-gray-500 (str/join ", " (map name types))]]
     (for [level ["none" "read" "write"]]
       [:td.py-2.px-2.text-center {:key level}
        [:input {:type                "radio"
                 :name                field
                 :value               level
                 :aria-label          (str label " " level)
                 :checked             (= "none" level)
                 :data-original-value "none"}]])]))

(defn- create-form
  []
  (biff/form
   {:action base-path, :method "post", :id "api-token-create-form"}
   [:div.space-y-4
    [:div
     [:label.form-label {:for "label"} "Label"]
     [:div.mt-2
      [:input.form-input
       {:type                "text"
        :name                "label"
        :id                  "label"
        :required            true
        :placeholder         "e.g. Claude Code on laptop"
        :data-original-value ""}]]]
    [:div
     [:h3.form-label.mb-2 "Access by group"]
     [:table.w-full
      [:thead
       [:tr.text-xs.text-gray-400
        [:th.text-left.font-normal.pb-1 "Group"]
        [:th.font-normal.pb-1.px-2 "None"]
        [:th.font-normal.pb-1.px-2 "Read"]
        [:th.font-normal.pb-1.px-2 "Write"]]]
      [:tbody (map group-row access/groups)]]
     [:p.text-xs.text-gray-500.mt-2
      "Write includes read. Types not listed here — your account, calendar feed URLs, tokens — are never reachable."]]
    [:div.space-y-2
     [:div.flex.items-center.space-x-2
      [:input.form-checkbox
       {:type "checkbox", :name "sensitive-granted", :id "sensitive-granted"
        :data-original-value "false"}]
      [:label.text-sm.text-secondary {:for "sensitive-granted"}
       "Allow sensitive records (each list request must still ask for them)"]]
     [:div.flex.items-center.space-x-2
      [:input.form-checkbox
       {:type "checkbox", :name "archived-granted", :id "archived-granted"
        :data-original-value "false"}]
      [:label.text-sm.text-secondary {:for "archived-granted"}
       "Allow archived records (each list request must still ask for them)"]]]
    [:button.form-button-primary.w-full {:type "submit"} "Create token"]]))

(defn- token-row
  [{:keys [xt/id] :api-token/keys [label prefix last-used-at revoked-at] :as token-doc}]
  [:li.py-3.border-t.border-dark {:key (str id) :data-testid "api-token-row"}
   [:div.flex.items-start.justify-between.gap-3
    [:div.min-w-0
     [:div.text-sm.font-semibold.text-gray-200.break-words label]
     [:code.text-xs.text-gray-500 (str prefix "…")]
     [:div.text-xs.text-gray-400.mt-1 (access-label token-doc)]
     [:div.text-xs.text-gray-500.mt-1
      (str "created " (fmt-instant (::sm/created-at token-doc))
           " · last used " (fmt-instant last-used-at))]
     (when revoked-at
       [:div.text-xs.text-gray-500.mt-1
        (str "revoked " (fmt-instant revoked-at))])]
    (when-not revoked-at
      (biff/form
       {:action (str base-path "/" id "/revoke"), :method "post", :class "shrink-0"}
       [:button.form-button-secondary {:type "submit"} "Revoke"]))]])

(defn page
  "The token page. `new-token` is the plaintext of a token just created, shown
   once."
  ([ctx] (page ctx nil))
  ([{:keys [biff/db session], :as ctx} new-token]
   (let [tokens (queries/api-tokens-for-user db (:uid session))]
     (ui/page
      ctx
      (layout/page-shell
       ctx {:width :narrow}
       (layout/page-header
        {:title    "API tokens"
         :subtitle "Bearer tokens for agents and scripts. Each sees only what you grant it."})
       [:div.space-y-6
        (when new-token (new-token-callout new-token))
        (layout/card (create-form))
        [:div
         (layout/section-header "TOKENS" [:span.text-xs.text-gray-500 (count tokens)])
         (if (seq tokens)
           [:ul.mt-2.list-none.pl-0 (map token-row tokens)]
           [:p.text-sm.text-gray-500.mt-2 "No tokens yet."])]
        [:p.text-xs.text-gray-500
         "Use a token as " [:code "Authorization: Bearer gmn_…"]
         ". Start at " [:code "GET /api/v1/types"] " to see what it can reach."]])))))

(defn- submitted-groups
  [params level]
  (into #{}
        (keep (fn [{:keys [key]}]
                (when (= level (get params (keyword (str "group-" (name key)))))
                  key)))
        access/groups))

(defn create!
  "Create a token and render the page with its plaintext. This handler renders
   instead of redirecting because the plaintext exists only in this response;
   the snapshot is refreshed so the new token appears in the list."
  [{:keys [params session biff.xtdb/node], :as ctx}]
  (let [label (str/trim (str (:label params)))]
    (if (str/blank? label)
      {:status 303, :headers {"location" base-path}}
      (let [token (access/generate-token)]
        (mutations/create-entity!
         ctx
         {:entity-key :api-token
          :data       {:user/id                     (:uid session)
                       :api-token/label             label
                       :api-token/token-hash        (access/hash-token token)
                       :api-token/prefix            (access/display-prefix token)
                       :api-token/read-groups       (submitted-groups params "read")
                       :api-token/write-groups      (submitted-groups params "write")
                       :api-token/sensitive-granted (param-true?
                                                     (:sensitive-granted params))
                       :api-token/archived-granted  (param-true?
                                                     (:archived-granted params))}})
        (page (assoc ctx :biff/db (xt/db node)) token)))))

(defn revoke!
  [{:keys [biff/db path-params session], :as ctx}]
  (let [id        (parse-uuid (str (:id path-params)))
        token-doc (when id (queries/get-entity-for-user db id (:uid session) :api-token))]
    (when (and token-doc (nil? (:api-token/revoked-at token-doc)))
      (mutations/update-entity! ctx {:entity-key :api-token
                                     :entity-id  id
                                     :data       {:api-token/revoked-at (t/now)}}))
    {:status 303, :headers {"location" base-path}}))

(def routes
  [["/api-tokens" {:get page, :post create!}]
   ["/api-tokens/:id/revoke" {:post revoke!}]])
