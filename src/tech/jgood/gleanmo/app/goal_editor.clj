(ns tech.jgood.gleanmo.app.goal-editor
  "The focused goal editor: measurement, optional relation scope, target,
   timing, dates, and time zone, with choices drawn from the measurement
   registry.

   Changing the measurement or timing re-renders only the dependent fields
   (htmx GET, no write). Saving goes through `db/mutations.clj`, whose write
   rules are the real validation; their field errors re-render the form in
   place, and success redirects to a fresh dashboard GET."
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.ui :as ui])
  (:import
   [java.time DayOfWeek LocalDate ZoneId]
   [java.time.temporal TemporalAdjusters]))

;; ---------------------------------------------------------------------------
;; Form state
;;
;; The editor works on a map of submitted strings so the same render serves a
;; fresh form, an existing goal, a dependent-field refresh, and a rejected
;; save (which must show exactly what was typed).
;; ---------------------------------------------------------------------------

(def ^:private default-measurement
  (registry/id->string :reading-log/duration-total))

(def ^:private optional-goal-keys
  "Goal attributes the editor may remove on save (switching measurement or
   timing can make a stored value inapplicable)."
  (into [:goal/target :goal/ends-on :goal/threshold-step
         :goal/progress-measure :goal/even-pace-enabled]
        registry/relation-keys))

(defn- trim-num
  "A canonical number shown in the editor's input unit, without a stray .0."
  [v factor]
  (when (number? v)
    (let [x (/ (double v) factor)]
      (if (== x (Math/rint x)) (str (long x)) (str x)))))

(defn- goal->form
  [goal]
  (let [m      (registry/measurement goal)
        factor (double (get-in m [:input-unit :factor] 1))]
    {:label            (:goal/label goal)
     :measurement      (some-> (:id m) registry/id->string)
     :target           (trim-num (:goal/target goal) factor)
     :threshold-step   (trim-num (:goal/threshold-step goal) factor)
     :timing           (some-> (:goal/timing goal) name)
     :starts-on        (some-> (:goal/starts-on goal) str)
     :ends-on          (some-> (:goal/ends-on goal) str)
     :time-zone        (:goal/time-zone goal)
     :relation-ids     (mapv str (get goal (get-in m [:relation :key])))
     :progress-measure (some-> (:goal/progress-measure goal) name)
     :even-pace        (true? (:goal/even-pace-enabled goal))}))

