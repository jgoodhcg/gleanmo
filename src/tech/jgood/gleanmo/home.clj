(ns tech.jgood.gleanmo.home
  (:require [com.biffweb :as biff]
            [tech.jgood.gleanmo.app.layout :as layout]
            [tech.jgood.gleanmo.home.sample-charts :as charts]
            [tech.jgood.gleanmo.middleware :as mid]
            [tech.jgood.gleanmo.ui :as ui]
            [tech.jgood.gleanmo.settings :as settings]))

(defn- lp-icon
  "Landing-page icon. `id` is :gh (the GitHub mark)."
  [id class]
  (case id
    :gh [:svg {:xmlns "http://www.w3.org/2000/svg"
               :viewBox "0 0 24 24"
               :fill "currentColor"
               :class class}
         [:path {:d "M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.09.68-.22.68-.49l-.01-1.72c-2.78.62-3.37-1.37-3.37-1.37-.46-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.62.07-.62 1 .07 1.53 1.06 1.53 1.06.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.56-1.14-4.56-5.06 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.28 2.75 1.05a9.3 9.3 0 0 1 5 0c1.91-1.33 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.93-2.34 4.79-4.57 5.05.36.32.68.94.68 1.9l-.01 2.82c0 .27.18.59.69.49A10.02 10.02 0 0 0 22 12.25C22 6.58 17.52 2 12 2z"}]]))

(def ^:private lp-github-url "https://github.com/jgoodhcg/gleanmo")

;; What Gleanmo is, in Simplified Technical English: short sentences, one
;; statement each, active voice. The email line stays: the mail provider's
;; review relies on the site saying that only sign-in email is sent.
(def ^:private about-lines
  ["Gleanmo is a personal tracking app."
   "Record habits, exercise, reading, meditation, projects, and tasks."
   "Record health data: mood, symptoms, medication, and BM logs."
   "Use timers to record how long an activity takes."
   "See your records in calendars and charts."
   "Set a goal. Gleanmo shows your progress to the goal."
   "Mark a record as sensitive. Gleanmo hides sensitive records until you show them."
   "Give an AI agent an API token. The token gets access only to the data that you select."
   "Sign in with a code that Gleanmo sends to your email. Gleanmo sends no other email."
   "One person makes Gleanmo for personal use. The source code is public."])

(defn- sample-chart
  [{:keys [title detail chart]} & {:keys [class]}]
  (layout/card
   {:class class}
   [:div.text-sm.font-semibold.text-gray-200 title]
   [:div.text-xs.text-gray-500.mt-1.mb-3 detail]
   chart))

(defn home-page [{:keys [params] :as ctx}]
  (ui/page
   ctx
   [:div.min-h-screen.bg-dark-bg.font-mono.text-gray-200
    [:header.border-b.border-dark
     [:div.max-w-4xl.mx-auto.px-4.py-4.flex.items-center.justify-between.gap-4
      [:span.text-sm.font-bold.tracking-widest "GLEANMO"]
      [:nav.flex.items-center.gap-4.text-sm
       [:a.link {:href "/signin"} "sign in"]
       [:a.link {:href "/signup"} "sign up"]
       [:a.text-gray-400 {:href lp-github-url :aria-label "Gleanmo on GitHub"}
        (lp-icon :gh "w-5 h-5")]]]]
    [:main.max-w-4xl.mx-auto.px-4.py-8.space-y-8
     [:ul.list-disc.pl-5.space-y-2.text-sm.leading-relaxed.text-gray-300
      (for [line about-lines]
        [:li {:key line} line])]
     [:div.space-y-4
      (layout/section-header "SAMPLE CHARTS" "made-up data")
      (sample-chart (charts/habit-heatmap))
      [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
       (sample-chart (charts/exercise-goal))
       (sample-chart (charts/reading-in-progress))
       (sample-chart (charts/reading-finished))
       (sample-chart (charts/reading-audiobook))]]]
    [:footer.border-t.border-dark
     [:div.max-w-4xl.mx-auto.px-4.py-4.text-xs.text-gray-500
      "Built by "
      [:a.link {:href "https://github.com/jgoodhcg"} "jgoodhcg"]
      " · "
      [:a.link {:href lp-github-url} "source"]]]
    (when-some [error (:error params)]
      [:div.lp-toast.rounded-md.border.border-dark.bg-dark-surface.px-4.py-2.text-sm.text-rose-400
       (case error
         "not-signed-in" "You must be signed in to view that page."
         "There was an error.")])]))

