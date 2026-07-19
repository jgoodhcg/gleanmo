(ns tech.jgood.gleanmo.app.boulder
  "Custom bouldering flow: one screen to run a gym session — log one attempt
   at a time against gym problems — instead of bouncing between generic CRUD
   forms. Mirrors the workout screen's state machine and visual language
   (see tech.jgood.gleanmo.app.workout).

   Interaction design rationale: an attempt is an interval, same as an
   exercise set — Start attempt when climbing begins, and the trailing
   interaction is the log itself: while an attempt runs the form's primary
   action 'Log attempt' records the result and closes the interval, so
   forgetting to stop is impossible on the normal path. Logging with no
   attempt running is still accepted rather than lost: the attempt is
   created closed with beginning = end = the log moment (duration unknown).
   The problem picker defaults to the last problem attempted this session
   since repeats on the same problem are the common case; new problems are
   created inline (circuit difficulty, hold color, wall)."
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(def ^:private screen-url "/app/boulder/session")

(defn- redirect-param [] (java.net.URLEncoder/encode screen-url "UTF-8"))

(defn- open-session
  [{:keys [biff/db session] :as ctx}]
  (first (queries/active-timers-for-user
          db (:uid session) :boulder-session
          :boulder-session/beginning :boulder-session/end
          :user-settings (queries/resolve-user-settings ctx))))

(defn- parse-int* [s] (when-not (str/blank? s) (parse-long s)))

(defn- problem-label
  "Compact one-line identity for a problem: 'pink v0-v2 · green · comp'."
  [p]
  (->> [(:boulder-problem/difficulty p)
        (:boulder-problem/hold-color p)
        (:boulder-problem/wall p)
        (:boulder-problem/label p)]
       (remove str/blank?)
       (str/join " · ")))

(defn- fmt-clock
  "m:ss, like a stopwatch — used for attempt durations."
  [beginning end]
  (let [secs (t/seconds (t/between beginning end))]
    (format "%d:%02d" (quot secs 60) (mod secs 60))))

(defn- attempt-detail
  "Right-aligned detail for history rows: '1:32 · sent · flash', 'top',
   '3 attempts'. Zero-length intervals (backfilled logs) show no clock."
  [a]
  (let [{:boulder-attempt/keys [beginning end]} a
        clock (when (and beginning end (t/< beginning end))
                (fmt-clock beginning end))
        parts (cond-> []
                clock (conj clock)
                (:boulder-attempt/sent a)  (conj "sent")
                (:boulder-attempt/flash a) (conj "flash")
                (and (:boulder-attempt/top a)
                     (not (:boulder-attempt/sent a))) (conj "top")
                (some-> (:boulder-attempt/laps a) (> 1))
                (conj (str (:boulder-attempt/laps a) " laps"))
                (some-> (:boulder-attempt/attempts a) (> 1))
                (conj (str (:boulder-attempt/attempts a) " tries")))]
    (if (seq parts) (str/join " · " parts) "attempt")))

;; Same client-side ticking as the workout screen: session duration renders
;; from the wall clock so the page needs no reloads.
(def ^:private tick-script
  "document.querySelectorAll('[data-epoch-ms]').forEach(function (el) {
     function render() {
       var s = Math.max(0, Math.floor((Date.now() - Number(el.dataset.epochMs)) / 1000));
       var m = Math.floor(s / 60);
       el.textContent = el.dataset.fmt === 'session'
         ? (m < 60 ? m + ' min' : Math.floor(m / 60) + 'h ' + (m % 60) + 'm')
         : m + ':' + String(s % 60).padStart(2, '0');
     }
     render();
     setInterval(render, 1000);
   });")

(defn- epoch-ms [instant] (str (t/millis (t/between (t/epoch) instant))))

