(ns tech.jgood.gleanmo.app.workout
  "Custom workout flow: one screen to run an exercise session — start/stop
   timed sets and log lines — instead of bouncing between generic CRUD forms.
   Naming follows gym vocabulary (see the exercise schema ns): a session
   contains timed *sets*; each set contains one *line* per exercise performed,
   so a superset is one set with several lines.

   Interaction design rationale: a set is an interval, and lines are logged
   *after* the work happens, so an honest interval needs one interaction
   before the work (Start set) and one after. We spend the trailing
   interaction on the line log itself: while a set runs, the form's primary
   action is 'Log <exercise> × <reps>', which logs and ends the set — the
   ~80% case (one exercise per set) closes in a single action and forgetting
   to stop is impossible on that path. A secondary 'keep timing' action
   covers supersets — the exceptional case pays the extra decision.

   The UI is a state machine with one primary action per state: idle shows
   only 'Start set' (plus a quiet backfill toggle that reveals the form);
   recording shows the live timer card with the form below it. If a line is
   logged with no set running we still accept it rather than lose data: a
   set is created on the spot and closed immediately, flagged auto-started,
   since its beginning is fabricated (the end — the log moment — is
   accurate). Ending a session force-closes any open set flagged auto-ended.
   See the exercise-set schema for how analysis treats the flags."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
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
  [ctx session-id]
  (->> (queries/all-for-user-query {:entity-type-str "exercise-set"
                                    :schema exercise-schema/exercise-set}
                                   ctx)
       (filter #(= session-id (:exercise-set/session-id %)))
       (sort-by :exercise-set/beginning)
       vec))

(defn- lines-by-set
  [ctx set-ids]
  (->> (queries/all-for-user-query {:entity-type-str "exercise-line"
                                    :schema exercise-schema/exercise-line}
                                   ctx)
       (filter #(contains? set-ids (:exercise-line/set-id %)))
       (sort-by :tech.jgood.gleanmo.schema.meta/created-at)
       (group-by :exercise-line/set-id)))

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
    :last exercise-id            ; from the single most recent line overall
    :recent [exercise-id …]}     ; up to 4 distinct, most-recent first"
  [ctx]
  (let [lines (->> (queries/all-for-user-query
                    {:entity-type-str "exercise-line"
                     :schema exercise-schema/exercise-line}
                    ctx)
                   (sort-by :tech.jgood.gleanmo.schema.meta/created-at)
                   reverse) ; most-recent first
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
     :last (:exercise-line/exercise-id (first lines))
     :recent (->> lines (map :exercise-line/exercise-id) distinct (take 4) vec)}))

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

