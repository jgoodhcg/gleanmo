(ns tech.jgood.gleanmo.app.workout
  "Custom workout flow: one screen to run an exercise session — start/stop
   timed sets and log lines — instead of bouncing between generic CRUD forms.
   Naming follows gym vocabulary (see the exercise schema ns): a session
   contains timed *sets*; each set contains one *line* per exercise performed,
   so a superset is one set with several lines.

   The screen has three states, all served from the one URL /app/exercise/session
   and all derived from the data rather than the path (see 'One URL' below):

     idle          no open session — start one, or open a recent summary
     between-sets  session open, no set running — 'Start set' is the primary
                   action, and every set in the history can be filled in
     recording     a set is running — live timer card with the log form below

   Interaction design rationale: a set is an interval, and lines are logged
   *after* the work happens, so an honest interval needs one interaction
   before the work (Start set) and one after. Recording therefore offers two
   exits, and which one is cheaper depends on whether you already know what
   you did:

     'Log <exercise> × <reps>' logs and ends in one action. The ~80% case
     (one exercise per set, prefilled from memory) closes in a single tap and
     forgetting to stop is impossible on that path. 'Log + keep timing' is the
     superset variant — the exceptional case pays the extra decision.

     'End set' stops the clock having recorded nothing. This is the honest
     move when reaching for the picker would cost real seconds of timer
     accuracy: the set drops into the history asking to be described, and
     '+ Add exercise' writes a line into that exact set without touching its
     interval. 'Resume' reopens the newest set for when you stopped a beat
     early.

   One URL, no state in the path: which state you see follows from the open
   session and the open set, and both can change from another device or from
   'End session'. A /recording path would be a second source of truth that
   can disagree with the data, and a bookmark or back-button that lands on a
   state that no longer exists. Identity does belong in the path, and already
   is — /session/:id/summary names one session.

   Lines are always written against a set id, never against 'whatever is
   running'. That is what lets a set be timed now and described later, and it
   is why the fragment routes below (line/:id/edit, set/:id/line/new) exist:
   they fetch one exercise picker on demand instead of rendering hundreds of
   options per set into the history.

   If a line is logged with no set running we still accept it rather than lose
   data: a set is created on the spot and closed immediately, flagged
   auto-started, since its beginning is fabricated (the end — the log moment —
   is accurate). Ending a session force-closes any open set flagged
   auto-ended. See the exercise-set schema for how analysis treats the flags."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.exercise-schema :as exercise-schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(def ^:private screen-url "/app/exercise/session")

(defn- redirect-param [] (java.net.URLEncoder/encode screen-url "UTF-8"))

(defn- open-session
  [{:keys [biff/db session] :as ctx}]
  (first (queries/active-timers-for-user
          db (:uid session) :exercise-session
          :exercise-session/beginning :exercise-session/end
          :user-settings (queries/resolve-user-settings ctx))))

(defn- session-sets
  [{:keys [biff/db session]} session-id]
  (queries/sets-for-session db (:uid session) session-id))

(defn- lines-by-set
  [{:keys [biff/db session]} set-ids]
  (group-by :exercise-line/set-id
            (queries/lines-for-sets db (:uid session) set-ids)))

(defn- exercises-for-user
  [ctx]
  (->> (queries/all-for-user-query {:entity-type-str "exercise"
                                    :schema exercise-schema/exercise}
                                   ctx)
       (sort-by #(some-> (:exercise/label %) str/lower-case))))

(defn- exercise-memory
  "The user's most recently logged reps/weight/unit per exercise, so the entry
   form can prefill what they did last time instead of a fixed default. Returns
   {:by-exercise {exercise-id {:reps _ :weight _ :unit _}}
    :last exercise-id}           ; from the single most recent line overall
   Reads only the newest 200 lines (bounded scan-then-pull) — memory for an
   exercise not logged in that window just falls back to defaults."
  [{:keys [biff/db session]}]
  (let [lines (queries/recent-lines-for-user db (:uid session) 200) ; newest first
        by-ex (reduce (fn [acc line]
                        (let [ex-id (:exercise-line/exercise-id line)]
                          (if (contains? acc ex-id)
                            acc
                            (assoc acc ex-id
                                   {:reps   (:exercise-line/reps line)
                                    :weight (:exercise-line/weight line)
                                    :unit   (some-> (:exercise-line/weight-unit line) name)}))))
                      {}
                      lines)]
    {:by-exercise by-ex
     :last (:exercise-line/exercise-id (first lines))}))

(defn- parse-int* [s] (when-not (str/blank? s) (parse-long s)))
(defn- parse-num* [s] (when-not (str/blank? s) (parse-double s)))

(defn- line-summary
  "One-string form used for the running set's chips: 'pullup × 12 @ 45 lbs'."
  [line exercises-by-id]
  (let [ex-label (get-in exercises-by-id [(:exercise-line/exercise-id line) :exercise/label] "Unknown")
        reps     (:exercise-line/reps line)
        weight   (:exercise-line/weight line)
        unit     (some-> (:exercise-line/weight-unit line) name)
        distance (:exercise-line/distance line)
        d-unit   (some-> (:exercise-line/distance-unit line) name)]
    (str ex-label
         (when reps (str " × " reps))
         (when weight (str " @ " weight (when unit (str " " unit))))
         (when distance (str " — " distance (when d-unit (str " " d-unit)))))))

(defn- line-detail
  "Right-aligned detail for history rows: '12 × 45 lbs', or '12 reps' for
   bodyweight, falling back to distance for cardio-style lines."
  [line]
  (let [reps     (:exercise-line/reps line)
        weight   (:exercise-line/weight line)
        unit     (some-> (:exercise-line/weight-unit line) name)
        distance (:exercise-line/distance line)
        d-unit   (some-> (:exercise-line/distance-unit line) name)]
    (cond
      (and reps weight) (str reps " × " weight " " (or unit "lbs"))
      reps              (str reps " reps")
      distance          (str distance (when d-unit (str " " d-unit)))
      :else             "—")))

(defn- fmt-clock
  "m:ss, like a stopwatch — used for set durations."
  [beginning end]
  (let [secs (t/seconds (t/between beginning end))]
    (format "%d:%02d" (quot secs 60) (mod secs 60))))

(defn- fmt-session-len
  "'12 min' under an hour, '1h 5m' over — used for session durations."
  [beginning end]
  (let [m (quot (t/seconds (t/between beginning end)) 60)]
    (if (< m 60) (str m " min") (str (quot m 60) "h " (mod m 60) "m"))))

(defn- fmt-session-start
  "User-local session timestamp without seconds or fractional seconds."
  [ctx instant]
  (->> (t/in instant (shared/user-zone-id ctx))
       (t/format (t/formatter "MMM d, yyyy '·' h:mm a"))))

(defn- adjusted-beginning
  "Move a timer's beginning to change its displayed duration by delta seconds.
   A negative adjustment clamps at the latest valid beginning (now for a set,
   or the first set's beginning for a session)."
  [beginning latest-beginning delta-seconds]
  (let [candidate (.minusSeconds beginning (long delta-seconds))]
    (if (t/> candidate latest-beginning) latest-beginning candidate)))

;; Timers tick client-side: elements carrying data-epoch-ms get their text
;; recomputed every second from the wall clock, so the page needs no reloads
;; while a set runs. Default fmt is a stopwatch 'm:ss'; data-fmt "session"
;; renders '12 min' / '1h 5m'.
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

;; Defaults used only for an exercise the user has never logged; once logged,
;; exercise-memory prefills what they did last time.
(def ^:private default-reps 12)
(def ^:private default-weight 0)

;; All form behavior wires up here off data attributes, per `[data-line-form]`
;; rather than per id, because the same fields render in the session form and
;; in every fragment htmx swaps into a set card: −/+ steppers (weight steps 5
;; lbs / 2.5 kg by unit), the lbs/kg toggle, and the primary button label
;; ('Log pullup × 12', only where `[data-line-primary]` marks one).
;;
;; `data-memory` opts a form into recall-on-select: picking an exercise fills
;; in its last-logged reps/weight/unit, so repeat sets need zero re-entry.
;; The edit fragment deliberately omits it — correcting a mis-picked exercise
;; must not overwrite the reps and weight you are keeping.
(def ^:private form-script
  "(function(){
     function initLineForm(form){
       if(form.dataset.lineFormInit==='true') return;
       form.dataset.lineFormInit='true';
       var mem=form.dataset.memory?JSON.parse(form.dataset.memory):null;
       var dReps=Number(form.dataset.defaultReps||0), dWeight=Number(form.dataset.defaultWeight||0);
       var sel=form.querySelector('select[name$=exercise-id]');
       var reps=form.querySelector('[name=reps]');
       var weight=form.querySelector('[name=weight]');
       var unitInput=form.querySelector('[name=weight-unit]');
       var primary=form.querySelector('[data-line-primary]');
       function setUnit(u){ if(!unitInput) return; unitInput.value=u;
         form.querySelectorAll('[data-unit-btn]').forEach(function(b){
           var on=b.dataset.unitBtn===u;
           b.classList.toggle('bg-neon-cyan',on); b.classList.toggle('text-black',on);
           b.classList.toggle('text-gray-500',!on); }); }
       function exLabel(){ if(!sel) return 'exercise';
         var o=sel.options[sel.selectedIndex]; return o?o.text:'exercise'; }
       function syncPrimary(){ if(!primary) return;
         primary.textContent='Log '+exLabel()+' \\u00d7 '+(parseInt(reps.value,10)||0); }
       if(sel){ sel.addEventListener('change',function(){
         if(mem){ var m=mem[sel.value]||{};
           if(reps) reps.value=(m.reps!=null?m.reps:dReps);
           if(weight) weight.value=(m.weight!=null?m.weight:dWeight);
           setUnit(m.unit||'lbs'); }
         syncPrimary(); }); }
       form.querySelectorAll('[data-unit-btn]').forEach(function(b){
         b.addEventListener('click',function(){ setUnit(b.dataset.unitBtn); }); });
       form.querySelectorAll('[data-adjust]').forEach(function(b){
         b.addEventListener('click',function(){
           var p=b.dataset.adjust.split(':'); var name=p[0]; var dir=Number(p[1]);
           var i=form.querySelector('[name='+name+']');
           var step=(name==='weight')?((unitInput&&unitInput.value==='kg')?2.5:5):1;
           var min=(name==='reps')?1:0;
           i.value=Math.max(min,Math.round(((parseFloat(i.value)||0)+dir*step)*10)/10);
           syncPrimary(); }); });
       if(reps) reps.addEventListener('input',syncPrimary);
       syncPrimary();
     }
     function initLineForms(root){
       (root||document).querySelectorAll('[data-line-form]').forEach(initLineForm);
     }
     initLineForms(document);
     document.addEventListener('htmx:afterSettle',function(e){ initLineForms(e.target); });
     // One inline editor open at a time. The exercise picker is the shared
     // CRUD component, which ids its container by field name, so two open
     // fragments would collide on that id and inline-create would swap into
     // the wrong one.
     document.addEventListener('htmx:beforeRequest',function(e){
       var t=e.detail&&e.detail.elt;
       if(!t||!t.hasAttribute('data-line-open')) return;
       var keep=document.querySelector(t.getAttribute('hx-target'));
       document.querySelectorAll('[data-line-mount]').forEach(function(m){
         if(m!==keep) m.innerHTML=''; });
     });
     document.addEventListener('click',function(e){
       var b=e.target.closest('[data-dismiss-mount]'); if(!b) return;
       var m=b.closest('[data-line-mount]'); if(m) m.innerHTML='';
     });
     var toggle=document.getElementById('wk-backfill-toggle');
     var card=document.getElementById('wk-form-card');
     var cancel=document.getElementById('wk-backfill-cancel');
     if(toggle&&card){ toggle.addEventListener('click',function(){
       card.classList.remove('hidden'); toggle.classList.add('hidden'); }); }
     if(cancel&&card&&toggle){ cancel.addEventListener('click',function(){
       card.classList.add('hidden'); toggle.classList.remove('hidden'); }); }
   })();")

(defn- memory-json
  "Per-exercise last-logged values keyed by exercise id, embedded as JSON for
   the recall-on-select script. Values are numbers/short enums, but cheshire
   handles any shape safely."
  [by-exercise]
  (cheshire/generate-string
   (into {} (map (fn [[k v]] [(str k) v])) by-exercise)))

(defn- stepper-ctrl
  "−/+ flanking a borderless numeric input. Buttons use 44px-equivalent touch
   targets; their behavior (step size, clamping) wires up in form-script via
   data-adjust."
  [input-name value]
  [:div.flex.items-center.gap-1.5.shrink-0
   [:button {:type "button" :data-adjust (str input-name ":-1")
             :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "−"]
   [:input {:type "number" :step "any" :name input-name :value value
            :class "w-16 text-center text-xl font-bold text-white bg-transparent border-none p-0 tabular-nums"}]
   [:button {:type "button" :data-adjust (str input-name ":1")
             :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "+"]])

;; The session form and the fragment forms must not both call their exercise
;; select "exercise-id": the shared picker derives its container DOM id from
;; the field name, and the session form stays in the DOM (merely hidden)
;; while a fragment is open. Distinct names keep both ids unique.
(def ^:private session-field-name "exercise-id")
(def ^:private fragment-field-name "line-exercise-id")

;; The schema's weight-unit enum, as the form submits it. Kept as a lookup so
;; an unexpected param can't become a keyword that fails malli at write time.
(def ^:private weight-units {"lbs" :lbs "kg" :kg})

(defn- line-fields
  "Exercise picker plus the reps and weight rows — the body every form that
   writes a line shares."
  [{:keys [field-name exercise-id reps weight unit]} ctx]
  [:<>
   [:div {:class "text-[10px] font-semibold tracking-widest text-gray-500 mb-2"} "EXERCISE"]
   ;; Shared inline-create picker rather than a hand-rolled select, so
   ;; discovering a missing movement mid-workout follows the same flow as
   ;; everywhere else (roadmap/inline-entity-creation.md Phase 2).
   [:div.mb-5
    (inputs/inline-create-select
     {:field-name         field-name
      :related-entity-str "exercise"
      :required?          true
      :value              exercise-id}
     ctx)]
   [:div.flex.items-center.justify-between.gap-3.py-2
    [:span {:class "text-[10px] font-semibold tracking-widest text-gray-500"} "REPS"]
    (stepper-ctrl "reps" (str reps))]
   [:div.flex.items-center.justify-between.gap-2.py-2
    [:div.flex.flex-wrap.items-center.gap-1.5.min-w-0
     [:span {:class "text-[10px] font-semibold tracking-widest text-gray-500"} "WEIGHT"]
     [:div {:class "inline-flex rounded-lg border border-dark p-0.5"}
      (for [u ["lbs" "kg"]]
        [:button {:type "button" :data-unit-btn u
                  :class (str "px-2.5 py-1 text-[11px] font-bold rounded-md "
                              (if (= u unit) "bg-neon-cyan text-black" "text-gray-500"))}
         u])]
     [:input {:type "hidden" :name "weight-unit" :value unit}]]
    (stepper-ctrl "weight" (str weight))]])

(defn- log-form
  "The recording form: shared line fields and a primary button that names its
   payload ('Log pullup × 12'). Posts to the session; the line lands in the
   running set (closing it on the primary action) or backfills an
   auto-started set when none is running."
  [session-id exercises memory running? ctx]
  (let [{:keys [by-exercise last]} memory
        ex-by-id (into {} (map (juxt :xt/id identity)) exercises)
        sel-id   (or last (some-> exercises first :xt/id))
        sel-ex   (get ex-by-id sel-id)
        m        (get by-exercise sel-id)
        reps     (or (:reps m) default-reps)]
    (biff/form
     {:id "wk-line-form" :data-line-form true
      :action (str "/app/exercise/session/" session-id "/line"), :method "post"
      :data-memory (memory-json by-exercise)
      :data-default-reps default-reps
      :data-default-weight default-weight}
     (line-fields {:field-name  session-field-name
                   :exercise-id sel-id
                   :reps        reps
                   :weight      (or (:weight m) default-weight)
                   :unit        (or (:unit m) "lbs")}
                  ctx)
     [:div.flex.flex-col.gap-2.mt-4
      [:button {:id "wk-log-primary" :data-line-primary true
                :type "submit" :name "stop-set" :value "true"
                :class "w-full py-4 px-3 rounded-xl text-sm leading-5 font-bold bg-neon-cyan text-black whitespace-normal break-words overflow-hidden"}
       (str "Log " (:exercise/label sel-ex) " × " reps)]
      (when running?
        [:button {:type "submit"
                  :class "w-full py-3 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"}
         "Log + keep timing (superset)"])])))

(defn- line-entry-form
  "Fragment form for writing one line outside the recording flow: adding to a
   set that already ended, or correcting one that is already there. Never
   touches the set's interval, so describing a set later costs its timing
   nothing."
  [{:keys [action submit-label exercise-id reps weight unit memory delete-action]} ctx]
  [:div {:class "mt-2.5 rounded-lg border border-dark bg-dark p-3 min-w-0"}
   (biff/form
    (cond-> {:data-line-form true :action action :method "post"}
      memory (assoc :data-memory (memory-json memory)
                    :data-default-reps default-reps
                    :data-default-weight default-weight))
    (line-fields {:field-name  fragment-field-name
                  :exercise-id exercise-id
                  :reps        reps
                  :weight      weight
                  :unit        unit}
                 ctx)
    [:div.flex.items-center.gap-2.mt-3
     [:button {:type "submit"
               :class "flex-1 py-3 rounded-lg text-xs font-bold bg-neon-cyan text-black"}
      submit-label]
     [:button {:type "button" :data-dismiss-mount true
               :class "px-4 py-3 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"}
      "Cancel"]])
   (when delete-action
     (biff/form {:action delete-action :method "post" :class "mt-2.5"}
                [:button {:type "submit"
                          :class "text-[11px] text-red-400 bg-transparent border-none p-0"}
                 "Delete line"]))])

(defn- form-card
  "Card wrapping the log form. Visible while a set records; otherwise hidden
   until the backfill toggle reveals it (with a Cancel to collapse again)."
  [session-id exercises memory running? ctx]
  [:div {:id "wk-form-card"
         :class (str "rounded-xl border border-dark bg-dark-surface p-4 sm:p-6 "
                     (when-not running? "hidden"))}
   [:div.flex.items-center.justify-between.gap-3.mb-4
    [:h2.text-sm.font-bold.text-white (if running? "Log exercise" "Log a completed set")]
    (when-not running?
      [:button {:id "wk-backfill-cancel" :type "button"
                :class "text-xs text-gray-500 bg-transparent border-none"}
       "Cancel"])]
   (if (seq exercises)
     (log-form session-id exercises memory running? ctx)
     ;; Guarded because log-form's primary button names the selected exercise
     ;; ("Log pullup × 12") and would render empty with none to select.
     [:div.space-y-2
      [:p.text-sm.text-gray-400 "No exercises yet."]
      (inputs/inline-create-trigger "exercise")])])

(defn- duration-adjustment-controls
  "Compact forms that adjust a running timer by one minute in either
   direction. The server clamps negative elapsed time to zero."
  [action]
  [:div.flex.items-center.gap-2
   (for [[label seconds] [["−1m" -60] ["+1m" 60]]]
     ^{:key label}
     (biff/form
      {:action action :method "post" :class "inline"}
      [:button {:type "submit" :name "seconds" :value (str seconds)
                :class "px-3 py-2 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"
                :aria-label (str (if (neg? seconds) "Subtract" "Add")
                                 " one minute")}
       label]))])

(defn- running-set-panel
  "The recording hero card: pulsing dot, SET N · RECORDING, big live m:ss
   timer, restart/adjust/end controls, and chips for lines already logged in
   this set (the superset case)."
  [running set-n running-lines ex-by-id]
  (let [set-id (:xt/id running)]
    [:div {:class "rounded-xl border p-5"
           :style {:border-color "rgba(34,211,238,.3)"
                   :background "rgba(34,211,238,.05)"}}
     [:div.flex.items-center.justify-between.gap-3
      [:div.flex.items-center.gap-2.min-w-0
       [:span {:class "w-2 h-2 rounded-full bg-neon-cyan animate-pulse shrink-0"}]
       [:span {:class "text-[11px] font-semibold tracking-widest text-gray-400 truncate"}
        (str "SET " set-n " · RECORDING")]]
      (biff/form {:action (str "/app/exercise/set/" set-id "/stop"), :method "post"}
                 [:button {:type "submit"
                           :class "px-3.5 py-2 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent whitespace-nowrap"}
                  "End set"])]
     [:div {:class "text-[46px] font-bold text-neon-cyan tabular-nums leading-tight mt-2"
            :data-epoch-ms (epoch-ms (:exercise-set/beginning running))} "…"]
     [:div.flex.flex-wrap.items-center.gap-2.mt-3
      (duration-adjustment-controls (str "/app/exercise/set/" set-id "/adjust"))
      (biff/form
       {:action (str "/app/exercise/set/" set-id "/restart")
        :method "post" :class "inline"}
       [:button {:type "submit"
                 :class "px-3 py-2 rounded-lg text-xs font-semibold border border-dark text-gray-300 bg-transparent"}
        "Restart timer"])]
     (when (seq running-lines)
       [:div {:class "flex flex-wrap gap-1.5 mt-3 min-w-0"}
        (for [line running-lines]
          [:span {:class "max-w-full text-[11px] text-gray-300 bg-dark-surface border border-dark rounded-md px-2.5 py-1 tabular-nums break-words"}
           (line-summary line ex-by-id)])])]))

;; Line rows and the add button fetch their form on demand instead of having
;; one rendered inline. The picker is a select over every exercise the user
;; owns — hundreds, after the Airtable import — so pre-rendering one per line
;; would put tens of thousands of options on a session page.
(defn- line-row
  "One logged line: exercise name left, detail right, tapping either swaps in
   an edit form beneath it."
  [line ex-by-id]
  (let [id (:xt/id line)]
    [:div.min-w-0
     [:button {:type "button" :data-line-open true
               :hx-get    (str "/app/exercise/line/" id "/edit")
               :hx-target (str "#wk-line-mount-" id)
               :hx-swap   "innerHTML"
               ;; hx-select is inheritable; unset it so htmx swaps the whole
               ;; fragment rather than hunting for an enclosing form's id.
               :hx-select "unset"
               :class "w-full flex items-start justify-between gap-3 min-w-0 text-left p-0 bg-transparent border-none cursor-pointer hover:text-neon-cyan"}
      [:span {:class "text-sm text-gray-200 min-w-0 break-words"}
       (get-in ex-by-id [(:exercise-line/exercise-id line) :exercise/label] "Unknown")]
      [:span {:class "text-xs text-gray-400 tabular-nums whitespace-nowrap"}
       (line-detail line)]]
     [:div {:id (str "wk-line-mount-" id) :data-line-mount true}]]))

(defn- add-line-affordance
  "'+ Add exercise' for a set. Loud on a set with no lines — that set is a
   timed interval with nothing to show for it — quiet once one is logged,
   where it only serves the after-the-fact superset."
  [set-id bare?]
  [:div {:class (if bare? "" "mt-2")}
   [:button {:type "button" :data-line-open true
             :hx-get    (str "/app/exercise/set/" set-id "/line/new")
             :hx-target (str "#wk-set-mount-" set-id)
             :hx-swap   "innerHTML"
             :hx-select "unset"
             :class (if bare?
                      "w-full py-2.5 rounded-lg text-xs font-semibold text-neon-cyan border border-dashed border-dark bg-transparent cursor-pointer"
                      "text-[11px] text-gray-500 p-0 bg-transparent border-none cursor-pointer")}
    "+ Add exercise"]
   [:div {:id (str "wk-set-mount-" set-id) :data-line-mount true}]])

(defn- set-card
  "One closed set in the history: SET N, bold m:ss duration, optional
   'backfilled' tag, resume/edit controls, then its lines and the add
   affordance. Every line and every set stays editable from here, so a
   workout can be timed honestly first and described afterwards."
  [{:keys [xt/id] :as ex-set} set-n set-lines ex-by-id {:keys [resumable?]}]
  [:div {:data-set-card (str id)
         :data-set-n    (str set-n)
         :class "rounded-xl border border-dark bg-dark-surface px-4 py-3.5 min-w-0 overflow-hidden"}
   [:div {:class "flex items-center gap-2.5 mb-2.5"}
    [:span {:class "text-[10px] font-semibold tracking-widest text-gray-500"}
     (str "SET " set-n)]
    [:span.text-xs.font-bold.text-gray-300.tabular-nums
     (when-let [end (:exercise-set/end ex-set)]
       (fmt-clock (:exercise-set/beginning ex-set) end))]
    (when (:exercise-set/auto-started ex-set)
      [:span {:class "text-[9px] font-semibold tracking-wider text-gray-500 border border-dark rounded px-1.5 py-0.5"
              :title "Set was backfilled at log time — its start time is not accurate."}
       "backfilled"])
    [:span.flex-1]
    (when resumable?
      (biff/form {:action (str "/app/exercise/set/" id "/resume")
                  :method "post" :class "inline"}
                 [:button {:type "submit"
                           :class "text-[11px] text-neon-cyan bg-transparent border-none p-0 cursor-pointer"
                           :title "Reopen this set's timer — for when you stopped it a beat early."}
                  "resume"]))
    [:a.link {:class "text-[11px]"
              :href (str "/app/crud/form/exercise-set/edit/" id
                         "?redirect=" (redirect-param))}
     "edit"]]
   (when (seq set-lines)
     [:div {:class "flex flex-col gap-1.5"}
      (for [line set-lines]
        ^{:key (:xt/id line)}
        (line-row line ex-by-id))])
   (add-line-affordance id (empty? set-lines))])

(defn- error-banner
  "Only one thing can go wrong on this screen that the user must see: a line
   submitted with no exercise chosen. Nothing is written and — the point of
   the message — no set is closed, so the entry is still recoverable."
  [ctx]
  (when (= "pick-exercise" (get-in ctx [:params :error]))
    [:div {:class "rounded-lg border border-red-400/30 px-4 py-3 text-xs text-red-400"}
     "Pick an exercise before logging. Nothing was saved, and the set was left exactly as it was."]))

(defn- active-session-view
  [ctx session]
  (let [session-id (:xt/id session)
        sets       (session-sets ctx session-id)
        set-n-of   (into {} (map-indexed (fn [i s] [(:xt/id s) (inc i)]) sets))
        running    (first (filter #(nil? (:exercise-set/end %)) sets))
        done-sets  (filter :exercise-set/end sets)
        ;; Only the newest set can resume, and only with nothing else running
        ;; — reopening an older one would silently stretch its duration by
        ;; everything that has happened since.
        resumable  (when-not running (:xt/id (last sets)))
        lines      (lines-by-set ctx (set (map :xt/id sets)))
        exercises  (exercises-for-user ctx)
        ex-by-id   (into {} (map (juxt :xt/id identity)) exercises)
        memory     (exercise-memory ctx)
        n-sets     (count sets)]
    [:div.space-y-5
     (error-banner ctx)
     [:div.flex.items-start.justify-between.gap-3
      [:div.min-w-0
       [:h1.text-2xl.font-bold.text-white "Workout"]
       [:p.text-xs.text-gray-500.mt-1.tabular-nums
        "Session "
        [:span {:data-epoch-ms (epoch-ms (:exercise-session/beginning session))
                :data-fmt "session"} "…"]
        (str " · " n-sets (if (= 1 n-sets) " set" " sets"))]
       (when-let [location (not-empty (:exercise-session/location session))]
         [:p.text-xs.text-gray-400.mt-1.truncate {:title location} location])
       [:div.mt-2
        (duration-adjustment-controls
         (str "/app/exercise/session/" session-id "/adjust"))]]
      (biff/form {:action (str "/app/exercise/session/" session-id "/end"), :method "post"}
                 [:button {:type "submit"
                           :class "px-3.5 py-2 rounded-lg text-xs font-semibold text-red-400 bg-transparent border border-red-400/30 whitespace-nowrap"}
                  "End session"])]

     (if running
       (running-set-panel running (get set-n-of (:xt/id running))
                          (get lines (:xt/id running)) ex-by-id)
       [:div {:class "flex flex-col gap-2.5"}
        (biff/form {:action (str "/app/exercise/session/" session-id "/set/start"), :method "post"}
                   [:button {:type "submit"
                             :class "w-full py-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                    "Start set"])
        [:button {:id "wk-backfill-toggle" :type "button"
                  :class "w-full py-3 rounded-lg text-xs text-gray-500 border border-dashed border-dark bg-transparent"}
         "Forgot to start? Log a completed set"]])

     (form-card session-id exercises memory (some? running) ctx)

     [:div
      [:div.flex.items-baseline.gap-3.mb-3
       [:h2 {:class "text-[11px] font-bold tracking-widest text-gray-400"} "THIS SESSION"]
       [:span {:class "flex-1 h-px bg-dark-border"}]
       [:span.text-xs.text-gray-500.tabular-nums
        (str (count done-sets) (if (= 1 (count done-sets)) " set" " sets"))]]
      (if (seq done-sets)
        [:div {:class "flex flex-col gap-2.5"}
         (for [ex-set (reverse done-sets)]
           ^{:key (:xt/id ex-set)}
           (set-card ex-set (get set-n-of (:xt/id ex-set))
                     (get lines (:xt/id ex-set)) ex-by-id
                     {:resumable? (= resumable (:xt/id ex-set))}))]
        (when-not running
          [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-xs text-gray-500"}
           "Nothing logged yet — hit Start set when you begin."]))]

     [:div.pt-4
      [:a.link.text-xs {:href (str "/app/crud/form/exercise-session/edit/" session-id
                                   "?redirect=" (redirect-param))}
       "edit session"]]
     [:script (biff/unsafe (str tick-script "\n" form-script))]]))

(defn- recent-session-card
  "Recent-session link with a local timestamp and compact workout totals."
  [ctx session sets lines-by-set]
  (let [all-lines  (mapcat #(get lines-by-set (:xt/id %)) sets)
        total-reps (reduce + 0 (keep :exercise-line/reps all-lines))
        end        (:exercise-session/end session)
        duration   (when end
                     (fmt-session-len (:exercise-session/beginning session) end))
        location   (:exercise-session/location session)]
    [:a.block.no-underline
     {:href (str screen-url "/" (:xt/id session) "/summary")}
     [:div {:class "rounded-lg border border-dark bg-dark-surface hover:border-neon-cyan p-3 min-w-0"}
      [:div.flex.items-start.justify-between.gap-3.min-w-0
       [:span {:class "text-sm text-gray-200 font-semibold min-w-0 break-words"}
        (fmt-session-start ctx (:exercise-session/beginning session))]
       (when-not (str/blank? location)
         [:span.text-xs.text-gray-500.truncate.shrink.min-w-0
          {:title location}
          location])]
      (when-let [label (not-empty (:exercise-session/label session))]
        [:div.text-xs.text-gray-400.mt-1.break-words label])
      [:div.flex.flex-wrap.items-center.gap-x-2.gap-y-1.mt-1.text-xs.text-gray-500.tabular-nums
       [:span (str (count sets) (if (= 1 (count sets)) " set" " sets"))]
       [:span "·"]
       [:span (str total-reps " reps")]
       (when duration
         [:<>
          [:span "·"]
          [:span duration]])]]]))

(defn- idle-view
  [{:keys [biff/db session] :as ctx}]
  (let [user-id       (:uid session)
        recent        (queries/recent-sessions-for-user db user-id 5)
        session-ids   (map :xt/id recent)
        recent-sets   (queries/sets-for-sessions db user-id session-ids)
        sets-by-sess  (group-by :exercise-set/session-id recent-sets)
        recent-lines  (queries/lines-for-sets db user-id (set (map :xt/id recent-sets)))
        lines-by-set  (group-by :exercise-line/set-id recent-lines)
        locations     (queries/distinct-field-values
                       db user-id :exercise-session :exercise-session/location)]
    [:div.space-y-6
     [:h1.text-2xl.font-bold.text-white "Workout"]
     (biff/form {:action "/app/exercise/session/start", :method "post"}
                [:div {:class "text-[10px] font-semibold tracking-widest text-gray-500 mb-2"}
                 "LOCATION (OPTIONAL)"]
                [:input {:type "text" :name "location" :list "wk-locations"
                         :autocomplete "off" :data-original-value ""
                         :class "form-input w-full mb-3"
                         :placeholder "Gym, home, park…"}]
                (when (seq locations)
                  [:datalist {:id "wk-locations"}
                   (for [location locations]
                     [:option {:value location}])])
                [:button {:type "submit"
                          :class "w-full py-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                 "Start session"])
     (when (seq recent)
       [:div.space-y-2
        [:h2 {:class "text-[11px] font-bold tracking-widest text-gray-400"} "RECENT SESSIONS"]
        (for [s recent]
          ^{:key (:xt/id s)}
          (recent-session-card ctx s (get sets-by-sess (:xt/id s)) lines-by-set))])]))

(defn- stat-tile
  [label value]
  [:div {:class "rounded-lg border border-dark bg-dark-surface px-4 py-3"}
   [:div {:class "text-[10px] tracking-widest text-gray-500"} label]
   [:div.text-xl.font-bold.text-white.tabular-nums.mt-0.5 value]])

(defn- fmt-thousands [n] (format "%,d" (long n)))

(defn- session-summary-view
  "Read-only recap of one session: headline stats plus every set with its
   lines, so a finished workout is legible at a glance without paging through
   generic CRUD records."
  [ctx session]
  (let [session-id (:xt/id session)
        sets       (session-sets ctx session-id)
        lines-map  (lines-by-set ctx (set (map :xt/id sets)))
        exercises  (exercises-for-user ctx)
        ex-by-id   (into {} (map (juxt :xt/id identity)) exercises)
        all-lines  (mapcat val lines-map)
        total-reps (reduce + 0 (keep :exercise-line/reps all-lines))
        volume     (->> all-lines
                        (filter #(and (:exercise-line/reps %) (:exercise-line/weight %)))
                        (group-by #(some-> (:exercise-line/weight-unit %) name))
                        (map (fn [[unit ls]]
                               (str (fmt-thousands
                                     (reduce + 0 (map #(* (:exercise-line/reps %)
                                                          (:exercise-line/weight %)) ls)))
                                    " " (or unit "lbs"))))
                        (str/join " · "))
        ended      (:exercise-session/end session)
        duration   (when ended (fmt-session-len (:exercise-session/beginning session) ended))]
    [:div.space-y-6
     (error-banner ctx)
     [:div
      [:a.link.text-xs {:href screen-url} "← workout"]
      [:h1.text-2xl.font-bold.text-white.mt-2
       (or (:exercise-session/label session) "Workout session")]
      [:p.text-sm.text-gray-400.mt-1
       (str (fmt-session-start ctx (:exercise-session/beginning session))
            (if ended (str " · " duration) " · in progress"))]]
     (when-let [location (not-empty (:exercise-session/location session))]
       [:p.text-sm.text-gray-400 location])

     [:div {:class "grid grid-cols-2 sm:grid-cols-4 gap-3"}
      (stat-tile "SETS" (str (count sets)))
      (stat-tile "LINES" (str (count all-lines)))
      (stat-tile "TOTAL REPS" (str total-reps))
      (stat-tile "VOLUME" (if (str/blank? volume) "—" volume))]

     [:div
      [:div.flex.items-baseline.gap-3.mb-3
       [:h2 {:class "text-[11px] font-bold tracking-widest text-gray-400"} "SETS"]
       [:span {:class "flex-1 h-px bg-dark-border"}]]
      (if (seq sets)
        [:div {:class "flex flex-col gap-2.5"}
         (for [[i ex-set] (map-indexed vector sets)]
           ^{:key (:xt/id ex-set)}
           (set-card ex-set (inc i) (get lines-map (:xt/id ex-set)) ex-by-id {}))]
        [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-xs text-gray-500"}
         "No sets in this session."])]

     [:div.pt-2
      [:a.link.text-xs {:href (str "/app/crud/form/exercise-session/edit/" session-id
                                   "?redirect=" (redirect-param))}
       "edit session"]]
     ;; A finished session still gets the line editors — fixing a workout you
     ;; logged badly is exactly what this page is for.
     [:script (biff/unsafe form-script)]]))

(defn session-summary-page
  [{:keys [session] :as ctx}]
  (let [entity-id (java.util.UUID/fromString (:id (:path-params ctx)))
        sess      (queries/get-entity-for-user (:biff/db ctx) entity-id
                                               (:uid session) :exercise-session)]
    (ui/page
     ctx
     (layout/page-shell
      ctx {:width :narrow}
      (if sess
        (session-summary-view ctx sess)
        [:div
         [:p.text-gray-400 "Session not found."]])))))

(defn workout-page
  [ctx]
  (let [session (open-session ctx)]
    (ui/page
     ctx
     (layout/page-shell
      ctx {:width :narrow}
      (if session
        (active-session-view ctx session)
        (idle-view ctx))))))

(defn- redirect-home
  []
  {:status 303 :headers {"location" screen-url}})

(defn- redirect-back
  "303 to the exercise page the request came from, so a line edited on a
   session summary lands back on that summary rather than on the live screen.
   The referer is only trusted as far as 'a path under this feature'."
  [ctx & {:keys [error]}]
  (let [path (some-> (get-in ctx [:headers "referer"])
                     (as-> r (try (.getPath (java.net.URI. r))
                                  (catch Exception _ nil)))
                     (as-> p (when (and p (str/starts-with? p "/app/exercise/")) p)))]
    {:status  303
     :headers {"location" (cond-> (or path screen-url)
                            error (str "?error=" error))}}))

(defn- owned-entity
  [{:keys [biff/db session path-params]} entity-key]
  (when-let [entity-id (some-> (:id path-params) parse-uuid)]
    (queries/get-entity-for-user db entity-id (:uid session) entity-key)))

(defn- fragment
  "200 with a bare HTML fragment — no page shell — for the htmx swaps."
  [ctx body]
  {:status 200 :headers {"content-type" "text/html"} :body (ui/fragment ctx body)})

(defn start-session!
  [{:keys [session params] :as ctx}]
  (when-not (open-session ctx)
    (let [location (some-> (:location params) str/trim not-empty)]
      (mutations/create-entity!
       ctx
       {:entity-key :exercise-session
        :data (cond-> {:user/id (:uid session)
                       :exercise-session/beginning (t/now)}
                location (assoc :exercise-session/location location))})))
  (redirect-home))

(defn end-session!
  [ctx]
  (when-let [sess (owned-entity ctx :exercise-session)]
    ;; close any running set along with the session
    (doseq [ex-set (session-sets ctx (:xt/id sess))
            :when (nil? (:exercise-set/end ex-set))]
      (mutations/update-entity! ctx {:entity-key :exercise-set
                                     :entity-id (:xt/id ex-set)
                                     :data {:exercise-set/end (t/now)
                                            :exercise-set/auto-ended true}}))
    (when (nil? (:exercise-session/end sess))
      (mutations/update-entity! ctx {:entity-key :exercise-session
                                     :entity-id (:xt/id sess)
                                     :data {:exercise-session/end (t/now)}})))
  (redirect-home))

(defn start-set!
  [{:keys [session] :as ctx}]
  (when-let [sess (owned-entity ctx :exercise-session)]
    (when (empty? (->> (session-sets ctx (:xt/id sess))
                       (filter #(nil? (:exercise-set/end %)))))
      (mutations/create-entity! ctx {:entity-key :exercise-set
                                     :data {:user/id (:uid session)
                                            :exercise-set/session-id (:xt/id sess)
                                            :exercise-set/beginning (t/now)}})))
  (redirect-home))

(defn stop-set!
  "Stop the clock and record nothing. The set drops into the history asking to
   be described — which is the whole point: the interval stays honest because
   no picker stood between finishing the work and stopping the timer."
  [ctx]
  (when-let [ex-set (owned-entity ctx :exercise-set)]
    (when (nil? (:exercise-set/end ex-set))
      (mutations/update-entity! ctx {:entity-key :exercise-set
                                     :entity-id (:xt/id ex-set)
                                     :data {:exercise-set/end (t/now)}})))
  (redirect-home))

(defn resume-set!
  "Reopen a set you stopped a beat early, clearing its end so the timer picks
   up from the original beginning.

   Guarded three ways, because each failure would corrupt an interval rather
   than merely annoy: the session must still be open (a finished session has
   no running state to return to), nothing else may be running (two open sets
   would make 'the running set' ambiguous), and only the newest set qualifies
   (reopening an older one would absorb everything logged since into its
   duration). Also clears auto-ended — that flag says the end was fabricated
   by End session, which is no longer the story once you reopen it."
  [{:keys [biff/db session] :as ctx}]
  (when-let [ex-set (owned-entity ctx :exercise-set)]
    (let [sets (session-sets ctx (:exercise-set/session-id ex-set))
          sess (queries/get-entity-for-user db (:exercise-set/session-id ex-set)
                                            (:uid session) :exercise-session)]
      (when (and sess
                 (nil? (:exercise-session/end sess))
                 (every? :exercise-set/end sets)
                 (= (:xt/id ex-set) (:xt/id (last sets))))
        (mutations/update-entity!
         ctx
         {:entity-key :exercise-set
          :entity-id  (:xt/id ex-set)
          :data       {:exercise-set/end        :db/dissoc
                       :exercise-set/auto-ended :db/dissoc}}))))
  (redirect-home))

(def ^:private allowed-duration-adjustments #{-60 60})

(defn- requested-adjustment
  [params]
  (let [seconds (parse-int* (:seconds params))]
    (when (allowed-duration-adjustments seconds) seconds)))

(defn- adjust-session!
  [{:keys [params] :as ctx}]
  (when-let [sess (owned-entity ctx :exercise-session)]
    (when-let [seconds (and (nil? (:exercise-session/end sess))
                            (requested-adjustment params))]
      (let [now         (t/now)
            first-set   (first (session-sets ctx (:xt/id sess)))
            latest-start (or (:exercise-set/beginning first-set) now)]
        (mutations/update-entity!
         ctx
         {:entity-key :exercise-session
          :entity-id (:xt/id sess)
          :data {:exercise-session/beginning
                 (adjusted-beginning (:exercise-session/beginning sess)
                                     latest-start seconds)}}))))
  (redirect-home))

(defn- restart-set!
  [ctx]
  (when-let [ex-set (owned-entity ctx :exercise-set)]
    (when (nil? (:exercise-set/end ex-set))
      (mutations/update-entity!
       ctx
       {:entity-key :exercise-set
        :entity-id (:xt/id ex-set)
        :data {:exercise-set/beginning (t/now)}})))
  (redirect-home))

(defn- adjust-set!
  [{:keys [params] :as ctx}]
  (when-let [ex-set (owned-entity ctx :exercise-set)]
    (when-let [seconds (and (nil? (:exercise-set/end ex-set))
                            (requested-adjustment params))]
      (let [now (t/now)]
        (mutations/update-entity!
         ctx
         {:entity-key :exercise-set
          :entity-id (:xt/id ex-set)
          :data {:exercise-set/beginning
                 (adjusted-beginning (:exercise-set/beginning ex-set)
                                     now seconds)}}))))
  (redirect-home))

(defn- line-params
  "Exercise, reps and weight from any of the line forms. The session form and
   the fragment forms name their select differently (see the field-name defs)
   so their DOM ids can coexist; both land here.

   A blank or malformed exercise id yields nil rather than throwing — the
   handlers treat that as 'ask again', never as 'write a line with no
   exercise'. Zero weight means bodyweight, stored as no weight at all, and an
   unrecognized unit falls back to lbs rather than reaching the schema's enum
   as a keyword that fails validation at write time."
  [params]
  (let [raw    (or (not-empty (str/trim (str (get params (keyword fragment-field-name)))))
                   (not-empty (str/trim (str (get params (keyword session-field-name))))))
        w      (parse-num* (:weight params))
        weight (when (and w (pos? w)) w)]
    {:exercise-id (some-> raw parse-uuid)
     :reps        (parse-int* (:reps params))
     :weight      weight
     :unit        (when weight (get weight-units (:weight-unit params) :lbs))}))

(defn- line-doc
  "The document a line form describes, ready for create-entity!."
  [user-id set-id {:keys [exercise-id reps weight unit]}]
  (cond-> {:user/id                   user-id
           :exercise-line/set-id      set-id
           :exercise-line/exercise-id exercise-id}
    reps   (assoc :exercise-line/reps reps)
    weight (assoc :exercise-line/weight weight)
    unit   (assoc :exercise-line/weight-unit unit)))

(defn add-line!
  "Record a line against the session's running set. The primary submit also
   ends the set (the common one-exercise-per-set case); the secondary leaves
   it open for supersets. With no set running, one is backfilled flagged
   auto-started and closed immediately — its beginning is fabricated, its
   end (the log moment) is not.

   With no exercise chosen this writes nothing and — the part that used to be
   wrong — ends nothing. Closing the set on a submit we cannot record threw
   away the reps and weight along with any chance of noticing."
  [{:keys [session params] :as ctx}]
  (if-let [sess (owned-entity ctx :exercise-session)]
    (let [{:keys [exercise-id] :as line} (line-params params)]
      (if-not exercise-id
        (redirect-back ctx :error "pick-exercise")
        (let [running (->> (session-sets ctx (:xt/id sess))
                           (filter #(nil? (:exercise-set/end %)))
                           first)
              stop?   (or (nil? running) (= "true" (:stop-set params)))
              set-id  (or (:xt/id running)
                          (mutations/create-entity!
                           ctx
                           {:entity-key :exercise-set
                            :data {:user/id (:uid session)
                                   :exercise-set/session-id (:xt/id sess)
                                   :exercise-set/beginning (t/now)
                                   :exercise-set/auto-started true}}))]
          (mutations/create-entity!
           ctx {:entity-key :exercise-line
                :data       (line-doc (:uid session) set-id line)})
          (when stop?
            (mutations/update-entity! ctx {:entity-key :exercise-set
                                           :entity-id set-id
                                           :data {:exercise-set/end (t/now)}}))
          ;; the reloaded form prefills from exercise-memory (DB-derived), so
          ;; no need to round-trip the entered values through the query string
          (redirect-home))))
    (redirect-home)))

(defn new-line-fragment
  "The add-a-line form for one existing set, prefilled from exercise memory."
  [ctx]
  (if-let [ex-set (owned-entity ctx :exercise-set)]
    (let [{:keys [by-exercise last]} (exercise-memory ctx)
          sel-id (or last (some-> (exercises-for-user ctx) first :xt/id))
          m      (get by-exercise sel-id)]
      (fragment ctx
                (line-entry-form
                 {:action       (str "/app/exercise/set/" (:xt/id ex-set) "/line")
                  :submit-label "Add to set"
                  :exercise-id  sel-id
                  :reps         (or (:reps m) default-reps)
                  :weight       (or (:weight m) default-weight)
                  :unit         (or (:unit m) "lbs")
                  :memory       by-exercise}
                 ctx)))
    (fragment ctx [:p.text-xs.text-gray-500 "Set not found."])))

(defn add-line-to-set!
  "Write a line into a set that already exists. Deliberately leaves the set's
   beginning and end untouched: this is the path for describing a set you
   already timed, and re-dating it would defeat the point."
  [{:keys [session params] :as ctx}]
  (if-let [ex-set (owned-entity ctx :exercise-set)]
    (let [{:keys [exercise-id] :as line} (line-params params)]
      (if exercise-id
        (do (mutations/create-entity!
             ctx {:entity-key :exercise-line
                  :data       (line-doc (:uid session) (:xt/id ex-set) line)})
            (redirect-back ctx))
        (redirect-back ctx :error "pick-exercise")))
    (redirect-back ctx)))

(defn edit-line-fragment
  "The correct-this-line form. Carries no exercise memory, so swapping a
   mis-picked exercise keeps the reps and weight you already had right."
  [ctx]
  (if-let [line (owned-entity ctx :exercise-line)]
    (fragment ctx
              (line-entry-form
               {:action        (str "/app/exercise/line/" (:xt/id line))
                :submit-label  "Save"
                :exercise-id   (:exercise-line/exercise-id line)
                :reps          (or (:exercise-line/reps line) default-reps)
                :weight        (or (:exercise-line/weight line) default-weight)
                :unit          (or (some-> (:exercise-line/weight-unit line) name) "lbs")
                :delete-action (str "/app/exercise/line/" (:xt/id line) "/delete")}
               ctx))
    (fragment ctx [:p.text-xs.text-gray-500 "Line not found."])))

(defn update-line!
  "Rewrite a line's exercise, reps and weight. Cleared fields are dissoc'd
   rather than left behind, so dropping the weight off a line really does make
   it bodyweight instead of silently keeping the old number."
  [{:keys [params] :as ctx}]
  (if-let [line (owned-entity ctx :exercise-line)]
    (let [{:keys [exercise-id reps weight unit]} (line-params params)]
      (if exercise-id
        (do (mutations/update-entity!
             ctx
             {:entity-key :exercise-line
              :entity-id  (:xt/id line)
              :data       {:exercise-line/exercise-id  exercise-id
                           :exercise-line/reps         (or reps :db/dissoc)
                           :exercise-line/weight       (or weight :db/dissoc)
                           :exercise-line/weight-unit  (or unit :db/dissoc)}})
            (redirect-back ctx))
        (redirect-back ctx :error "pick-exercise")))
    (redirect-back ctx)))

(defn delete-line!
  [ctx]
  (when-let [line (owned-entity ctx :exercise-line)]
    (mutations/soft-delete-entity! ctx {:entity-key :exercise-line
                                        :entity-id  (:xt/id line)}))
  (redirect-back ctx))

(def routes
  ["/exercise" {}
   ["/session" {:get workout-page}]
   ["/session/:id/summary" {:get session-summary-page}]
   ["/session/start" {:post start-session!}]
   ["/session/:id/end" {:post end-session!}]
   ["/session/:id/adjust" {:post adjust-session!}]
   ["/session/:id/line" {:post add-line!}]
   ["/session/:id/set/start" {:post start-set!}]
   ["/set/:id/stop" {:post stop-set!}]
   ["/set/:id/resume" {:post resume-set!}]
   ["/set/:id/restart" {:post restart-set!}]
   ["/set/:id/adjust" {:post adjust-set!}]
   ["/set/:id/line/new" {:get new-line-fragment}]
   ["/set/:id/line" {:post add-line-to-set!}]
   ["/line/:id/edit" {:get edit-line-fragment}]
   ["/line/:id" {:post update-line!}]
   ["/line/:id/delete" {:post delete-line!}]])
