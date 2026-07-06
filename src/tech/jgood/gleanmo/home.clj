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
  "Landing-page line icon. `id` is one of :track :export :viz :lock :mail :gh."
  [id class]
  (let [attrs (assoc svg-attrs :class class)]
    (case id
      :track [:svg attrs
              [:path {:d "M4 12 h4 l2 5 4-12 2 7 h4"}]]
      :export [:svg attrs
               [:path {:d "M12 3 v11"}]
               [:polyline {:points "8 7 12 3 16 7"}]
               [:path {:d "M5 14 v5 a1 1 0 0 0 1 1 h12 a1 1 0 0 0 1 -1 v-5"}]]
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
             [:polyline {:points "4.5 7.5 12 13 19.5 7.5"}]]
      :gh [:svg {:xmlns "http://www.w3.org/2000/svg"
                 :viewBox "0 0 24 24"
                 :fill "currentColor"
                 :class class}
           [:path {:d "M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.09.68-.22.68-.49l-.01-1.72c-2.78.62-3.37-1.37-3.37-1.37-.46-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.62.07-.62 1 .07 1.53 1.06 1.53 1.06.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.56-1.14-4.56-5.06 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.28 2.75 1.05a9.3 9.3 0 0 1 5 0c1.91-1.33 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.93-2.34 4.79-4.57 5.05.36.32.68.94.68 1.9l-.01 2.82c0 .27.18.59.69.49A10.02 10.02 0 0 0 22 12.25C22 6.58 17.52 2 12 2z"}]])))

;; --- demo heatmap -----------------------------------------------------------
;; Deterministic sample data rendered as a real calendar heatmap: columns are
;; ISO weeks (Monday-first), the last column is the current, partial week
;; ending today, and the streak / entry counts are computed from the same
;; cells that are drawn.

(def ^:private lp-heatmap-weeks 30)

(def ^:private lp-level-entries
  "Demo log entries recorded on a day at each activity level."
  [0 1 2 4 6])