;; All form behavior wires up here off data attributes: the searchable
;; exercise picker (dropdown rows are built client-side from the JSON in
;; data-picker; selection fires on mousedown so it beats the input's blur),
;; RECENT chips, −/+ steppers (weight steps 5 lbs / 2.5 kg by unit), the
;; lbs/kg toggle, the primary button label ('Log pullup × 12'), and the
;; backfill show/hide toggle. Picking an exercise recalls its last-logged
;; reps/weight/unit so repeat sets need zero re-entry.
(def ^:private form-script
  "(function(){
     var form=document.getElementById('wk-line-form'); if(!form) return;
     var data=JSON.parse(form.dataset.picker);
     var byId={}; data.exercises.forEach(function(e){ byId[e.id]=e; });
     var exInput=form.querySelector('[name=exercise-id]');
     var search=document.getElementById('wk-ex-search');
     var dd=document.getElementById('wk-ex-dd');
     var reps=form.querySelector('[name=reps]');
     var weight=form.querySelector('[name=weight]');
     var unitInput=form.querySelector('[name=weight-unit]');
     var primary=document.getElementById('wk-log-primary');
     var open=false, query='';
     function selected(){ return byId[exInput.value]; }
     function setUnit(u){ unitInput.value=u;
       form.querySelectorAll('[data-unit-btn]').forEach(function(b){
         var on=b.dataset.unitBtn===u;
         b.classList.toggle('bg-neon-cyan',on); b.classList.toggle('text-black',on);
         b.classList.toggle('text-gray-500',!on); }); }
     function syncPrimary(){ var e=selected();
       primary.textContent='Log '+(e?e.label:'exercise')+' \\u00d7 '+(parseInt(reps.value,10)||0); }
     function syncChips(){ form.querySelectorAll('[data-chip-id]').forEach(function(c){
       var on=c.dataset.chipId===exInput.value;
       c.classList.toggle('border-neon-cyan',on); c.classList.toggle('text-neon-cyan',on);
       c.classList.toggle('font-bold',on); c.classList.toggle('text-gray-400',!on); }); }
     function select(id){ var e=byId[id]; if(!e) return;
       exInput.value=id;
       if(e.reps!=null) reps.value=e.reps;
       weight.value=(e.weight!=null?e.weight:data.defaultWeight);
       setUnit(e.unit||'lbs');
       search.value=e.label;
       syncPrimary(); syncChips(); }
     function hint(e){ if(e.reps==null) return '';
       return e.reps+' \\u00d7 '+(e.weight?e.weight+' '+(e.unit||'lbs'):'bw')+' last'; }
     function rows(){ var q=query.trim().toLowerCase();
       if(q) return data.exercises.filter(function(e){ return e.label.toLowerCase().indexOf(q)>=0; }).slice(0,8);
       var rec=data.recent.map(function(id){ return byId[id]; }).filter(Boolean);
       return rec.concat(data.exercises.filter(function(e){ return data.recent.indexOf(e.id)<0; })).slice(0,8); }
     function render(){ if(!open){ dd.classList.add('hidden'); return; }
       dd.classList.remove('hidden'); dd.innerHTML='';
       var rs=rows();
       if(!rs.length){ var d=document.createElement('div');
         d.className='px-3.5 py-3 text-xs text-gray-500';
         d.textContent='No match for \\u201c'+query+'\\u201d'; dd.appendChild(d); return; }
       rs.forEach(function(e){
         var b=document.createElement('button'); b.type='button';
         b.className='w-full flex items-baseline justify-between gap-3 text-left px-3.5 py-3 text-sm border-b border-dark bg-transparent '+(e.id===exInput.value?'text-neon-cyan':'text-gray-200');
         var n=document.createElement('span'); n.textContent=e.label;
         var h=document.createElement('span');
         h.className='text-[11px] text-gray-500 tabular-nums whitespace-nowrap';
         h.textContent=hint(e);
         b.appendChild(n); b.appendChild(h);
         b.addEventListener('mousedown',function(ev){ ev.preventDefault(); select(e.id); search.blur(); });
         dd.appendChild(b); }); }
     search.addEventListener('focus',function(){ open=true; query=''; search.value='';
       search.classList.add('border-neon-cyan'); render(); });
     search.addEventListener('blur',function(){ open=false; query='';
       var e=selected(); search.value=e?e.label:'';
       search.classList.remove('border-neon-cyan'); render(); });
     search.addEventListener('input',function(){ query=search.value; render(); });
     form.querySelectorAll('[data-chip-id]').forEach(function(c){
       c.addEventListener('click',function(){ select(c.dataset.chipId); }); });
     form.querySelectorAll('[data-unit-btn]').forEach(function(b){
       b.addEventListener('click',function(){ setUnit(b.dataset.unitBtn); }); });
     form.querySelectorAll('[data-adjust]').forEach(function(b){
       b.addEventListener('click',function(){
         var p=b.dataset.adjust.split(':'); var name=p[0]; var dir=Number(p[1]);
         var i=form.querySelector('[name='+name+']');
         var step=(name==='weight')?(unitInput.value==='kg'?2.5:5):1;
         var min=(name==='reps')?1:0;
         i.value=Math.max(min,Math.round(((parseFloat(i.value)||0)+dir*step)*10)/10);
         syncPrimary(); }); });
     reps.addEventListener('input',syncPrimary);
     var toggle=document.getElementById('wk-backfill-toggle');
     var card=document.getElementById('wk-form-card');
     var cancel=document.getElementById('wk-backfill-cancel');
     if(toggle&&card){ toggle.addEventListener('click',function(){
       card.classList.remove('hidden'); toggle.classList.add('hidden'); }); }
     if(cancel&&card&&toggle){ cancel.addEventListener('click',function(){
       card.classList.add('hidden'); toggle.classList.remove('hidden'); }); }
     syncPrimary(); syncChips();
   })();")

(defn- picker-json
  "Exercise catalog + per-exercise last-logged values + recent ids, embedded
   as JSON for the client-side picker. Labels are user free-text, hence
   cheshire rather than hand-rolled string building."
  [exercises by-exercise chip-ids]
  (cheshire/generate-string
   {:exercises (for [ex exercises]
                 (let [m (get by-exercise (:xt/id ex))]
                   {:id     (str (:xt/id ex))
                    :label  (:exercise/label ex)
                    :reps   (:reps m)
                    :weight (:weight m)
                    :unit   (:unit m)}))
    :recent (map str chip-ids)
    :defaultWeight default-weight}))

