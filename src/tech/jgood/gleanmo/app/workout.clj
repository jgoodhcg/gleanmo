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
   action is 'log & end set', which closes the ~80% case (one exercise per
   set) in a single action and makes forgetting to stop impossible on that
   path. A secondary 'log & continue set' action covers supersets — the
   exceptional case pays the extra decision, not the common one.

   If a line is logged with no set running we still accept it rather than
   lose data: a set is created on the spot and closed immediately, flagged
   auto-started, since its beginning is fabricated (the end — the log
   moment — is accurate). Ending a session force-closes any open set flagged
   auto-ended. See the exercise-set schema for how analysis treats the flags."
  (:require
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
   {:by-exercise {exercise-id {:reps _ :weight _ :unit _}} :last exercise-id},
   where :last is the exercise from the single most recent line overall."
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
     :last (:exercise-line/exercise-id (first lines))}))

(defn- parse-int* [s] (when-not (str/blank? s) (parse-long s)))
(defn- parse-num* [s] (when-not (str/blank? s) (parse-double s)))

(defn- line-summary
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

(defn- fmt-duration
  [beginning end]
  (let [secs (t/seconds (t/between beginning end))]
    (format "%dm %02ds" (quot secs 60) (mod secs 60))))

;; Timers tick client-side: elements carrying data-epoch-ms get their text
;; recomputed every second from the wall clock, so the page needs no reloads
;; while a set runs. data-fmt "long" renders "12m 05s", "short" renders "42m".
(def ^:private tick-script
  "document.querySelectorAll('[data-epoch-ms]').forEach(function (el) {
     function render() {
       var s = Math.max(0, Math.floor((Date.now() - Number(el.dataset.epochMs)) / 1000));
       el.textContent = el.dataset.fmt === 'short'
         ? Math.floor(s / 60) + 'm'
         : Math.floor(s / 60) + 'm ' + String(s % 60).padStart(2, '0') + 's';
     }
     render();
     setInterval(render, 1000);
   });")

(defn- epoch-ms [instant] (str (t/millis (t/between (t/epoch) instant))))

(defn- stepper
  "Numeric input flanked by −/+ buttons. The buttons adjust the named input
   client-side by step (clamped at min) so gloved/mid-workout hands never
   need the keyboard."
  [{:keys [input-name value step min-val width]}]
  (let [adjust (fn [delta]
                 (str "var i=this.parentElement.querySelector('input');"
                      "i.value=Math.max(" min-val
                      ",(parseFloat(i.value)||0)+(" delta "));"))]
    [:div.flex.items-center.justify-center.gap-1
     [:button {:type "button"
               :onclick (adjust (- step))
               :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "−"]
     [:input {:type "number" :step "any" :name input-name :value value
              :class (str width " text-center text-xl font-bold text-white bg-transparent border-none p-0")}]
     [:button {:type "button"
               :onclick (adjust step)
               :class "w-11 h-11 shrink-0 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300 text-lg"} "+"]]))

;; Defaults used only for an exercise the user has never logged; once logged,
;; exercise-memory prefills what they did last time.
(def ^:private default-reps 12)
(def ^:private default-weight 0)

(defn- js-obj
  "Render a flat clj map of {string -> {clj map of scalars}} as a JS object
   literal for embedding in a script. Keys are exercise UUIDs (safe), values
   are numbers or short enum strings — no user free-text, so no escaping."
  [m]
  (str "{"
       (str/join ","
                 (for [[k v] m]
                   (str "\"" k "\":{"
                        (str/join ","
                                  (for [[vk vv] v]
                                    (str "\"" (name vk) "\":"
                                         (cond (nil? vv) "null"
                                               (string? vv) (str "\"" vv "\"")
                                               :else vv))))
                        "}")))
       "}"))

;; Swapping the exercise recalls what was logged for it last time (reps, weight,
;; unit) and animates the fields so the change is legible; an unlogged exercise
;; falls back to defaults. Choices.js fires 'change' on the underlying select.
(def ^:private form-script
  "(function(){
     var form=document.getElementById('wk-line-form'); if(!form) return;
     var mem=JSON.parse(form.dataset.memory||'{}');
     var dReps=Number(form.dataset.defaultReps), dWeight=Number(form.dataset.defaultWeight);
     var sel=form.querySelector('[name=exercise-id]');
     var reps=form.querySelector('[name=reps]'), weight=form.querySelector('[name=weight]');
     var unitInput=form.querySelector('[name=weight-unit]');
     function setUnit(u){ unitInput.value=u;
       form.querySelectorAll('[data-unit-btn]').forEach(function(b){
         var on=b.dataset.unitBtn===u;
         b.classList.toggle('bg-neon-cyan',on); b.classList.toggle('text-black',on); }); }
     function flash(el){ el.animate([{background:'rgba(34,211,238,.25)'},{background:'transparent'}],{duration:500,easing:'ease-out'}); }
     sel.addEventListener('change',function(){
       var m=mem[sel.value]||{};
       reps.value=(m.reps!=null?m.reps:dReps);
       weight.value=(m.weight!=null?m.weight:dWeight);
       setUnit(m.unit||'lbs');
       flash(reps.parentElement); flash(weight.parentElement); });
   })();")

