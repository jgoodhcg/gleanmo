(ns tech.jgood.gleanmo.home
  (:require [com.biffweb :as biff]
            [tech.jgood.gleanmo.middleware :as mid]
            [tech.jgood.gleanmo.ui :as ui]
            [tech.jgood.gleanmo.ui.icons :as icons]
            [tech.jgood.gleanmo.settings :as settings]))

(def email-disabled-notice
  [:.text-sm.mt-3.bg-blue-100.rounded.p-2
   "Until you add API keys for MailerSend and reCAPTCHA, we'll print your sign-up "
   "link to the console. See config.edn."])

(def ^:private svg-attrs
  {:xmlns           "http://www.w3.org/2000/svg"
   :viewBox         "0 0 24 24"
   :fill            "none"
   :stroke          "currentColor"
   :stroke-width    1.6
   :stroke-linecap  "round"
   :stroke-linejoin "round"})

(defn- lp-icon
  "Landing-page line icon. `id` is one of :crud :log :viz :lock :mail."
  [id class]
  (let [attrs (assoc svg-attrs :class class)]
    (case id
      :crud [:svg attrs
             [:rect {:x 4 :y 4 :width 16 :height 16 :rx 2}]
             [:line {:x1 4 :y1 9.5 :x2 20 :y2 9.5}]
             [:line {:x1 9.5 :y1 9.5 :x2 9.5 :y2 20}]]
      :log [:svg attrs
            [:line {:x1 5 :y1 12 :x2 19 :y2 12}]
            [:circle {:cx 9 :cy 12 :r 2.2}]
            [:circle {:cx 15 :cy 12 :r 2.2}]]
      :viz [:svg attrs
            [:rect {:x 4 :y 4 :width 16 :height 16 :rx 2}]
            [:rect {:x 7 :y 13 :width 2.6 :height 4}]
            [:rect {:x 11 :y 9 :width 2.6 :height 8}]
            [:rect {:x 15 :y 11 :width 2.6 :height 6}]]
      :lock [:svg attrs
             [:rect {:x 5 :y 10 :width 14 :height 10 :rx 2}]
             [:path {:d "M8 10 V7.5 a4 4 0 0 1 8 0 V10"}]]
      :mail [:svg attrs
             [:rect {:x 4 :y 6 :width 16 :height 12 :rx 2}]
             [:polyline {:points "4.5 7.5 12 13 19.5 7.5"}]])))

(defn- lp-section-label
  "Numbered section header with hairline rule."
  [n title]
  [:div.flex.items-baseline.gap-3.mb-6
   [:span.text-xs.tracking-widest.text-lp-accent n]
   [:h2.text-xs.font-bold.tracking-widest.text-slate-300 title]
   [:span.flex-1.h-px.lp-rule-bg]])

(defn- lp-sign-up-btn
  ([]
   (lp-sign-up-btn "Sign up"))
  ([label]
   [:a.inline-flex.items-center.gap-2.rounded-lg.bg-lp-accent.px-5.py-2.text-sm.font-semibold.no-underline.text-lp-accent-ink
    {:href "/signup"}
    label " " (icons/arrow-right {:class "w-4 h-4"})]))

(defn- lp-sign-in-btn []
  [:a.inline-flex.items-center.gap-2.rounded-lg.border.border-lp-edge-2.px-5.py-2.text-sm.no-underline.text-slate-300
   {:href "/signin"} "sign in"])