(defn- stepper-ctrl
  "−/+ flanking a borderless numeric input. Buttons are 44px squares (touch
   minimum); their behavior (step size, clamping) wires up in form-script via
   data-adjust."
  [input-name value]
  [:div.flex.items-center.gap-1.5.shrink-0
   [:button {:type "button" :data-adjust (str input-name ":-1")
             :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "−"]
   [:input {:type "number" :step "any" :name input-name :value value
            :class "w-12 text-center text-xl font-bold text-white bg-transparent border-none p-0 tabular-nums"}]
   [:button {:type "button" :data-adjust (str input-name ":1")
             :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "+"]])

(defn- log-form
  "The log form: searchable exercise picker with RECENT chips, stacked
   label-left/control-right rows for reps and weight, and a primary button
   that names its payload ('Log pullup × 12'). Posts to the session; the
   line lands in the running set (closing it on the primary action) or
   backfills an auto-started set when none is running."
  [session-id exercises memory running?]
  (let [{:keys [by-exercise last recent]} memory
        ex-by-id (into {} (map (juxt :xt/id identity)) exercises)
        sel-id   (or last (some-> exercises first :xt/id))
        sel-ex   (get ex-by-id sel-id)
        m        (get by-exercise sel-id)
        unit     (or (:unit m) "lbs")
        reps     (or (:reps m) default-reps)
        weight   (or (:weight m) default-weight)
        chip-ids (if (seq recent) recent (map :xt/id (take 4 exercises)))]
    (biff/form
     {:id "wk-line-form"
      :action (str "/app/exercise/session/" session-id "/line"), :method "post"
      :data-picker (picker-json exercises by-exercise chip-ids)}
     [:input {:type "hidden" :name "exercise-id" :value (str sel-id)}]
     [:div {:class "text-[10px] font-semibold tracking-widest text-gray-500 mb-2"} "EXERCISE"]
     [:div.relative.mb-2
      [:input {:id "wk-ex-search" :type "text" :autocomplete "off"
               :placeholder "Search exercises…"
               :value (:exercise/label sel-ex)
               :class "w-full h-12 px-3.5 text-sm rounded-lg border border-dark bg-black/30 text-white"}]
      [:div {:id "wk-ex-dd"
             :class "hidden absolute left-0 right-0 top-[52px] z-20 rounded-lg border border-dark bg-dark-surface shadow-xl overflow-y-auto"
             :style {:max-height "268px"}}]]
     [:div {:class "flex flex-wrap items-center gap-2 mb-5"}
      [:span {:class "text-[9px] font-semibold tracking-widest text-gray-600"} "RECENT"]
      (for [id chip-ids]
        [:button {:type "button" :data-chip-id (str id)
                  :class "px-3 py-2 text-xs rounded-lg border border-dark bg-dark-surface text-gray-400"}
         (get-in ex-by-id [id :exercise/label])])]
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
      (stepper-ctrl "weight" (str weight))]
     [:div.flex.flex-col.gap-2.mt-4
      [:button {:id "wk-log-primary" :type "submit" :name "stop-set" :value "true"
                :class "w-full py-4 rounded-xl text-sm font-bold bg-neon-cyan text-black"}
       (str "Log " (:exercise/label sel-ex) " × " reps)]
      (when running?
        [:button {:type "submit"
                  :class "w-full py-3 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"}
         "Log + keep timing (superset)"])])))

(defn- form-card
  "Card wrapping the log form. Visible while a set records; otherwise hidden
   until the backfill toggle reveals it (with a Cancel to collapse again)."
  [session-id exercises memory running?]
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
     (log-form session-id exercises memory running?)
     [:p.text-sm.text-gray-400
      "No exercises yet. "
      [:a.link {:href (str "/app/crud/form/exercise/new?redirect=" (redirect-param))}
       "Create one"]
      " first."])])