(defn- lp-demo-levels
  "n activity levels (0-4) indexed by days-ago, 0 = today. Seeded LCG so the
  page is stable across renders; recent days keep a nonzero run so the demo
  streak reads well."
  [n]
  (loop [seed 20260757 i 0 out []]
    (if (= i n)
      out
      (let [seed' (bit-and (+ (* seed 1103515245) 12345) 0x7fffffff)
            r     (/ (double seed') 0x7fffffff)
            lvl   (cond (< r 0.30) 0
                        (< r 0.55) 1
                        (< r 0.78) 2
                        (< r 0.93) 3
                        :else      4)]
        (recur seed' (inc i) (conj out lvl))))))

(defn- lp-heatmap-data []
  (let [today       (java.time.LocalDate/now)
        dow         (.getValue (.getDayOfWeek today)) ; Mon=1 .. Sun=7
        n-days      (+ (* (dec lp-heatmap-weeks) 7) dow)
        by-days-ago (lp-demo-levels n-days)]
    {:levels  (reverse by-days-ago) ; oldest day first
     :streak  (count (take-while pos? by-days-ago))
     :entries (transduce (map lp-level-entries) + by-days-ago)}))

(defn- lp-cell-bg [lvl]
  (if (zero? lvl)
    "#161b23"
    (str "rgba(34,211,238," (get [nil 0.28 0.5 0.74 1.0] lvl) ")")))

(defn- lp-heatmap-card []
  (let [{:keys [levels streak entries]} (lp-heatmap-data)]
    [:div.rounded-xl.border.border-lp-edge.overflow-hidden.lp-surface
     {:style {:box-shadow "0 24px 60px -24px rgba(0,0,0,.7)"}}
     [:div.flex.items-center.gap-2.px-4.py-3.border-b.border-lp-rule
      (for [c ["#ff5f57" "#febc2e" "#28c840"]]
        [:span.block.rounded-full {:style {:width "11px" :height "11px" :background c}}])
      [:span.ml-2.text-xs.text-slate-500
       (str "activity · last " lp-heatmap-weeks " weeks")]]
     [:div.p-5.overflow-x-auto
      [:div.flex.items-baseline.justify-between.mb-4
       [:span.text-sm.font-semibold.text-slate-300 (str entries " entries")]
       [:span.text-xs.text-slate-500 (str streak "-day streak")]]
      [:div {:style {:display            "grid"
                     :grid-template-rows "repeat(7,12px)"
                     :grid-auto-flow     "column"
                     :grid-auto-columns  "12px"
                     :gap                "3px"
                     :justify-content    "start"}}
       (for [lvl levels]
         [:span {:style {:width "12px" :height "12px" :border-radius "2px"
                         :background (lp-cell-bg lvl)}}])]
      [:div.flex.items-center.justify-end.gap-2.mt-4
       [:span.text-xs.text-slate-500 "less"]
       (for [lvl (range 5)]
         [:span {:style {:width "11px" :height "11px" :border-radius "2px"
                         :background (lp-cell-bg lvl)}}])
       [:span.text-xs.text-slate-500 "more"]]]]))

(defn- lp-section-label
  "Numbered section header with hairline rule."
  [n title]
  [:div.flex.items-baseline.gap-3.mb-6
   [:span.text-xs.tracking-widest.text-lp-accent n]
   [:h2.text-xs.font-bold.tracking-widest.text-slate-300 title]
   [:span.flex-1.h-px.lp-rule-bg]])

(def ^:private lp-github-url "https://github.com/jgoodhcg/gleanmo")

(defn home-page [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:div.lp-landing.min-h-screen.font-mono.text-slate-200.lp-hero-glow
    [:header.sticky.top-0.z-10.border-b.border-lp-rule.lp-header-blur
     [:div.max-w-6xl.mx-auto.px-8.py-4.flex.items-center.justify-between.gap-4
      [:div.flex.items-center.gap-2
       [:span.block.w-4.h-4.rounded.bg-lp-accent]
       [:span.text-sm.font-bold.tracking-widest "GLEANMO"]]
      [:div.flex.items-center.gap-3
       [:a.text-slate-400 {:href lp-github-url :aria-label "Gleanmo on GitHub"}
        (lp-icon :gh "w-5 h-5")]
       [:a.rounded-md.border.border-lp-edge-2.px-4.py-2.text-xs.no-underline.text-slate-300.whitespace-nowrap
        {:href "/signin"} "sign in"]
       [:a.rounded-md.bg-lp-accent.px-4.py-2.text-xs.font-semibold.no-underline.text-lp-accent-ink.whitespace-nowrap
        {:href "/signup"} "sign up"]]]]
    [:main.max-w-6xl.mx-auto.px-8
     ;; hero
     [:section.py-20.border-b.border-lp-rule
      [:div.grid.gap-14.items-center {:class "grid-cols-1 md:grid-cols-2"}
       [:div
        [:div.text-5xl.md:text-6xl.font-extrabold.tracking-tight.text-lp-accent "Gleanmo"]
        [:h1.mt-4.text-3xl.md:text-4xl.font-bold.tracking-tight.leading-tight.text-slate-100
         "Personal quantified-self app"]
        [:p.mt-5.text-base.leading-relaxed.text-slate-400.max-w-md
         "Tracks habits, meditation, reading, projects, medications, and health metrics in one private dashboard."]
        [:div.flex.flex-wrap.items-center.gap-3.mt-8
         [:a.inline-flex.items-center.gap-2.rounded-full.bg-lp-accent.px-6.text-sm.font-semibold.no-underline.text-lp-accent-ink
          {:href "/signup" :class "py-2.5"}
          "Sign up " (icons/arrow-right {:class "w-4 h-4"})]
         [:a.inline-flex.items-center.gap-2.rounded-full.border.border-lp-edge-2.lp-surface.px-5.text-sm.font-semibold.no-underline.text-slate-200
          {:href lp-github-url :class "py-2.5"}
          (lp-icon :gh "w-4 h-4") "View on GitHub"]]
        [:p.mt-5.text-xs.leading-relaxed.text-slate-500.max-w-md
         "Personal-use software, built first for its creator. Public for reference — not a plug-and-play product. Sign-in is by email magic link."]]
       [:div.hidden {:class "md:block"} (lp-heatmap-card)]]]
     ;; 01 what it supports
     [:section.py-14.border-b.border-lp-rule
      (lp-section-label "01" "WHAT IT SUPPORTS")
      [:div.grid.gap-4
       {:class "grid-cols-1 sm:grid-cols-2 lg:grid-cols-4"}
       [:div.lp-surface.border.border-lp-edge.rounded-xl.p-5
        [:div.text-lp-accent.mb-3 (lp-icon :track "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Track many things"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Habits, meditation, reading, projects, medications, exercise, and health metrics — through one schema-driven CRUD engine."]]
       [:div.lp-surface.border.border-lp-edge.rounded-xl.p-5
        [:div.text-lp-accent.mb-3 (lp-icon :viz "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Visualizations"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Calendar heatmaps and related charts over the activity logs."]]
       [:div.lp-surface.border.border-lp-edge.rounded-xl.p-5
        [:div.text-lp-accent.mb-3 (lp-icon :lock "w-5 h-5")]
        [:div.text-sm.font-semibold.text-slate-200 "Private by default"]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Privacy-aware filtering, with email magic-link auth and reCAPTCHA."]]
       [:div.rounded-xl.p-5.border.border-dashed.border-lp-edge-2
        [:div.text-slate-500.mb-3 (lp-icon :export "w-5 h-5")]
        [:div.flex.items-center.gap-2.flex-wrap
         [:span.text-sm.font-semibold.text-slate-300 "Data export & API"]
         [:span.text-xs.tracking-widest.text-slate-500.border.border-dashed.border-lp-edge-2.rounded.py-px
          {:class "px-1.5"} "PLANNED"]]
        [:p.mt-2.text-sm.leading-normal.text-slate-500
         "Full data export and a read API. Not built yet — on the roadmap."]]]]
     ;; 02 email & privacy
     [:section.py-14.border-b.border-lp-rule
      (lp-section-label "02" "EMAIL & PRIVACY")
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
          "No marketing, promotional, or bulk email. No mailing lists maintained."]]]]]]
    [:footer.border-t.border-lp-rule
     [:div.max-w-6xl.mx-auto.px-8.py-5.flex.items-center.justify-between.gap-4.flex-wrap
      [:span.text-xs.tracking-widest.text-slate-500 "GLEANMO"]
      [:span.text-xs.text-slate-600
       "Built by "
       [:a.text-slate-400.no-underline {:href "https://github.com/jgoodhcg"} "jgoodhcg"]
       " in Clojure — Biff, XTDB, Rum, HTMX, ECharts · "
       [:a.text-slate-400.no-underline {:href lp-github-url} "source"]
       " · all rights reserved · shared for reference"]]]
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