;; Wires the attempt form: 'new problem' fields reveal when the picker is on
;; __new__, sent/flash/top toggle chips flip hidden inputs (flash implies
;; sent), and −/+ steppers adjust laps/retries.
(def ^:private form-script
  "(function(){
     var form=document.getElementById('bd-attempt-form'); if(!form) return;
     var sel=form.querySelector('[name=problem-id]');
     var np=document.getElementById('bd-new-problem');
     function syncNew(){ if(np) np.classList.toggle('hidden', sel.value!=='__new__'); }
     sel.addEventListener('change',syncNew);
     form.querySelectorAll('[data-toggle-chip]').forEach(function(b){
       b.addEventListener('click',function(){
         var name=b.dataset.toggleChip;
         var input=form.querySelector('[name='+name+']');
         var on=input.value!=='true';
         input.value=on?'true':'false';
         b.classList.toggle('bg-neon-cyan',on); b.classList.toggle('text-black',on);
         b.classList.toggle('text-gray-500',!on);
         if(name==='flash'&&on){
           var s=form.querySelector('[name=sent]');
           if(s.value!=='true'){ form.querySelector('[data-toggle-chip=sent]').click(); }
         } }); });
     form.querySelectorAll('[data-adjust]').forEach(function(b){
       b.addEventListener('click',function(){
         var p=b.dataset.adjust.split(':'); var name=p[0]; var dir=Number(p[1]);
         var i=form.querySelector('[name='+name+']');
         var min=(name==='laps')?1:0;
         i.value=Math.max(min,(parseInt(i.value,10)||0)+dir); }); });
     syncNew();
   })();")