(defn- running-set-panel
  "The recording hero card: pulsing dot, SET N · RECORDING, big live m:ss
   timer, ghost End set, and chips for lines already logged in this set
   (the superset case)."
  [running set-n running-lines ex-by-id]
  [:div {:class "rounded-xl border p-5"
         :style {:border-color "rgba(34,211,238,.3)"
                 :background "rgba(34,211,238,.05)"}}
   [:div.flex.items-center.justify-between.gap-3
    [:div.flex.items-center.gap-2
     [:span {:class "w-2 h-2 rounded-full bg-neon-cyan animate-pulse"}]
     [:span {:class "text-[11px] font-semibold tracking-widest text-gray-400"}
      (str "SET " set-n " · RECORDING")]]
    (biff/form {:action (str "/app/exercise/set/" (:xt/id running) "/stop"), :method "post"}
               [:button {:type "submit"
                         :class "px-3.5 py-2 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"}
                "End set"])]
   [:div {:class "text-[46px] font-bold text-neon-cyan tabular-nums leading-tight mt-2"
          :data-epoch-ms (epoch-ms (:exercise-set/beginning running))} "…"]
   (when (seq running-lines)
     [:div {:class "flex flex-wrap gap-1.5 mt-3"}
      (for [line running-lines]
        [:span {:class "text-[11px] text-gray-300 bg-dark-surface border border-dark rounded-md px-2.5 py-1 tabular-nums"}
         (line-summary line ex-by-id)])])])

(defn- set-card
  "One closed set in the history: SET N, bold m:ss duration, optional
   'backfilled' tag, edit link; each line as exercise-name left and detail
   right, both linking to the line's edit form."
  [{:keys [xt/id] :as ex-set} set-n set-lines ex-by-id]
  [:div {:class "rounded-xl border border-dark bg-dark-surface px-4 py-3.5"}
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
    [:a.link {:class "text-[11px]"
              :href (str "/app/crud/form/exercise-set/edit/" id
                         "?redirect=" (redirect-param))}
     "edit"]]
   [:div {:class "flex flex-col gap-1.5"}
    (for [line set-lines]
      [:a {:class "flex items-baseline justify-between gap-3 no-underline hover:text-neon-cyan"
           :href (str "/app/crud/form/exercise-line/edit/" (:xt/id line)
                      "?redirect=" (redirect-param))}
       [:span.text-sm.text-gray-200
        (get-in ex-by-id [(:exercise-line/exercise-id line) :exercise/label] "Unknown")]
       [:span {:class "text-xs text-gray-400 tabular-nums whitespace-nowrap"}
        (line-detail line)]])]])

(defn- active-session-view
  [ctx session]
  (let [session-id (:xt/id session)
        sets       (session-sets ctx session-id)
        set-n-of   (into {} (map-indexed (fn [i s] [(:xt/id s) (inc i)]) sets))
        running    (first (filter #(nil? (:exercise-set/end %)) sets))
        done-sets  (filter :exercise-set/end sets)
        lines      (lines-by-set ctx (set (map :xt/id sets)))
        exercises  (exercises-for-user ctx)
        ex-by-id   (into {} (map (juxt :xt/id identity)) exercises)
        memory     (exercise-memory ctx)
        n-sets     (count sets)]
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 pb-24 space-y-5"}
     [:div.flex.items-start.justify-between.gap-3
      [:div
       [:h1.text-2xl.font-bold.text-white "Workout"]
       [:p.text-xs.text-gray-500.mt-1.tabular-nums
        "Session "
        [:span {:data-epoch-ms (epoch-ms (:exercise-session/beginning session))
                :data-fmt "session"} "…"]
        (str " · " n-sets (if (= 1 n-sets) " set" " sets"))]]
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

     (form-card session-id exercises memory (some? running))

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
                     (get lines (:xt/id ex-set)) ex-by-id))]
        (when-not running
          [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-xs text-gray-500"}
           "Nothing logged yet — hit Start set when you begin."]))]

     [:div.pt-4
      [:a.link.text-xs {:href (str "/app/crud/form/exercise-session/edit/" session-id
                                   "?redirect=" (redirect-param))}
       "edit session"]]
     [:script (biff/unsafe (str tick-script "\n" form-script))]]))

(defn- idle-view
  [ctx]
  (let [recent (->> (queries/all-for-user-query
                     {:entity-type-str "exercise-session"
                      :schema exercise-schema/exercise-session}
                     ctx)
                    (take 5))]
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 space-y-6"}
     [:h1.text-2xl.font-bold.text-white "Workout"]
     (biff/form {:action "/app/exercise/session/start", :method "post"}
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
            (or (:exercise-session/label s)
                (str (:exercise-session/beginning s)))]])])]))

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
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 pb-24 space-y-6"}
     [:div
      [:a.link.text-xs {:href screen-url} "← workout"]
      [:h1.text-2xl.font-bold.text-white.mt-2
       (or (:exercise-session/label session) "Workout session")]
      [:p.text-sm.text-gray-400.mt-1
       (str (:exercise-session/beginning session)
            (if ended (str " · " duration) " · in progress"))]]

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
           (set-card ex-set (inc i) (get lines-map (:xt/id ex-set)) ex-by-id))]
        [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-xs text-gray-500"}
         "No sets in this session."])]

     [:div.pt-2
      [:a.link.text-xs {:href (str "/app/crud/form/exercise-session/edit/" session-id
                                   "?redirect=" (redirect-param))}
       "edit session"]]]))