(defn- line-form
  "Line entry form. The exercise select is a searchable Choices dropdown that
   preselects the most recently logged exercise; reps/weight/unit prefill from
   what was logged for that exercise last time (exercise-memory), and switching
   the exercise recalls its own last values client-side. Posts to the session;
   the line lands in the running set (closing it on the primary action) or
   backfills an auto-started set when none is running."
  [session-id exercises memory running?]
  (let [{:keys [by-exercise last]} memory
        sel-id (or last (some-> exercises first :xt/id))
        m      (get by-exercise sel-id)
        unit   (or (:unit m) "lbs")]
    (biff/form
     {:id "wk-line-form"
      :action (str "/app/exercise/session/" session-id "/line"), :method "post"
      :data-memory (js-obj by-exercise)
      :data-default-reps default-reps
      :data-default-weight default-weight}
     [:div {:class "grid grid-cols-2 sm:grid-cols-3 gap-x-3 gap-y-4 mb-4"}
      [:div {:class "col-span-2 sm:col-span-1"}
       [:div.text-xs.tracking-widest.text-gray-500.mb-2 "EXERCISE"]
       [:select.form-select.w-full {:name "exercise-id" :required true
                                    :data-enhance "choices"
                                    :data-placeholder "Exercise"}
        (for [ex exercises]
          [:option {:value (:xt/id ex)
                    :selected (= (:xt/id ex) sel-id)}
           (:exercise/label ex)])]]
      [:div
       [:div.text-xs.tracking-widest.text-gray-500.mb-2.text-center "REPS"]
       (stepper {:input-name "reps" :value (str (or (:reps m) default-reps))
                 :step 1 :min-val 1 :width "min-w-0 flex-1"})]
      [:div
       [:div.flex.items-center.justify-center.gap-2.flex-wrap.mb-2
        [:span.text-xs.tracking-widest.text-gray-500 "WEIGHT"]
        [:div.inline-flex.rounded.border.border-dark.p-px
         (for [u ["lbs" "kg"]]
           [:button {:type "button"
                     :data-unit-btn u
                     :onclick (str "this.closest('form').querySelector('[name=weight-unit]').value='" u "';"
                                   "this.closest('div').querySelectorAll('button').forEach(function(b){"
                                   "b.classList.remove('bg-neon-cyan','text-black');});"
                                   "this.classList.add('bg-neon-cyan','text-black');")
                     :class (str "px-3 py-1 text-[11px] font-bold rounded "
                                 (when (= u unit) "bg-neon-cyan text-black"))}
            u])]
        [:input {:type "hidden" :name "weight-unit" :value unit}]]
       (stepper {:input-name "weight" :value (str (or (:weight m) default-weight))
                 :step 5 :min-val 0 :width "min-w-0 flex-1"})]]
     [:div.flex.flex-col.gap-2
      (if running?
        [:button {:type "submit" :name "stop-set" :value "true"
                  :class "w-full py-3 rounded-lg text-sm font-bold bg-neon-cyan text-black"}
         "Log & end set"]
        [:button {:type "submit" :name "stop-set" :value "true"
                  :class "w-full py-3 rounded-lg text-sm font-semibold border border-dark bg-dark-surface text-gray-400"}
         "Log line anyway"])
      (when running?
        [:button {:type "submit"
                  :class "w-full py-3 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"}
         "Log & continue set (superset)"])])))

