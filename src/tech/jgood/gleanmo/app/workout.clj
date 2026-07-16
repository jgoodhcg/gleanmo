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
    [:div.flex.items-center.justify-center.gap-2
     [:button {:type "button"
               :onclick (adjust (- step))
               :class "w-8 h-8 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300"} "−"]
     [:input {:type "number" :step "any" :name input-name :value value
              :class (str width " text-center text-lg font-bold text-white bg-transparent border-none p-0")}]
     [:button {:type "button"
               :onclick (adjust step)
               :class "w-8 h-8 flex items-center justify-center rounded-lg border border-dark bg-dark-surface text-gray-300"} "+"]]))

(defn- line-form
  "Sticky line entry form: last-entered values arrive as query params from
   add-line!'s redirect so supersets/repeat lines need minimal input. Posts
   to the session; the line lands in the running set (closing it on the
   primary action) or backfills an auto-started set when none is running."
  [session-id exercises params running?]
  (let [p (fn [k] (let [v (get params k)] (when-not (str/blank? (str v)) (str v))))
        unit (or (p :weight-unit) "lbs")]
    (biff/form
     {:action (str "/app/exercise/session/" session-id "/line"), :method "post"}
     [:div {:class "grid grid-cols-1 sm:grid-cols-3 gap-4 mb-5"}
      [:div
       [:div.text-xs.tracking-widest.text-gray-500.mb-2 "EXERCISE"]
       [:select.form-select.w-full {:name "exercise-id" :required true}
        (for [ex exercises]
          [:option {:value (:xt/id ex)
                    :selected (= (str (:xt/id ex)) (p :exercise-id))}
           (:exercise/label ex)])]]
      [:div
       [:div.text-xs.tracking-widest.text-gray-500.mb-2.text-center "REPS"]
       (stepper {:input-name "reps" :value (or (p :reps) "12")
                 :step 1 :min-val 1 :width "w-14"})]
      [:div
       [:div.flex.items-center.justify-center.gap-2.mb-2
        [:span.text-xs.tracking-widest.text-gray-500 "WEIGHT"]
        [:div.inline-flex.rounded.border.border-dark.p-px
         (for [u ["lbs" "kg"]]
           [:button {:type "button"
                     :onclick (str "this.closest('form').querySelector('[name=weight-unit]').value='" u "';"
                                   "this.closest('div').querySelectorAll('button').forEach(function(b){"
                                   "b.classList.remove('bg-neon-cyan','text-black');});"
                                   "this.classList.add('bg-neon-cyan','text-black');")
                     :class (str "px-2 py-px text-[10px] font-bold rounded "
                                 (when (= u unit) "bg-neon-cyan text-black"))}
            u])]
        [:input {:type "hidden" :name "weight-unit" :value unit}]]
       (stepper {:input-name "weight" :value (or (p :weight) "45")
                 :step 5 :min-val 0 :width "w-20"})]]
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
                  :class "w-full py-2 rounded-lg text-xs font-semibold border border-dark text-gray-400 bg-transparent"}
         "Log & continue set (superset)"])])))

(defn- running-set-panel
  [running running-lines ex-by-id]
  [:div {:class "rounded-xl border p-5 mb-4"
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
  [ctx session params]
  (let [session-id (:xt/id session)
        sets       (session-sets ctx session-id)
        running    (first (filter #(nil? (:exercise-set/end %)) sets))
        done-sets  (filter :exercise-set/end sets)
        lines      (lines-by-set ctx (set (map :xt/id sets)))
        exercises  (exercises-for-user ctx)
        ex-by-id   (into {} (map (juxt :xt/id identity)) exercises)
        n-lines    (reduce + (map count (vals lines)))]
    [:div {:class "max-w-2xl mx-auto p-6 pb-24 space-y-4"}
     [:div.flex.items-start.justify-between.gap-4.mb-6
      [:div.flex.items-center.gap-3
       [:span {:class "w-11 h-11 rounded-xl border flex items-center justify-center text-xl"
             :style {:border-color "rgba(34,211,238,.3)" :background "rgba(34,211,238,.08)"}} "🏋️"]
       [:div
        [:h1.text-2xl.font-bold.text-white "Workout"]
        [:p.text-sm.text-neon-cyan.mt-0.5
         "Session running "
         [:span {:data-epoch-ms (epoch-ms (:exercise-session/beginning session))
                 :data-fmt "short"} "…"]
         (str " · " n-lines (if (= 1 n-lines) " line logged" " lines logged"))]]]
      (biff/form {:action (str "/app/exercise/session/" session-id "/end"), :method "post"}
                 [:button {:type "submit"
                           :class "px-4 py-2 rounded-lg text-sm font-semibold text-red-400 bg-red-500/10 border border-red-400/30 whitespace-nowrap"}
                  "End session"])]

     (if running
       (running-set-panel running (get lines (:xt/id running)) ex-by-id)
       (biff/form {:action (str "/app/exercise/session/" session-id "/set/start"), :method "post"}
                  [:button {:type "submit"
                            :class "w-full py-4 mb-4 rounded-xl text-base font-bold bg-neon-cyan text-black"}
                   "Start set"]))

     [:div {:class "rounded-xl border border-dark bg-dark-surface p-6 mb-8"}
      [:h2.text-base.font-bold.text-white.mb-2 "Add line"]
      [:p {:class (str "text-xs mb-5 " (if running "text-neon-cyan" "text-gray-500"))}
       (if running
         "Logging ends this set by default — use \"continue set\" below to build a superset instead."
         "Forgot to start a set? Logging a line here backfills one and closes it immediately.")]
      (if (seq exercises)
        (line-form session-id exercises params (some? running))
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
     [:script (biff/unsafe tick-script)]]))

(defn- idle-view
  [ctx]
  (let [recent (->> (queries/all-for-user-query
                     {:entity-type-str "exercise-session"
                      :schema exercise-schema/exercise-session}
                     ctx)
                    (take 5))]
    [:div {:class "max-w-2xl mx-auto p-6 space-y-6"}
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
           {:href (str "/app/crud/form/exercise-session/edit/" (:xt/id s)
                       "?redirect=" (redirect-param))}
           [:div {:class "rounded-lg border border-dark bg-dark-surface hover:border-neon-cyan p-3 text-sm text-gray-300"}
            (or (:exercise-session/label s)
                (str (:exercise-session/beginning s)))]])])]))

(defn workout-page
  [{:keys [params] :as ctx}]
  (let [session (open-session ctx)]
    (ui/page
     ctx
     (side-bar
      ctx
      (if session
        (active-session-view ctx session params)
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
          weight      (parse-num* (:weight params))
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
      ;; sticky form: carry the entered values back as query params
      {:status 303
       :headers {"location"
                 (str screen-url
                      "?exercise-id=" exercise-id
                      (when reps (str "&reps=" reps))
                      (when weight (str "&weight=" weight))
                      (when unit (str "&weight-unit=" (name unit))))}})
    (redirect-home)))

(def routes
  ["/exercise" {}
   ["/session" {:get workout-page}]
   ["/session/start" {:post start-session!}]
   ["/session/:id/end" {:post end-session!}]
   ["/session/:id/line" {:post add-line!}]
   ["/session/:id/set/start" {:post start-set!}]
   ["/set/:id/stop" {:post stop-set!}]])