(defn- param-values
  "A possibly repeated param as a vector of non-blank strings."
  [v]
  (->> (cond (string? v) [v] (sequential? v) v :else [])
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- params->form
  [params]
  {:default-start    (:default-start params)
   :default-end      (:default-end params)
   :label            (:label params)
   :measurement      (:measurement params)
   :target           (:target params)
   :threshold-step   (:threshold-step params)
   :timing           (:timing params)
   :starts-on        (:starts-on params)
   :ends-on          (:ends-on params)
   :time-zone        (:time-zone params)
   :relation-ids     (param-values (:relation-ids params))
   :progress-measure (:progress-measure params)
   :even-pace        (shared/param-true? (:even-pace params))})

(defn- monday-of [^LocalDate d]
  (.with d (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))

(defn- new-form
  [ctx {:keys [measurement timing]}]
  (let [today  (shared/user-local-date ctx)
        timing (or timing "dated")]
    {:measurement (or measurement default-measurement)
     :timing      timing
     :default-start (str (if (= "weekly" timing) (monday-of today) today))
     :default-end (if (= "weekly" timing) (str (.plusDays (monday-of today) 6)) "")
     :ends-on (when (= "weekly" timing) (str (.plusDays (monday-of today) 6)))
     ;; Weekly goals default to whole calendar weeks.
     :starts-on   (str (if (= "weekly" timing) (monday-of today) today))
     :time-zone   (shared/get-user-time-zone ctx)}))

(defn- parse-number
  [s]
  (when-not (str/blank? s)
    (parse-double (str/trim s))))

(defn- parse-date
  [s]
  (when-not (str/blank? s)
    (try (LocalDate/parse (str/trim s)) (catch Exception _ ::invalid))))

(defn- form->goal
  "`[goal-attributes parse-errors]` for a submitted form. Only parsing
   problems are reported here; every rule lives in the write boundary."
  [{:keys [label measurement target threshold-step timing starts-on ends-on
           time-zone relation-ids progress-measure even-pace]}]
  (let [m          (registry/string->measurement measurement)
        factor     (double (get-in m [:input-unit :factor] 1))
        completion (= :completion (:aggregation m))
        ->canon    (fn [s]
                     (when-let [x (parse-number s)]
                       (let [v (* x factor)]
                         (if (and (:integer? m) (== v (Math/rint v))) (long v) v))))
        target-v   (when-not completion (->canon target))
        step-v     (when-not completion (->canon threshold-step))
        start      (parse-date starts-on)
        end        (parse-date ends-on)
        ids        (keep parse-uuid relation-ids)
        rel-key    (get-in m [:relation :key])
        errors     (cond-> {}
                     (nil? m)
                     (assoc :goal/measure ["Choose a measurement."])
                     (and (not completion) (not (str/blank? target)) (nil? target-v))
                     (assoc :goal/target ["Enter a number."])
                     (and (not completion) (not (str/blank? threshold-step))
                          (nil? step-v))
                     (assoc :goal/threshold-step ["Enter a number."])
                     (= ::invalid start) (assoc :goal/starts-on ["Enter a date."])
                     (= ::invalid end) (assoc :goal/ends-on ["Enter a date."]))]
    [(cond-> {:goal/label     (some-> label str/trim)
              :goal/timing    (some-> timing not-empty keyword)
              :goal/time-zone (some-> time-zone str/trim)}
       m                            (assoc :goal/source (:source m)
                                           :goal/measure (:measure m)
                                           :goal/aggregation (:aggregation m))
       target-v                     (assoc :goal/target target-v)
       step-v                       (assoc :goal/threshold-step step-v)
       (instance? LocalDate start)  (assoc :goal/starts-on start)
       (instance? LocalDate end)    (assoc :goal/ends-on end)
       (and rel-key (seq ids))      (assoc rel-key (set ids))
       (and completion (not (str/blank? progress-measure)))
       (assoc :goal/progress-measure (keyword progress-measure))
       (and completion even-pace)   (assoc :goal/even-pace-enabled true))
     errors]))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- field-errors
  [errors & ks]
  (when-let [msgs (seq (mapcat #(get errors %) ks))]
    (into [:div.mt-1.space-y-1]
          (for [msg msgs] [:p.text-sm.text-red-400 msg]))))

(defn- text-input
  [{:keys [id label value type help] :or {type "text"} :as opts}]
  [:div
   [:label.form-label {:for id} label]
   [:div.mt-2
    [:input.form-input
     (merge {:type type, :id id, :name id, :autocomplete "off"
             :data-original-value (str value)}
            (select-keys opts [:step :min :required :placeholder :inputmode])
            (when (some? value) {:value value}))]]
   (when help [:p.form-help help])])

(defn- measurement-select
  [form]
  [:div
   [:label.form-label {:for "measurement"} "What to measure"]
   [:div.mt-2
    (into
     [:select.form-select
      {:id                  "measurement"
       :name                "measurement"
       :data-original-value (str (:measurement form))
       :hx-get              "/app/goals/editor"
       :hx-trigger          "change"
       :hx-include          "#goal-editor-form"
       :hx-target           "#goal-editor-fields-mount"
       :hx-select           "#goal-editor-fields"
       :hx-swap             "innerHTML"}]
     (for [[source ms] (group-by :source registry/measurements)]
       (into [:optgroup {:label (get-in registry/sources [source :label])}]
             (for [{:keys [id label]} ms
                   :let [v (registry/id->string id)]]
               [:option {:value v, :selected (= v (:measurement form))}
                label]))))]])

(defn- relation-field
  [m form ctx]
  (when-let [{:keys [entity label]} (:relation m)]
    (let [completion (= :completion (:aggregation m))]
      [:div
       (inputs/render
        (if completion
          {:input-type         :single-relationship
           :input-name         "relation-ids"
           :input-label        "Book to finish"
           :input-required     true
           :related-entity-str (name entity)
           :value              (first (:relation-ids form))
           :opts               {}}
          {:input-type         :many-relationship
           :input-name         "relation-ids"
           :input-label        (str label " (optional)")
           :input-required     false
           :related-entity-str (name entity)
           :value              (seq (:relation-ids form))})
        ctx)
       (when-not completion
         [:p.form-help (str "Leave empty to count every " (str/lower-case label)
                            " record, including ones added later.")])])))

(defn- timing-select
  [m form]
  (let [allowed (sort (:timings m))
        current (keyword (or (:timing form) "dated"))]
    [:div
     [:label.form-label {:for "timing"} "Timing"]
     [:div.mt-2
      (into
       [:select.form-select
        {:id                  "timing"
         :name                "timing"
         :data-original-value (name current)
         :hx-get              "/app/goals/editor"
         :hx-trigger          "change"
         :hx-include          "#goal-editor-form"
         :hx-target           "#goal-editor-fields-mount"
         :hx-select           "#goal-editor-fields"
         :hx-swap             "innerHTML"}]
       (for [t allowed]
         [:option {:value (name t), :selected (= t current)}
          (get registry/timing-labels t)]))]
     [:p.form-help
      (case current
        :weekly     "Resets every Monday with the full target. A start or end mid-week makes that week partial; extra progress never carries forward."
        :open-ended "Counts from the start date with no deadline and never resets."
        "Counts from the start date through the end date, inclusive.")]]))

(defn- dependent-fields
  "Everything that depends on the chosen measurement and timing."
  [m form errors ctx]
  (let [completion (= :completion (:aggregation m))
        timing     (keyword (or (:timing form) "dated"))
        unit-label (get-in m [:input-unit :label])]
    [:div#goal-editor-fields.space-y-6
     (for [k [:default-start :default-end]]
       (when (contains? form k)
         [:input {:type "hidden" :name (name k) :value (get form k)}]))
     [:p.text-sm.text-gray-400 (:counts m)]
     (relation-field m form ctx)
     (field-errors errors :goal/project-ids :goal/book-ids :goal/meditation-ids
                   :goal/habit-ids :goal/exercise-ids)
     (when-not completion
       [:div.grid.grid-cols-1.sm:grid-cols-2.gap-4
        [:div
         (text-input {:id "target", :label (str "Target (" unit-label ")")
                      :type "number", :step (if (:integer? m) "1" "any"), :min "0"
                      :value (:target form), :required true,
                      :inputmode "decimal"})
         (field-errors errors :goal/target)]
        [:div
         (text-input {:id "threshold-step",
                      :label (str "Threshold step (" unit-label ", optional)")
                      :type "number", :step (if (:integer? m) "1" "any"), :min "0"
                      :value (:threshold-step form),
                      :help "Milestones every this much; defaults to about a tenth of the target."})
         (field-errors errors :goal/threshold-step)]])
     (timing-select m form)
     (field-errors errors :goal/timing)
     [:div.grid.grid-cols-1.sm:grid-cols-2.gap-4
      {:oninput "var k=event.target.name==='starts-on'?'default-start':event.target.name==='ends-on'?'default-end':null; var h=k&&this.closest('form').elements[k]; if(h) h.value='changed';"}
      [:div
       (text-input {:id "starts-on", :label "Start counting on", :type "date",
                    :value (:starts-on form), :required true,
                    :help "Nothing before this date counts."})
       (field-errors errors :goal/starts-on)]
      (when-not (= :open-ended timing)
        [:div
         (text-input {:id "ends-on",
                      :label (if (= :weekly timing) "Stop after (optional)" "End date")
                      :type "date", :value (:ends-on form),
                      :required (= :dated timing)})
         (field-errors errors :goal/ends-on)])]
     (when completion
       [:div.grid.grid-cols-1.sm:grid-cols-2.gap-4
        [:div
         [:label.form-label {:for "progress-measure"} "Chart progress by"]
         [:div.mt-2
          (into [:select.form-select
                 {:id "progress-measure", :name "progress-measure",
                  :data-original-value (str (:progress-measure form))}]
                (for [[v label] [["pages" "Pages"] ["chapters" "Chapters"]
                                 ["audio" "Audio position"]]]
                  [:option {:value v, :selected (= v (or (:progress-measure form)
                                                         "pages"))}
                   label]))]]
        (when (= :dated timing)
          [:div.flex.items-center.gap-2.sm:mt-8
           [:input {:type "checkbox", :id "even-pace", :name "even-pace",
                    :value "true", :checked (:even-pace form),
                    :data-original-value (str (boolean (:even-pace form)))}]
           [:label.form-label {:for "even-pace"} "Show an even-pace guide"]])])]))

(defn- time-zone-select
  [form errors]
  [:div
   [:label.form-label {:for "time-zone"} "Time zone"]
   [:div.mt-2
    (into [:select.form-select
           {:id "time-zone", :name "time-zone", :required true,
            :data-original-value (str (:time-zone form))}]
          (for [z (sort (ZoneId/getAvailableZoneIds))]
            [:option {:value z, :selected (= z (:time-zone form))} z]))]
   [:p.form-help "Days, weeks, and midnight splits use this zone."]
   (field-errors errors :goal/time-zone)])

(defn- editor-page
  [ctx {:keys [form errors goal-id]}]
  (let [m      (or (registry/string->measurement (:measurement form))
                   (registry/string->measurement default-measurement))
        action (if goal-id (str "/app/goal/" goal-id) "/app/goals")]
    (ui/page
     ctx
     (layout/page-shell
      ctx {:width :narrow}
      (layout/page-header
       {:title    (if goal-id "Edit goal" "New goal")
        :subtitle "Progress is always computed from your logs; nothing is copied onto the goal."})
      (when (seq errors)
        [:div.rounded-lg.border.border-red-500.bg-dark-surface.p-4.text-sm.text-red-300
         {:role "alert"}
         "The goal was not saved. Fix the highlighted fields below."])
      (biff/form
       {:action action, :method "post", :id "goal-editor-form",
        :class  "space-y-6"}
       (when goal-id [:input {:type "hidden", :name "goal-id", :value (str goal-id)}])
       [:div
        (text-input {:id "label", :label "Name", :value (:label form),
                     :required true, :placeholder "Read 100 hours"})
        (field-errors errors :goal/label)]
       (measurement-select form)
       (field-errors errors :goal/measure :goal/source :goal/aggregation)
       [:div#goal-editor-fields-mount (dependent-fields m form errors ctx)]
       (time-zone-select form errors)
       [:div.flex.justify-between.gap-3.pt-2
        [:a.form-button-secondary
         {:href (if goal-id (str "/app/goals?goal=" goal-id) "/app/goals")}
         "Cancel"]
        [:button.form-button-primary {:type "submit"}
         (if goal-id "Save goal" "Create goal")]])))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn- owned-goal
  [{:keys [biff/db session path-params]}]
  (when-let [id (some-> (:id path-params) parse-uuid)]
    (queries/get-entity-for-user db id (:uid session) :goal)))

(defn new-page
  "GET /app/goals/new"
  [{:keys [params] :as ctx}]
  (editor-page ctx {:form (new-form ctx params)}))

(defn edit-page
  "GET /app/goal/:id/edit"
  [ctx]
  (if-let [goal (owned-goal ctx)]
    (editor-page ctx {:form (goal->form goal), :goal-id (:xt/id goal)})
    {:status 303, :headers {"location" "/app/goals"}}))

(defn refresh
  "GET /app/goals/editor — re-render from the submitted values so the
   dependent fields match a new measurement or timing. Never writes."
  [{:keys [params] :as ctx}]
  (let [form (params->form params)
        weekly? (and (nil? (:goal-id params)) (= "weekly" (:timing form)))
        defaults (new-form ctx params)
        form (cond-> form
               (and weekly? (= (:starts-on form) (:default-start form)))
               (assoc :starts-on (:starts-on defaults) :default-start (:default-start defaults))
               (and weekly? (= (or (:ends-on form) "") (:default-end form)))
               (assoc :ends-on (:ends-on defaults) :default-end (:default-end defaults)))
        form (cond-> form
               (str/blank? (:starts-on form))
               (assoc :starts-on (:starts-on (new-form ctx params))))]
    (editor-page ctx {:form    form
                      :goal-id (some-> (:goal-id params) parse-uuid)})))

(defn- save!
  [form write!]
  (let [[attrs parse-errors] (form->goal form)]
    (if (seq parse-errors)
      [nil parse-errors]
      (try
        [(write! attrs) nil]
        (catch clojure.lang.ExceptionInfo e
          (if (mutations/invalid-write? e)
            [nil (:errors (ex-data e))]
            (throw e)))))))

(defn create!
  "POST /app/goals"
  [{:keys [session params] :as ctx}]
  (let [form        (params->form params)
        [id errors] (save! form
                           #(mutations/create-entity!
                             ctx {:entity-key :goal
                                  :data       (assoc % :user/id (:uid session))}))]
    (if errors
      (editor-page ctx {:form form, :errors errors})
      {:status 303, :headers {"location" (str "/app/goals?goal=" id)}})))

(defn update!
  "POST /app/goal/:id — inapplicable optional attributes are removed, so
   switching measurement cannot leave a stale filter or target behind."
  [{:keys [params] :as ctx}]
  (if-let [goal (owned-goal ctx)]
    (let [form        (params->form params)
          [_ errors]  (save! form
                             #(mutations/update-entity!
                               ctx {:entity-key :goal
                                    :entity-id  (:xt/id goal)
                                    :data       (merge (zipmap optional-goal-keys
                                                               (repeat :db/dissoc))
                                                       %)}))]
      (if errors
        (editor-page ctx {:form form, :errors errors, :goal-id (:xt/id goal)})
        {:status 303, :headers {"location" (str "/app/goals?goal=" (:xt/id goal))}}))
    {:status 303, :headers {"location" "/app/goals"}}))