(defn- stepper-ctrl
  "−/+ flanking a borderless numeric input, 44px touch targets — same control
   as the workout screen's reps/weight steppers."
  [input-name value]
  [:div.flex.items-center.gap-1.5.shrink-0
   [:button {:type "button" :data-adjust (str input-name ":-1")
             :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "−"]
   [:input {:type "number" :step "1" :name input-name :value value
            :class "w-12 text-center text-xl font-bold text-white bg-transparent border-none p-0 tabular-nums"}]
   [:button {:type "button" :data-adjust (str input-name ":1")
             :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "+"]])

(defn- toggle-chip
  [input-name label on?]
  [:button {:type "button" :data-toggle-chip input-name
            :class (str "px-4 py-2.5 rounded-lg text-sm font-bold border border-dark "
                        (if on? "bg-neon-cyan text-black" "text-gray-500 bg-dark-surface"))}
   label])

(defn- datalist
  [id values]
  [:datalist {:id id}
   (for [v (->> values (remove str/blank?) distinct sort)]
     [:option {:value v}])])

(defn- new-problem-fields
  "Inline problem creation: circuit difficulty, hold color, wall — free text
   with datalists sourced from the user's existing problems, since these are
   gym vocabulary that recurs constantly."
  [problems]
  [:div {:id "bd-new-problem" :class "hidden flex flex-col gap-3 mt-3"}
   [:input {:type "text" :name "new-difficulty" :list "bd-difficulties"
            :placeholder "Difficulty (e.g. pink v0-v2)" :autocomplete "off"
            :class "form-input w-full"}]
   [:div.flex.gap-3
    [:input {:type "text" :name "new-hold-color" :list "bd-colors"
             :placeholder "Hold color" :autocomplete "off"
             :class "form-input w-full"}]
    [:input {:type "text" :name "new-wall" :list "bd-walls"
             :placeholder "Wall" :autocomplete "off"
             :class "form-input w-full"}]]
   (datalist "bd-difficulties" (map :boulder-problem/difficulty problems))
   (datalist "bd-colors" (map :boulder-problem/hold-color problems))
   (datalist "bd-walls" (map :boulder-problem/wall problems))])

(defn- attempt-form
  "The attempt log form: searchable problem select (Choices.js, same as CRUD
   forms) with an inline new-problem branch, sent/flash/top toggle chips,
   laps/retries steppers, optional duration, and a Log attempt primary."
  [session problems selected-problem-id]
  (let [gym       (:boulder-session/gym session)
        ;; problems at this gym first, everything else after
        ordered   (concat (filter #(= gym (:boulder-problem/gym %)) problems)
                          (remove #(= gym (:boulder-problem/gym %)) problems))
        available (remove :boulder-problem/archived ordered)]
    (biff/form
     {:id "bd-attempt-form"
      :action (str screen-url "/" (:xt/id session) "/attempt") :method "post"}
     [:div {:class "text-[10px] font-semibold tracking-widest text-gray-500 mb-2"} "PROBLEM"]
     [:div.mb-2
      [:select.form-select.w-full {:name "problem-id" :required true
                                   :data-enhance "choices"
                                   :data-placeholder "Problem"}
       [:option {:value "__new__"
                 :selected (nil? selected-problem-id)} "＋ New problem"]
       (for [p available]
         [:option {:value (:xt/id p)
                   :selected (= (:xt/id p) selected-problem-id)}
          (str (problem-label p)
               (when-not (= gym (:boulder-problem/gym p))
                 (str " (" (:boulder-problem/gym p) ")")))])]]
     (new-problem-fields problems)
     [:div {:class "text-[10px] font-semibold tracking-widest text-gray-500 mt-5 mb-2"} "RESULT"]
     [:div.flex.gap-2
      (toggle-chip "sent" "Sent" false)
      (toggle-chip "flash" "Flash" false)
      (toggle-chip "top" "Top" false)]
     [:input {:type "hidden" :name "sent" :value "false"}]
     [:input {:type "hidden" :name "flash" :value "false"}]
     [:input {:type "hidden" :name "top" :value "false"}]
     [:div.flex.items-center.justify-between.gap-3.py-2.mt-3
      [:span {:class "text-[10px] font-semibold tracking-widest text-gray-500"} "LAPS"]
      (stepper-ctrl "laps" "1")]
     [:div.flex.items-center.justify-between.gap-3.py-2
      [:span {:class "text-[10px] font-semibold tracking-widest text-gray-500"} "TRIES"]
      (stepper-ctrl "attempts" "1")]
     [:button {:type "submit"
               :class "w-full py-4 rounded-xl text-sm font-bold bg-neon-cyan text-black mt-3"}
      "Log attempt"])))

(defn- running-attempt-panel
  "The recording hero card while an attempt's timer runs: pulsing dot,
   ATTEMPT · RECORDING, big live m:ss clock. The log form below is what
   closes it."
  [running]
  [:div {:class "rounded-xl border p-5"
         :style {:border-color "rgba(34,211,238,.3)"
                 :background "rgba(34,211,238,.05)"}}
   [:div.flex.items-center.gap-2
    [:span {:class "w-2 h-2 rounded-full bg-neon-cyan animate-pulse"}]
    [:span {:class "text-[11px] font-semibold tracking-widest text-gray-400"}
     "ATTEMPT · RECORDING"]]
   [:div {:class "text-[46px] font-bold text-neon-cyan tabular-nums leading-tight mt-2"
          :data-epoch-ms (epoch-ms (:boulder-attempt/beginning running))} "…"]])

(defn- attempt-card
  "One logged attempt in the session history: problem identity left, result
   detail right, both linking to the attempt's edit form."
  [a problems-by-id]
  (let [p (get problems-by-id (:boulder-attempt/problem-id a))]
    [:a {:class "flex items-baseline justify-between gap-3 no-underline hover:text-neon-cyan rounded-xl border border-dark bg-dark-surface px-4 py-3"
         :href (str "/app/crud/form/boulder-attempt/edit/" (:xt/id a)
                    "?redirect=" (redirect-param))}
     [:span.text-sm.text-gray-200 (if p (problem-label p) "Unknown problem")]
     [:span {:class "text-xs text-gray-400 tabular-nums whitespace-nowrap"}
      (attempt-detail a)]]))

(defn- active-session-view
  [{:keys [biff/db session]} boulder-session]
  (let [session-id  (:xt/id boulder-session)
        user-id     (:uid session)
        attempts    (queries/attempts-for-boulder-session db user-id session-id)
        running     (first (filter #(nil? (:boulder-attempt/end %)) attempts))
        done        (filter :boulder-attempt/end attempts)
        problems    (queries/boulder-problems-for-user db user-id)
        probs-by-id (into {} (map (juxt :xt/id identity)) problems)
        last-prob   (or (:boulder-attempt/problem-id (last done))
                        (:boulder-attempt/problem-id running))
        n           (count done)
        sends       (count (filter :boulder-attempt/sent done))]
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 pb-24 space-y-5"}
     [:div.flex.items-start.justify-between.gap-3
      [:div
       [:h1.text-2xl.font-bold.text-white "Bouldering"]
       [:p.text-xs.text-gray-500.mt-1.tabular-nums
        (str (:boulder-session/gym boulder-session) " · ")
        [:span {:data-epoch-ms (epoch-ms (:boulder-session/beginning boulder-session))
                :data-fmt "session"} "…"]
        (str " · " n (if (= 1 n) " attempt" " attempts")
             (when (pos? sends) (str " · " sends (if (= 1 sends) " send" " sends"))))]]
      (biff/form {:action (str screen-url "/" session-id "/end"), :method "post"}
                 [:button {:type "submit"
                           :class "px-3.5 py-2 rounded-lg text-xs font-semibold text-red-400 bg-transparent border border-red-400/30 whitespace-nowrap"}
                  "End session"])]

     (if running
       (running-attempt-panel running)
       (biff/form {:action (str screen-url "/" session-id "/attempt/start")
                   :method "post"}
                  [:button {:type "submit"
                            :class "w-full py-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                   "Start attempt"]))

     [:div {:class "rounded-xl border border-dark bg-dark-surface p-4 sm:p-6"}
      [:h2.text-sm.font-bold.text-white.mb-4
       (if running "Log attempt" "Log a finished attempt")]
      (attempt-form boulder-session problems last-prob)]

     [:div
      [:div.flex.items-baseline.gap-3.mb-3
       [:h2 {:class "text-[11px] font-bold tracking-widest text-gray-400"} "THIS SESSION"]
       [:span {:class "flex-1 h-px bg-dark-border"}]
       [:span.text-xs.text-gray-500.tabular-nums
        (str n (if (= 1 n) " attempt" " attempts"))]]
      (if (seq done)
        [:div {:class "flex flex-col gap-2.5"}
         (for [a (reverse done)]
           ^{:key (:xt/id a)}
           (attempt-card a probs-by-id))]
        [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-xs text-gray-500"}
         "Nothing logged yet — climb, then log the attempt here."])]

     [:div.pt-4
      [:a.link.text-xs {:href (str "/app/crud/form/boulder-session/edit/" session-id
                                   "?redirect=" (redirect-param))}
       "edit session"]]
     [:script (biff/unsafe (str tick-script "\n" form-script))]]))

(defn- idle-view
  [{:keys [biff/db session]}]
  (let [user-id (:uid session)
        recent  (queries/recent-boulder-sessions-for-user db user-id 5)
        gyms    (->> recent (map :boulder-session/gym) (remove str/blank?) distinct)]
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 space-y-6"}
     [:h1.text-2xl.font-bold.text-white "Bouldering"]
     (biff/form {:action (str screen-url "/start"), :method "post"}
                [:div {:class "text-[10px] font-semibold tracking-widest text-gray-500 mb-2"} "GYM"]
                [:input {:type "text" :name "gym" :required true :list "bd-gyms"
                         :value (first gyms) :autocomplete "off"
                         :class "form-input w-full mb-3"}]
                [:datalist {:id "bd-gyms"}
                 (for [g gyms] [:option {:value g}])]
                [:button {:type "submit"
                          :class "w-full py-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                 "Start session"])
     (when (seq recent)
       [:div.space-y-2
        [:h2 {:class "text-[11px] font-bold tracking-widest text-gray-400"} "RECENT SESSIONS"]
        (for [s recent]
          ^{:key (:xt/id s)}
          [:a.block.no-underline
           {:href (str screen-url "/" (:xt/id s) "/summary")}
           [:div {:class "rounded-lg border border-dark bg-dark-surface hover:border-neon-cyan p-3 text-sm text-gray-300"}
            (str (:boulder-session/gym s) " · "
                 (or (:boulder-session/label s)
                     (:boulder-session/beginning s)))]])])]))

(defn- stat-tile
  [label value]
  [:div {:class "rounded-lg border border-dark bg-dark-surface px-4 py-3"}
   [:div {:class "text-[10px] tracking-widest text-gray-500"} label]
   [:div.text-xl.font-bold.text-white.tabular-nums.mt-0.5 value]])

(defn- session-summary-view
  "Read-only recap of one session: headline stats plus every attempt, so a
   finished gym visit is legible at a glance."
  [{:keys [biff/db session]} boulder-session]
  (let [session-id  (:xt/id boulder-session)
        user-id     (:uid session)
        attempts    (queries/attempts-for-boulder-session db user-id session-id)
        problems    (queries/boulder-problems-for-user db user-id)
        probs-by-id (into {} (map (juxt :xt/id identity)) problems)
        sends       (count (filter :boulder-attempt/sent attempts))
        flashes     (count (filter :boulder-attempt/flash attempts))
        n-problems  (count (distinct (keep :boulder-attempt/problem-id attempts)))
        ended       (:boulder-session/end boulder-session)]
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 pb-24 space-y-6"}
     [:div
      [:a.link.text-xs {:href screen-url} "← bouldering"]
      [:h1.text-2xl.font-bold.text-white.mt-2
       (str (:boulder-session/gym boulder-session) " session")]
      [:p.text-sm.text-gray-400.mt-1
       (str (:boulder-session/beginning boulder-session)
            (if ended "" " · in progress"))]]

     [:div {:class "grid grid-cols-2 sm:grid-cols-4 gap-3"}
      (stat-tile "ATTEMPTS" (str (count attempts)))
      (stat-tile "SENDS" (str sends))
      (stat-tile "FLASHES" (str flashes))
      (stat-tile "PROBLEMS" (str n-problems))]

     [:div
      [:div.flex.items-baseline.gap-3.mb-3
       [:h2 {:class "text-[11px] font-bold tracking-widest text-gray-400"} "ATTEMPTS"]
       [:span {:class "flex-1 h-px bg-dark-border"}]]
      (if (seq attempts)
        [:div {:class "flex flex-col gap-2.5"}
         (for [a attempts]
           ^{:key (:xt/id a)}
           (attempt-card a probs-by-id))]
        [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-xs text-gray-500"}
         "No attempts in this session."])]

     [:div.pt-2
      [:a.link.text-xs {:href (str "/app/crud/form/boulder-session/edit/" session-id
                                   "?redirect=" (redirect-param))}
       "edit session"]]]))

(defn session-summary-page
  [{:keys [session] :as ctx}]
  (let [entity-id (java.util.UUID/fromString (:id (:path-params ctx)))
        sess      (queries/get-entity-for-user (:biff/db ctx) entity-id
                                               (:uid session) :boulder-session)]
    (ui/page
     ctx
     (side-bar
      ctx
      (if sess
        (session-summary-view ctx sess)
        [:div {:class "max-w-2xl mx-auto p-6"}
         [:p.text-gray-400 "Session not found."]])))))

(defn boulder-page
  [ctx]
  (let [sess (open-session ctx)]
    (ui/page
     ctx
     (side-bar
      ctx
      (if sess
        (active-session-view ctx sess)
        (idle-view ctx))))))

(defn- redirect-home
  []
  {:status 303 :headers {"location" screen-url}})

(defn- owned-entity
  [{:keys [biff/db session path-params]} entity-key]
  (let [entity-id (java.util.UUID/fromString (:id path-params))]
    (queries/get-entity-for-user db entity-id (:uid session) entity-key)))

(defn start-session!
  [{:keys [session params] :as ctx}]
  (when-not (open-session ctx)
    (mutations/create-entity! ctx {:entity-key :boulder-session
                                   :data {:user/id (:uid session)
                                          :boulder-session/gym (str/trim (or (:gym params) ""))
                                          :boulder-session/beginning (t/now)}}))
  (redirect-home))

(defn- running-attempt
  [{:keys [biff/db session]} session-id]
  (->> (queries/attempts-for-boulder-session db (:uid session) session-id)
       (filter #(nil? (:boulder-attempt/end %)))
       first))

(defn end-session!
  [ctx]
  (when-let [sess (owned-entity ctx :boulder-session)]
    ;; close any running attempt along with the session
    (when-let [running (running-attempt ctx (:xt/id sess))]
      (mutations/update-entity! ctx {:entity-key :boulder-attempt
                                     :entity-id (:xt/id running)
                                     :data {:boulder-attempt/end (t/now)}}))
    (when (nil? (:boulder-session/end sess))
      (mutations/update-entity! ctx {:entity-key :boulder-session
                                     :entity-id (:xt/id sess)
                                     :data {:boulder-session/end (t/now)}})))
  (redirect-home))

(defn start-attempt!
  "Open the attempt interval when climbing begins. Result fields land later
   via add-attempt!; sent starts false because the schema requires it."
  [{:keys [session] :as ctx}]
  (when-let [sess (owned-entity ctx :boulder-session)]
    (when-not (running-attempt ctx (:xt/id sess))
      (mutations/create-entity! ctx {:entity-key :boulder-attempt
                                     :data {:user/id (:uid session)
                                            :boulder-attempt/session-id (:xt/id sess)
                                            :boulder-attempt/beginning (t/now)
                                            :boulder-attempt/sent false}})))
  (redirect-home))

(defn add-attempt!
  "Record the result of an attempt. Closes the running attempt interval if
   one exists; otherwise the attempt is created already closed with
   beginning = end = now (logged after the fact, duration unknown).
   problem-id __new__ creates the problem inline from the difficulty/color/
   wall fields (gym comes from the session). Laps/tries only persist past
   their defaults — absent means the unremarkable case."
  [{:keys [session params] :as ctx}]
  (if-let [sess (owned-entity ctx :boulder-session)]
    (let [user-id    (:uid session)
          new?       (= "__new__" (:problem-id params))
          difficulty (str/trim (or (:new-difficulty params) ""))
          problem-id (if new?
                       (when-not (str/blank? difficulty)
                         (mutations/create-entity!
                          ctx
                          {:entity-key :boulder-problem
                           :data (cond-> {:user/id user-id
                                          :boulder-problem/gym (:boulder-session/gym sess)
                                          :boulder-problem/difficulty difficulty}
                                   (not (str/blank? (:new-hold-color params)))
                                   (assoc :boulder-problem/hold-color (str/trim (:new-hold-color params)))
                                   (not (str/blank? (:new-wall params)))
                                   (assoc :boulder-problem/wall (str/trim (:new-wall params))))}))
                       (some-> (:problem-id params) java.util.UUID/fromString))
          laps       (parse-int* (:laps params))
          tries      (parse-int* (:attempts params))
          now        (t/now)
          running    (running-attempt ctx (:xt/id sess))
          result     (cond-> {:boulder-attempt/sent (= "true" (:sent params))
                              :boulder-attempt/end now}
                       problem-id (assoc :boulder-attempt/problem-id problem-id)
                       (= "true" (:flash params)) (assoc :boulder-attempt/flash true)
                       (= "true" (:top params))   (assoc :boulder-attempt/top true)
                       (some-> laps (> 1))  (assoc :boulder-attempt/laps laps)
                       (some-> tries (> 1)) (assoc :boulder-attempt/attempts tries))]
      (when problem-id
        (if running
          (mutations/update-entity! ctx {:entity-key :boulder-attempt
                                         :entity-id (:xt/id running)
                                         :data result})
          (mutations/create-entity!
           ctx
           {:entity-key :boulder-attempt
            :data (merge {:user/id user-id
                          :boulder-attempt/session-id (:xt/id sess)
                          :boulder-attempt/beginning now}
                         result)})))
      (redirect-home))
    (redirect-home)))

(def routes
  ["/boulder" {}
   ["/session" {:get boulder-page}]
   ["/session/:id/summary" {:get session-summary-page}]
   ["/session/start" {:post start-session!}]
   ["/session/:id/end" {:post end-session!}]
   ["/session/:id/attempt" {:post add-attempt!}]
   ["/session/:id/attempt/start" {:post start-attempt!}]])