(defn- running-set-panel
  [running running-lines ex-by-id]
  [:div {:class "rounded-xl border p-4 mb-4"
         :style {:border-color "rgba(34,211,238,.4)"
                 :background "rgba(34,211,238,.05)"}}
   [:div.flex.items-center.justify-between.gap-4
    [:div.flex.items-center.gap-3
     [:span {:class "w-2 h-2 rounded-full bg-neon-cyan animate-pulse"}]
     [:div
      [:div {:class "text-[10px] tracking-widest text-gray-500"} "SET IN PROGRESS"]
      [:div {:class "text-2xl font-bold text-neon-cyan tabular-nums leading-tight"
             :data-epoch-ms (epoch-ms (:exercise-set/beginning running))} "…"]]]
    (biff/form {:action (str "/app/exercise/set/" (:xt/id running) "/stop"), :method "post"}
               [:button {:type "submit"
                         :class "px-4 py-2 rounded-lg text-sm font-semibold border border-dark bg-dark-surface text-gray-300"}
                "■ End set"])]
   (when (seq running-lines)
     [:div {:class "flex flex-wrap gap-2 pt-4 mt-4 border-t"
            :style {:border-color "rgba(34,211,238,.2)"}}
      (for [line running-lines]
        [:span {:class "text-xs text-gray-300 bg-dark-surface border border-dark rounded-md px-2.5 py-1 tabular-nums"}
         (line-summary line ex-by-id)])])])

(defn- set-card
  [{:keys [xt/id] :as ex-set} set-lines ex-by-id]
  [:div {:class "rounded-xl border border-dark bg-dark-surface p-4"}
   [:div.flex.items-center.justify-between.gap-3.mb-2
    [:div.flex.items-center.gap-3
     [:span {:class "text-[10px] tracking-widest text-gray-500"} "SET"]
     [:span.text-sm.font-bold.text-gray-300.tabular-nums
      (when-let [end (:exercise-set/end ex-set)]
        (fmt-duration (:exercise-set/beginning ex-set) end))]
     (when (> (count set-lines) 1)
       [:span {:class "text-[9px] font-semibold tracking-widest text-neon-violet border rounded px-1.5 py-0.5"
               :style {:border-color "rgba(139,92,246,.4)"}}
        "SUPERSET"])
     (when (:exercise-set/auto-started ex-set)
       [:span {:class "text-[9px] font-semibold tracking-widest text-gray-500 border border-dark rounded px-1.5 py-0.5"
               :title "Set was backfilled at log time — its start time is not accurate."}
        "AUTO"])]
    [:a.link.text-xs {:href (str "/app/crud/form/exercise-set/edit/" id
                                 "?redirect=" (redirect-param))}
     "edit"]]
   [:div.flex.flex-col.gap-1
    (for [line set-lines]
      [:div.flex.items-baseline.gap-2.text-sm.text-gray-300
       [:span.text-gray-600 "•"]
       [:a.hover:text-neon-cyan.no-underline
        {:href (str "/app/crud/form/exercise-line/edit/" (:xt/id line)
                    "?redirect=" (redirect-param))}
        (line-summary line ex-by-id)]])]])