;; --- auth pages -------------------------------------------------------------

(defn- lp-auth-shell
  "Landing-styled shell for the auth pages: dark backdrop, minimal header
  linking home, and a centered card."
  [ctx & content]
  (ui/page
   ctx
   [:div.lp-landing.min-h-screen.font-mono.text-slate-200.lp-hero-glow
    [:header.border-b.border-lp-rule.lp-header-blur
     [:div.max-w-6xl.mx-auto.px-8.py-4.flex.items-center.justify-between.gap-4
      [:a.flex.items-center.gap-2.no-underline.text-slate-200 {:href "/"}
       [:span.block.w-4.h-4.rounded.bg-lp-accent]
       [:span.text-sm.font-bold.tracking-widest "GLEANMO"]]
      [:a.text-slate-400 {:href lp-github-url :aria-label "Gleanmo on GitHub"}
       (lp-icon :gh "w-5 h-5")]]]
    [:main.max-w-md.mx-auto.px-6.py-16
     (into [:div.lp-surface.border.border-lp-edge.rounded-xl.p-8] content)]]))

(defn- lp-email-input [attrs]
  [:input.w-full.rounded-lg.border.border-lp-edge-2.px-4.text-sm.text-slate-200
   (merge {:class "py-2.5 bg-transparent placeholder:text-slate-600 focus:outline-none focus:border-lp-accent"}
          attrs)])

