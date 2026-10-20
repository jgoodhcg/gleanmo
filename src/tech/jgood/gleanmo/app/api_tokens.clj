(ns tech.jgood.gleanmo.app.api-tokens
  "Create, list, and revoke API tokens for the agent API (`tech.jgood.gleanmo.api`)."
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.api.discovery :as discovery]
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

(defn- base-url
  [ctx]
  (str/replace (str (:biff/base-url ctx)) #"/+$" ""))

(defn- copy-onclick
  "Copy the value of element `id`, flashing the button's label."
  [id]
  (str "var b=this;navigator.clipboard.writeText(document.getElementById('" id "').value).then(function(){b.textContent='Copied ✓'},function(){b.textContent='Copy failed'}).then(function(){setTimeout(function(){b.textContent='Copy'},1500)})"))

(defn- agent-hint
  "The one thing to tell an agent. It reads the guide and walks the user
   through grants and token storage, so this comes before the form."
  [ctx]
  (let [url (str (base-url ctx) "/agents")]
    [:p.text-sm.text-gray-300 {:data-testid "agent-setup-hint"}
     "Setting up an agent? Tell it " [:code.text-neon-cyan (str "set up Gleanmo, see " url)]
     ". It will suggest what to grant here and where to keep the token."]))

(defn- new-token-callout
  "The plaintext of a token just created, then a prompt to paste into an
   agent. The token is masked like a password until revealed, and Copy reads
   the input's value, so copying never needs a reveal. The prompt names the
   token's grants but never the token, so pasting it into a chat leaks nothing."
  [token prompt]
  (layout/card
   {:class "border-neon-cyan" :data-testid "new-api-token"}
   [:p.text-sm.text-gray-300.mb-2
    "Copy this token now. Only a hash and its first few characters are kept, so the full token can't be shown again."]
   [:div.flex.items-center.gap-2
    [:input.form-input.flex-1.min-w-0.font-mono.text-sm
     {:type         "password"
      :id           "new-api-token-value"
      :value        token
      :readonly     true
      :autocomplete "off"
      :spellcheck   "false"
      :aria-label   "New API token"}]
    [:button.form-button-secondary.shrink-0
     {:type    "button"
      :id      "new-api-token-reveal"
      :onclick "var i=document.getElementById('new-api-token-value');var shown=i.type==='text';i.type=shown?'password':'text';this.textContent=shown?'Reveal':'Hide'"}
     "Reveal"]
    [:button.form-button-secondary.shrink-0
     {:type    "button"
      :id      "new-api-token-copy"
      :onclick (copy-onclick "new-api-token-value")}
     "Copy"]]
   [:div.mt-4
    [:label.text-sm.text-gray-300 {:for "new-api-token-prompt"}
     "Then paste this into your agent. It doesn't contain the token; the agent will tell you where to put that."]
    [:textarea.form-input.w-full.mt-2.text-sm
     {:id       "new-api-token-prompt"
      :rows     5
      :readonly true}
     prompt]
    [:div.flex.justify-end.mt-2
     [:button.form-button-secondary
      {:type    "button"
       :id      "new-api-token-prompt-copy"
       :onclick (copy-onclick "new-api-token-prompt")}
      "Copy"]]]))

(defn- create-error
  [message]
  [:p.text-sm.text-red-400 {:data-testid "new-api-token-error"} message])

(defn- group-row
  "A read-only group (synced data) offers none and read only."
  [{:keys [key label types read-only]}]
  (let [field (str "group-" (name key))]
    [:tr.border-t.border-dark {:key field}
     [:td.py-2.pr-3
      [:div.text-sm.text-gray-200 label]
      [:div.text-xs.text-gray-500
       (str/join ", " (map name types))
       (when read-only " · read-only, synced")]]
     (for [level ["none" "read" "write"]]
       [:td.py-2.px-2.text-center {:key level}
        (if (and read-only (= "write" level))
          [:span.text-xs.text-gray-600 {:aria-label (str label " write unavailable")} "—"]
          [:input {:type                "radio"
                   :name                field
                   :value               level
                   :aria-label          (str label " " level)
                   :checked             (= "none" level)
                   :data-original-value "none"}])])]))

(defn- create-form
  "Posts with HTMX rather than navigating: the response carries the token's
   plaintext, and a navigation would let a refresh re-submit and mint another
   token. See `.decisions/api-token-reveal.json`."
  []
  (biff/form
   {:action    base-path
    :method    "post"
    :id        "api-token-create-form"
    :hx-post   base-path
    :hx-target "#new-api-token-slot"
    :hx-swap   "innerHTML"}
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

(defn- create-section
  "The create form in its swap container. Re-sent after a create so the form
   comes back empty with no changed-field highlights."
  ([] (create-section nil))
  ([attrs]
   [:div#api-token-create attrs (layout/card (create-form))]))

(defn- token-list
  ([tokens] (token-list tokens nil))
  ([tokens attrs]
   [:div#api-token-list attrs
    (layout/section-header "TOKENS" [:span.text-xs.text-gray-500 (count tokens)])
    (if (seq tokens)
      [:ul.mt-2.list-none.pl-0 (map token-row tokens)]
      [:p.text-sm.text-gray-500.mt-2 "No tokens yet."])]))

(defn page
  "The token page. A new token's plaintext never appears here — `create!`
   returns it as an HTMX fragment into `#new-api-token-slot`."
  [{:keys [biff/db session], :as ctx}]
  (let [tokens (queries/api-tokens-for-user db (:uid session))]
    (ui/page
     ctx
     (layout/page-shell
      ctx {:width :narrow}
      (layout/page-header
       {:title    "API tokens"
        :subtitle "Bearer tokens for agents and scripts. Each sees only what you grant it."})
      [:div.space-y-6
       (agent-hint ctx)
       [:div#new-api-token-slot]
       (create-section)
       (token-list tokens)
       [:p.text-xs.text-gray-500
        "Agents send a token as " [:code "Authorization: Bearer gmn_…"]
        ". API reference: " [:code "/agents"] "."]]))))

(defn- submitted-level
  "The level submitted for a group. A write submitted for a read-only group
   (only possible from a tampered form) is stored as read."
  [params {:keys [key read-only]}]
  (let [level (get params (keyword (str "group-" (name key))))]
    (if (and read-only (= "write" level)) "read" level)))

(defn- submitted-groups
  [params level]
  (into #{}
        (keep (fn [group]
                (when (= level (submitted-level params group))
                  (:key group))))
        access/groups))

(defn- fragment-response
  [& body]
  {:status  200
   :headers {"Content-Type"  "text/html"
             "Cache-Control" "no-store"}
   :body    (apply ui/fragment {} body)})

(defn create!
  "Create a token and return its plaintext as an HTMX fragment, the one time it
   is ever sent. This breaks the app's write-then-303 rule on purpose: no
   redirect can carry the plaintext without storing it somewhere, and an HTMX
   post is not a navigation, so refreshing the page cannot re-submit it. The
   form and token list come back out-of-band; the snapshot is refreshed first
   so the list includes the new token. See `.decisions/api-token-reveal.json`."
  [{:keys [params session biff.xtdb/node], :as ctx}]
  (let [label (str/trim (str (:label params)))]
    (if (str/blank? label)
      (fragment-response (create-error "A label is required."))
      (let [token (access/generate-token)
            data  {:user/id                     (:uid session)
                       :api-token/label             label
                       :api-token/token-hash        (access/hash-token token)
                       :api-token/prefix            (access/display-prefix token)
                       :api-token/read-groups       (submitted-groups params "read")
                       :api-token/write-groups      (submitted-groups params "write")
                       :api-token/sensitive-granted (param-true?
                                                     (:sensitive-granted params))
                       :api-token/archived-granted  (param-true?
                                                     (:archived-granted params))}]
        (mutations/create-entity! ctx {:entity-key :api-token, :data data})
        (let [tokens (queries/api-tokens-for-user (xt/db node) (:uid session))]
          (fragment-response
           (new-token-callout token (discovery/setup-prompt (base-url ctx) data))
           (create-section {:hx-swap-oob "true"})
           (token-list tokens {:hx-swap-oob "true"})))))))

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