(defn- active-session-view
  [ctx session]
  (let [session-id (:xt/id session)
        sets       (session-sets ctx session-id)
        running    (first (filter #(nil? (:exercise-set/end %)) sets))
        done-sets  (filter :exercise-set/end sets)
        lines      (lines-by-set ctx (set (map :xt/id sets)))
        exercises  (exercises-for-user ctx)
        ex-by-id   (into {} (map (juxt :xt/id identity)) exercises)
        memory     (exercise-memory ctx)
        n-lines    (reduce + (map count (vals lines)))]
    [:div {:class "max-w-2xl mx-auto p-4 sm:p-6 pb-24 space-y-4"}
     [:div.flex.items-center.justify-between.gap-3.mb-4
      [:div.flex.items-center.gap-3.min-w-0
       [:span {:class "w-10 h-10 shrink-0 rounded-xl border flex items-center justify-center text-lg"
               :style {:border-color "rgba(34,211,238,.3)" :background "rgba(34,211,238,.08)"}} "🏋️"]
       [:div.min-w-0
        [:h1.text-xl.font-bold.text-white.leading-tight "Workout"]
        [:p.text-xs.text-neon-cyan.whitespace-nowrap
         [:span {:data-epoch-ms (epoch-ms (:exercise-session/beginning session))
                 :data-fmt "short"} "…"]
         (str " · " n-lines (if (= 1 n-lines) " line" " lines"))]]]
      (biff/form {:action (str "/app/exercise/session/" session-id "/end"), :method "post"}
                 [:button {:type "submit"
                           :class "px-3 py-2 rounded-lg text-xs font-semibold text-red-400 bg-red-500/10 border border-red-400/30 whitespace-nowrap"}
                  "End session"])]

     (if running
       (running-set-panel running (get lines (:xt/id running)) ex-by-id)
       (biff/form {:action (str "/app/exercise/session/" session-id "/set/start"), :method "post"}
                  [:button {:type "submit"
                            :class "w-full py-4 mb-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                   "Start set"]))

     [:div {:class "rounded-xl border border-dark bg-dark-surface p-4 sm:p-6 mb-8"}
      [:div.flex.items-baseline.justify-between.gap-3.mb-4
       [:h2.text-base.font-bold.text-white "Add line"]
       [:p {:class (str "text-[11px] text-right " (if running "text-neon-cyan" "text-gray-500"))}
        (if running
          "logging ends the set"
          "backfills a closed set")]]
      (if (seq exercises)
        (line-form session-id exercises memory (some? running))
        [:p.text-sm.text-gray-400
         "No exercises yet. "
         [:a.link {:href (str "/app/crud/form/exercise/new?redirect=" (redirect-param))}
          "Create one"]
         " first."])]

     [:div
      [:div.flex.items-baseline.gap-3.mb-4
       [:h2 {:class "text-sm font-bold tracking-widest text-gray-300"} "SETS"]
       [:span {:class "flex-1 h-px bg-dark-border"}]
       [:span.text-xs.text-gray-500
        (str (count sets) (if (= 1 (count sets)) " set" " sets"))]]
      (if (seq done-sets)
        [:div.flex.flex-col.gap-3
         (for [ex-set (reverse done-sets)]
           ^{:key (:xt/id ex-set)}
           (set-card ex-set (get lines (:xt/id ex-set)) ex-by-id))]
        (when-not running
          [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-sm text-gray-500"}
           "No sets logged yet this session."]))]

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
     [:div.flex.items-center.gap-3
      [:span {:class "w-11 h-11 rounded-xl border flex items-center justify-center text-xl"
              :style {:border-color "rgba(34,211,238,.3)" :background "rgba(34,211,238,.08)"}} "🏋️"]
      [:h1.text-2xl.font-bold.text-white "Workout"]]
     (biff/form {:action "/app/exercise/session/start", :method "post"}
                [:button {:type "submit"
                          :class "w-full py-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                 "Start session"])
     (when (seq recent)
       [:div.space-y-2
        [:h2 {:class "text-sm font-bold tracking-widest text-gray-300"} "RECENT SESSIONS"]
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
        duration   (when ended (fmt-duration (:exercise-session/beginning session) ended))]
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
      [:div.flex.items-baseline.gap-3.mb-4
       [:h2 {:class "text-sm font-bold tracking-widest text-gray-300"} "SETS"]
       [:span {:class "flex-1 h-px bg-dark-border"}]]
      (if (seq sets)
        [:div.flex.flex-col.gap-3
         (for [ex-set sets]
           ^{:key (:xt/id ex-set)}
           (set-card ex-set (get lines-map (:xt/id ex-set)) ex-by-id))]
        [:div {:class "rounded-xl border border-dashed border-dark p-7 text-center text-sm text-gray-500"}
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