(defn home-page [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:div.lp-landing.min-h-screen.font-mono.text-slate-200.lp-hero-glow
    [:header.sticky.top-0.z-10.border-b.border-lp-rule.lp-header-blur
     [:div.max-w-4xl.mx-auto.px-8.py-4.flex.items-center.justify-between.gap-4
      [:span.text-sm.font-bold.tracking-widest "GLEANMO"]
      [:div.flex.items-center.gap-2
       [:a.rounded-md.border.border-lp-edge-2.px-4.py-2.text-xs.no-underline.text-slate-300
        {:href "/signin"} "sign in"]
       [:a.rounded-md.bg-lp-accent.px-4.py-2.text-xs.font-semibold.no-underline.text-lp-accent-ink
        {:href "/signup"} "sign up"]]]]
    [:main.max-w-4xl.mx-auto.px-8
     ;; hero
     [:section.py-24.border-b.border-lp-rule
      [:div.inline-flex.items-center.gap-2.rounded-full.border.border-lp-edge.px-3.py-1.mb-8
       [:span.block.w-1.h-1.bg-lp-accent.rounded-full]
       [:span.text-xs.tracking-widest.text-slate-500 "PERSONAL QUANTIFIED-SELF APP"]]
      [:h1.text-4xl.md:text-5xl.font-bold.tracking-tight.leading-tight.text-slate-100.max-w-xl
       "A personal quantified-self web app."]
      [:p.mt-5.text-base.leading-relaxed.text-slate-400.max-w-2xl
       "It tracks habits, meditation, reading, projects, and health metrics through a single private dashboard."]
      [:div.flex.flex-wrap.items-center.gap-3.mt-8
       (lp-sign-up-btn)
       (lp-sign-in-btn)
       [:span.text-xs.text-slate-500.ml-1 "Sign-in is by email magic link."]]]
     ;; 01 what it supports
     [:section.py-14.border-b.border-lp-rule
      (lp-section-label "01" "WHAT IT SUPPORTS")
      [:div.grid.gap-px.lp-rule-bg.border.border-lp-rule.rounded-xl.overflow-hidden
       {:class "grid-cols-1 sm:grid-cols-2"}
       [:div.lp-surface.p-6
        [:div.text-lp-accent.mb-3 (lp-icon :crud "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Schema-driven CRUD"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Create, edit, and manage many personal data types through one generic engine."]]
       [:div.lp-surface.p-6
        [:div.text-lp-accent.mb-3 (lp-icon :log "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Temporal activity logs"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Point-in-time and interval-based entries."]]
       [:div.lp-surface.p-6
        [:div.text-lp-accent.mb-3 (lp-icon :viz "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Visualizations"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Calendar heatmaps and related charts over the activity logs."]]
       [:div.lp-surface.p-6
        [:div.text-lp-accent.mb-3 (lp-icon :lock "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Privacy-aware by default"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "User settings and privacy-aware filtering, with email-based auth and reCAPTCHA."]]]]
     ;; 02 what this is
     [:section.py-14.border-b.border-lp-rule
      (lp-section-label "02" "WHAT THIS IS")
      [:div.grid.gap-10 {:class "grid-cols-1 md:grid-cols-2"}
       [:div
        [:p.text-sm.leading-relaxed.text-slate-400
         "An application in active use, built for a single primary user. It is public mainly as a reference for how it is built."]
        [:p.mt-4.text-sm.leading-relaxed.text-slate-500
         "Gleanmo is technically multi-user and schema-driven, but it is not positioned as a plug-and-play product."]]
       [:div.flex.flex-col.gap-4
        (for [t ["One place to track habits, exercise, projects, and other life data."
                 "A flexible Clojure playground for personal data visualization."]]
          [:div.flex.gap-3
           [:span.text-lp-accent.text-sm ">"]
           [:span.text-sm.leading-normal.text-slate-400 t]])
        [:div.mt-1.pt-4.border-t.border-lp-rule.flex.flex-wrap.gap-2
         (for [t ["Clojure" "Biff + XTDB" "Rum + HTMX" "ECharts"]]
           [:span.text-xs.tracking-wide.text-slate-500.border.border-lp-edge-2.rounded-md.px-2.py-1 t])]]]]
     ;; 03 email & privacy
     [:section.py-14.border-b.border-lp-rule
      (lp-section-label "03" "EMAIL & PRIVACY")
      [:div.lp-mail-card.rounded-xl.border.border-lp-edge.p-6
       [:div.flex.items-center.gap-2.mb-4
        [:div.text-lp-accent (lp-icon :mail "w-4 h-4")]
        [:span.text-sm.font-semibold.text-slate-200 "Transactional email only"]]
       [:p.text-sm.leading-relaxed.text-slate-400.max-w-2xl
        "The only emails sent from this domain are user-initiated authentication messages — sign-up and sign-in magic links, and verification codes."]
       [:div.grid.gap-3.mt-5 {:class "grid-cols-1 md:grid-cols-2"}
        [:div.rounded-lg.border.border-lp-rule.p-4
         [:div.text-xs.tracking-widest.text-lp-accent.mb-1 "SENT"]
         [:div.text-sm.leading-snug.text-slate-300
          "Magic links · verification codes, only on explicit request from the account holder."]]
        [:div.rounded-lg.border.border-lp-rule.p-4
         [:div.text-xs.tracking-widest.text-slate-500.mb-1 "NEVER SENT"]
         [:div.text-sm.leading-snug.text-slate-500
          "No marketing, promotional, or bulk email. No mailing lists maintained."]]]]]
     ;; 04 contact
     [:section.py-14
      [:div.grid.gap-10.items-center {:class "grid-cols-1 md:grid-cols-2"}
       [:div
        [:div.flex.items-baseline.gap-3.mb-3
         [:span.text-xs.tracking-widest.text-lp-accent "04"]
         [:h2.text-xs.font-bold.tracking-widest.text-slate-300 "CONTACT"]]
        [:p.text-sm.leading-relaxed.text-slate-400
         "Questions about this service or its email practices? Reply to any message received from this domain."]]
       [:div.flex.flex-col.gap-3.items-start
        [:a.inline-flex.items-center.gap-2.rounded-lg.bg-lp-accent.px-5.py-3.text-sm.font-semibold.no-underline.text-lp-accent-ink
         {:href "/signup"} "Sign up " (icons/arrow-right {:class "w-4 h-4"})]
        [:a.text-xs.no-underline.text-slate-400.px-1
         {:href "/signin"} "Existing account? " [:span.text-lp-accent "Sign in"]]]]]]
    [:footer.border-t.border-lp-rule
     [:div.max-w-4xl.mx-auto.px-8.py-5.flex.items-center.justify-between.gap-4.flex-wrap
      [:span.text-xs.tracking-widest.text-slate-500 "GLEANMO"]
      [:span.text-xs.text-slate-600
       "Personal quantified-self app · all rights reserved · shared for reference"]]]
    (when-some [error (:error params)]
      [:div.lp-toast.rounded-md.border.border-lp-edge-2.lp-surface.px-4.py-2.text-sm.text-rose-400
       (case error
         "not-signed-in" "You must be signed in to view that page."
         "There was an error.")])]))

(defn link-sent [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-xl.font-bold "Check your inbox"]
   [:p "We've sent a sign-in link to " [:span.font-bold (:email params)] "."]))

(defn verify-email-page [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:h2.text-2xl.font-bold (str "Sign up for " settings/app-name)]
   [:.h-3]
   (biff/form
    {:action "/auth/verify-link"
     :hidden {:token (:token params)}}
    [:div [:label {:for "email"}
           "It looks like you opened this link on a different device or browser than the one "
           "you signed up on. For verification, please enter the email you signed up with:"]]
    [:.h-3]
    [:.flex
     [:input#email {:name "email" :type "email"
                    :placeholder "Enter your email address"}]
     [:.w-3]
     [:button.btn {:type "submit"}
      "Sign in"]])
   (when-some [error (:error params)]
     #_{:clj-kondo/ignore [:unused-value]}
     [:.h-1]
     [:.text-sm.text-red-600
      (case error
        "incorrect-email" "Incorrect email address. Try again."
        "There was an error.")])))

(defn signin-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (biff/form
    {:action "/auth/send-code"
     :id "signin"
     :hidden {:on-error "/signin"}}
    (biff/recaptcha-callback "submitSignin" "signin")
    [:h2.text-2xl.font-bold "Sign in to " settings/app-name]
    [:.h-3]
    [:.flex
     [:input#email {:name "email"
                    :type "email"
                    :autocomplete "email"
                    :placeholder "Enter your email address"}]
     [:.w-3]
     [:button.btn.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignin"})
             {:type "submit"})
      "Sign in"]]
    (when-some [error (:error params)]
      [:<>
       [:.h-1]
       [:.text-sm.text-red-600
        (case error
          "recaptcha" (str "You failed the recaptcha test. Try again, "
                           "and make sure you aren't blocking scripts from Google.")
          "invalid-email" "Invalid email. Try again with a different address."
          "send-failed" (str "We weren't able to send an email to that address. "
                             "If the problem persists, try another address.")
          "invalid-link" "Invalid or expired link. Sign in to get a new link."
          "not-signed-in" "You must be signed in to view that page."
          "There was an error.")]])
    [:.h-1]
    [:.text-sm "Don't have an account yet? " [:a.link {:href "/"} "Sign up"] "."]
    [:.h-3]
    biff/recaptcha-disclosure
    email-disabled-notice)))

(defn enter-code-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (biff/form
    {:action "/auth/verify-code"
     :id "code-form"
     :hidden {:email (:email params)}}
    (biff/recaptcha-callback "submitCode" "code-form")
    [:div [:label {:for "code"} "Enter the 6-digit code that we sent to "
           [:span.font-bold (:email params)]]]
    [:.h-1]
    [:.flex
     [:input#code {:name "code" :type "text"}]
     [:.w-3]
     [:button.btn.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitCode"})
             {:type "submit"})
      "Sign in"]])
   (when-some [error (:error params)]
     #_{:clj-kondo/ignore [:unused-value]}
     [:.h-1]
     [:.text-sm.text-red-600
      (case error
        "invalid-code" "Invalid code."
        "There was an error.")])
   [:.h-3]
   (biff/form
    {:action "/auth/send-code"
     :id "signin"
     :hidden {:email (:email params)
              :on-error "/signin"}}
    (biff/recaptcha-callback "submitSignin" "signin")
    [:button.link.g-recaptcha
     (merge (when site-key
              {:data-sitekey site-key
               :data-callback "submitSignin"})
            {:type "submit"})
     "Send another code"])))

(defn signup-page [{:keys [recaptcha/site-key params] :as ctx}]
  (ui/page
   (assoc ctx ::ui/recaptcha true)
   (biff/form
    {:action "/auth/send-link"
     :id "signup"
     :hidden {:on-error "/signup"}}
    (biff/recaptcha-callback "submitSignup" "signup")
    [:h2.text-2xl.font-bold (str "Sign up for " settings/app-name)]
    [:.h-3]
    [:.flex
     [:input#email {:name "email"
                    :type "email"
                    :autocomplete "email"
                    :placeholder "Enter your email address"}]
     [:.w-3]
     [:button.btn.g-recaptcha
      (merge (when site-key
               {:data-sitekey site-key
                :data-callback "submitSignup"})
             {:type "submit"})
      "Sign up"]]
    (when-some [error (:error params)]
      [:<>
       [:.h-1]
       [:.text-sm.text-red-600
        (case error
          "recaptcha" (str "You failed the recaptcha test. Try again, "
                           "and make sure you aren't blocking scripts from Google.")
          "invalid-email" "Invalid email. Try again with a different address."
          "send-failed" (str "We weren't able to send an email to that address. "
                             "If the problem persists, try another address.")
          "There was an error.")]])
    [:.h-1]
    [:.text-sm "Already have an account? " [:a.link {:href "/signin"} "Sign in"] "."]
    [:.h-3]
    biff/recaptcha-disclosure
    email-disabled-notice)))

(def module
  {:routes [["" {:middleware [mid/wrap-redirect-signed-in]}
             ["/"                  {:get home-page}]
             ["/signup"            {:get signup-page}]
             ["/link-sent"         {:get link-sent}]
             ["/verify-link"       {:get verify-email-page}]
             ["/signin"            {:get signin-page}]
             ["/verify-code"       {:get enter-code-page}]]]})