(defn session-summary-page
  [{:keys [session] :as ctx}]
  (let [entity-id (java.util.UUID/fromString (:id (:path-params ctx)))
        sess      (queries/get-entity-for-user (:biff/db ctx) entity-id
                                               (:uid session) :exercise-session)]
    (ui/page
     ctx
     (side-bar
      ctx
      (if sess
        (session-summary-view ctx sess)
        [:div {:class "max-w-2xl mx-auto p-6"}
         [:p.text-gray-400 "Session not found."]])))))

(defn workout-page
  [ctx]
  (let [session (open-session ctx)]
    (ui/page
     ctx
     (side-bar
      ctx
      (if session
        (active-session-view ctx session)
        (idle-view ctx))))))

(defn- redirect-home
  []
  {:status 303 :headers {"location" screen-url}})

(defn- owned-entity
  [{:keys [biff/db session path-params]} entity-key]
  (let [entity-id (java.util.UUID/fromString (:id path-params))]
    (queries/get-entity-for-user db entity-id (:uid session) entity-key)))

(defn start-session!
  [{:keys [session] :as ctx}]
  (when-not (open-session ctx)
    (mutations/create-entity! ctx {:entity-key :exercise-session
                                   :data {:user/id (:uid session)
                                          :exercise-session/beginning (t/now)}}))
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
  [ctx]
  (when-let [ex-set (owned-entity ctx :exercise-set)]
    (when (nil? (:exercise-set/end ex-set))
      (mutations/update-entity! ctx {:entity-key :exercise-set
                                     :entity-id (:xt/id ex-set)
                                     :data {:exercise-set/end (t/now)}})))
  (redirect-home))

(defn add-line!
  "Record a line against the session's running set. The primary submit also
   ends the set (the common one-exercise-per-set case); the secondary leaves
   it open for supersets. With no set running, one is backfilled flagged
   auto-started and closed immediately — its beginning is fabricated, its
   end (the log moment) is not."
  [{:keys [session params] :as ctx}]
  (if-let [sess (owned-entity ctx :exercise-session)]
    (let [running     (->> (session-sets ctx (:xt/id sess))
                           (filter #(nil? (:exercise-set/end %)))
                           first)
          stop?       (or (nil? running) (= "true" (:stop-set params)))
          set-id      (or (:xt/id running)
                          (mutations/create-entity!
                           ctx
                           {:entity-key :exercise-set
                            :data {:user/id (:uid session)
                                   :exercise-set/session-id (:xt/id sess)
                                   :exercise-set/beginning (t/now)
                                   :exercise-set/auto-started true}}))
          exercise-id (some-> (:exercise-id params) java.util.UUID/fromString)
          reps        (parse-int* (:reps params))
          ;; zero weight means bodyweight — store no weight at all
          weight      (let [w (parse-num* (:weight params))] (when (and w (pos? w)) w))
          unit        (when weight (keyword (or (:weight-unit params) "lbs")))]
      (when exercise-id
        (mutations/create-entity!
         ctx
         {:entity-key :exercise-line
          :data (cond-> {:user/id (:uid session)
                         :exercise-line/set-id set-id
                         :exercise-line/exercise-id exercise-id}
                  reps   (assoc :exercise-line/reps reps)
                  weight (assoc :exercise-line/weight weight)
                  unit   (assoc :exercise-line/weight-unit unit))}))
      (when stop?
        (mutations/update-entity! ctx {:entity-key :exercise-set
                                       :entity-id set-id
                                       :data {:exercise-set/end (t/now)}}))
      ;; the reloaded form prefills from exercise-memory (DB-derived), so no
      ;; need to round-trip the entered values through the query string
      (redirect-home))
    (redirect-home)))

(def routes
  ["/exercise" {}
   ["/session" {:get workout-page}]
   ["/session/:id/summary" {:get session-summary-page}]
   ["/session/start" {:post start-session!}]
   ["/session/:id/end" {:post end-session!}]
   ["/session/:id/line" {:post add-line!}]
   ["/session/:id/set/start" {:post start-set!}]
   ["/set/:id/stop" {:post stop-set!}]])