(defn- lp-submit-btn [attrs label]
  [:button.w-full.mt-3.rounded-lg.bg-lp-accent.px-5.text-sm.font-semibold.text-lp-accent-ink
   (update (merge {:type "submit"} attrs) :class #(str "py-2.5 " %))
   label])

(defn- lp-auth-error
  "Error banner for auth cards. `msgs` maps error codes to copy."
  [error msgs]
  (when error
    [:p.mt-3.text-sm.leading-normal.text-rose-400
     (get msgs error "There was an error.")]))

(defn- lp-recaptcha-attrs [site-key callback]
  (merge {:class "g-recaptcha"}
         (when site-key
           {:data-sitekey site-key
            :data-callback callback})))

(def ^:private lp-send-errors
  {"recaptcha" (str "You failed the recaptcha test. Try again, "
                    "and make sure you aren't blocking scripts from Google.")
   "invalid-email" "Invalid email. Try again with a different address."
   "send-failed" (str "We weren't able to send an email to that address. "
                      "If the problem persists, try another address.")})

(defn link-sent [{:keys [params] :as ctx}]
  (lp-auth-shell
   ctx
   [:h2.text-xl.font-bold.text-slate-100 "Check your inbox"]
   [:p.mt-3.text-sm.leading-relaxed.text-slate-400
    "We've sent a sign-in link to "
    [:span.font-semibold.text-slate-200 (:email params)] "."]))

(defn verify-email-page [{:keys [params] :as ctx}]
  (lp-auth-shell
   ctx
   [:h2.text-xl.font-bold.text-slate-100 (str "Sign up for " settings/app-name)]
   [:p.mt-3.text-sm.leading-relaxed.text-slate-400
    "It looks like you opened this link on a different device or browser than the one "
    "you signed up on. For verification, please enter the email you signed up with:"]
   [:div.mt-5
    (biff/form
     {:action "/auth/verify-link"
      :hidden {:token (:token params)}}
     (lp-email-input {:id "email" :name "email" :type "email"
                      :placeholder "Enter your email address"})
     (lp-submit-btn {} "Sign in"))]
   (lp-auth-error (:error params)
                  {"incorrect-email" "Incorrect email address. Try again."})))

(defn signin-page [{:keys [recaptcha/site-key params] :as ctx}]
  (lp-auth-shell
   (assoc ctx ::ui/recaptcha true)
   [:h2.text-xl.font-bold.text-slate-100 (str "Sign in to " settings/app-name)]
   [:p.mt-3.text-sm.leading-relaxed.text-slate-400
    "We'll email you a 6-digit code — no password needed."]
   [:div.mt-5
    (biff/form
     {:action "/auth/send-code"
      :id "signin"
      :hidden {:on-error "/signin"}}
     (biff/recaptcha-callback "submitSignin" "signin")
     (lp-email-input {:id "email" :name "email" :type "email"
                      :autocomplete "email"
                      :placeholder "Enter your email address"})
     (lp-submit-btn (lp-recaptcha-attrs site-key "submitSignin") "Sign in"))]
   (lp-auth-error (:error params)
                  (assoc lp-send-errors
                         "invalid-link" "Invalid or expired link. Sign in to get a new link."
                         "not-signed-in" "You must be signed in to view that page."))
   [:p.mt-5.text-sm.text-slate-400
    "Don't have an account yet? "
    [:a.text-lp-accent.no-underline {:href "/signup"} "Sign up"] "."]
   [:div.mt-4.text-xs.text-slate-600 biff/recaptcha-disclosure]))

(defn enter-code-page [{:keys [recaptcha/site-key params] :as ctx}]
  (lp-auth-shell
   (assoc ctx ::ui/recaptcha true)
   [:h2.text-xl.font-bold.text-slate-100 "Enter your code"]
   [:p.mt-3.text-sm.leading-relaxed.text-slate-400
    "Enter the 6-digit code that we sent to "
    [:span.font-semibold.text-slate-200 (:email params)] "."]
   [:div.mt-5
    (biff/form
     {:action "/auth/verify-code"
      :id "code-form"
      :hidden {:email (:email params)}}
     (biff/recaptcha-callback "submitCode" "code-form")
     (lp-email-input {:id "code" :name "code" :type "text"
                      :inputmode "numeric"
                      :autocomplete "one-time-code"
                      :placeholder "6-digit code"})
     (lp-submit-btn (lp-recaptcha-attrs site-key "submitCode") "Sign in"))]
   (lp-auth-error (:error params) {"invalid-code" "Invalid code."})
   [:div.mt-5
    (biff/form
     {:action "/auth/send-code"
      :id "signin"
      :hidden {:email (:email params)
               :on-error "/signin"}}
     (biff/recaptcha-callback "submitSignin" "signin")
     [:button.text-sm.text-lp-accent
      (merge (lp-recaptcha-attrs site-key "submitSignin") {:type "submit"})
      "Send another code"])]))

(defn signup-page [{:keys [recaptcha/site-key params] :as ctx}]
  (lp-auth-shell
   (assoc ctx ::ui/recaptcha true)
   [:h2.text-xl.font-bold.text-slate-100 (str "Sign up for " settings/app-name)]
   [:p.mt-3.text-sm.leading-relaxed.text-slate-400
    "We'll email you a magic link — no password needed."]
   [:div.mt-5
    (biff/form
     {:action "/auth/send-link"
      :id "signup"
      :hidden {:on-error "/signup"}}
     (biff/recaptcha-callback "submitSignup" "signup")
     (lp-email-input {:id "email" :name "email" :type "email"
                      :autocomplete "email"
                      :placeholder "Enter your email address"})
     (lp-submit-btn (lp-recaptcha-attrs site-key "submitSignup") "Sign up"))]
   (lp-auth-error (:error params) lp-send-errors)
   [:p.mt-5.text-sm.text-slate-400
    "Already have an account? "
    [:a.text-lp-accent.no-underline {:href "/signin"} "Sign in"] "."]
   [:div.mt-4.text-xs.text-slate-600 biff/recaptcha-disclosure]))

(def module
  {:routes [["" {:middleware [mid/wrap-redirect-signed-in]}
             ["/"                  {:get home-page}]
             ["/signup"            {:get signup-page}]
             ["/link-sent"         {:get link-sent}]
             ["/verify-link"       {:get verify-email-page}]
             ["/signin"            {:get signin-page}]
             ["/verify-code"       {:get enter-code-page}]]]})
