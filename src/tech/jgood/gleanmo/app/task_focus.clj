(ns tech.jgood.gleanmo.app.task-focus
  (:require
   [clojure.string :as str]
   [ring.middleware.anti-forgery :as csrf]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]))

(def ^:private task-states
  [:inbox :now :later :waiting :done])

(def ^:private active-task-states
  "Task states that are considered 'not done' (active tasks)."
  #{:inbox :now :later :waiting})

(def ^:private task-state-labels
  {:inbox   "Inbox",
   :now     "Now",
   :later   "Later",
   :waiting "Waiting",
   :done    "Done"})

(def ^:private task-domains
  [:work :personal :home :health :admin])

(defn- staleness-days
  [task now]
  (when-let [last-change (or (:task/last-state-change-at task)
                             (::sm/created-at task))]
    (.between java.time.temporal.ChronoUnit/DAYS last-change now)))

(defn- normalize-param
  [value]
  (let [value (some-> value
                      str/trim)]
    (when (seq value) value)))

(defn- parse-keyword-param
  [value]
  (some-> value
          normalize-param
          keyword))

(defn- parse-local-date-param
  [value]
  (some-> value
          normalize-param
          java.time.LocalDate/parse))

(defn- matches-search?
  [task term]
  (let [term  (str/lower-case term)
        label (some-> (:task/label task)
                      str/lower-case)
        notes (some-> (:task/notes task)
                      str/lower-case)]
    (or (and label (str/includes? label term))
        (and notes (str/includes? notes term)))))

(defn- due-on-or-before?
  [task due-date]
  (when-let [task-due (:task/due-on task)]
    (not (.isAfter task-due due-date))))

(defn- due-status-match?
  [task due-status today]
  (let [due-on    (:task/due-on task)
        next-week (.plusDays today 7)]
    (case due-status
      "none"    (nil? due-on)
      "set"     (some? due-on)
      "overdue" (and due-on (.isBefore due-on today))
      "today"   (and due-on (.isEqual due-on today))
      "next-7"  (and due-on
                     (not (.isBefore due-on today))
                     (not (.isAfter due-on next-week)))
      "future"  (and due-on (.isAfter due-on today))
      true)))

(defn- snoozed-filter-match?
  [task snoozed-filter today]
  (let [snooze-until    (:task/snooze-until task)
        future-snoozed? (and snooze-until (.isAfter snooze-until today))]
    (case snoozed-filter
      "future" future-snoozed?
      "exclude-future" (not future-snoozed?)
      true)))

(defn- focus-base-tasks
  [db user-id state-filter state-any? state-not-done?]
  (cond
    state-filter
    (queries/tasks-by-state db user-id state-filter)

    state-any?
    (mapcat #(queries/tasks-by-state db user-id %) task-states)

    state-not-done?
    (mapcat #(queries/tasks-by-state db user-id %) active-task-states)

    :else
    (queries/tasks-by-state db user-id :now)))

(defn- sort-focus-tasks
  [tasks sort-key now]
  (case sort-key
    "due-asc"
    (sort-by (fn [task]
               [(nil? (:task/due-on task)) (:task/due-on task)])
             tasks)
    "stale-desc"
    (sort-by (fn [task] (or (staleness-days task now) 0)) > tasks)
    (sort-by ::sm/created-at #(compare %2 %1) tasks)))

(defn- secondary-button
  "Secondary action - less prominent. Uses HTMX to preserve filters."
  [task-id target-state label]
  [:form.inline
   {:method     "post",
    :action     (str "/app/task/" task-id "/set-state"),
    :hx-post    (str "/app/task/" task-id "/set-state"),
    :hx-include "#filter-form",
    :hx-target  "#task-list",
    :hx-select  "#task-list",
    :hx-swap    "outerHTML"}
   [:input
    {:type  "hidden",
     :name  "__anti-forgery-token",
     :value csrf/*anti-forgery-token*}]
   [:input {:type "hidden", :name "target-state", :value (name target-state)}]
   [:button.inline-flex.items-center.justify-center.rounded-md.text-xs.font-medium.transition-all
    {:type "submit",
     :class
     "border border-dark-border text-gray-300 hover:text-neon-cyan hover:border-neon-cyan px-2 py-1"}
    label]])

(defn- snooze-button
  "Snooze button with days parameter. Uses HTMX to preserve filters."
  [task-id days label]
  [:form.inline
   {:method     "post",
    :action     (str "/app/task/" task-id "/snooze"),
    :hx-post    (str "/app/task/" task-id "/snooze"),
    :hx-include "#filter-form",
    :hx-target  "#task-list",
    :hx-select  "#task-list",
    :hx-swap    "outerHTML"}
   [:input
    {:type  "hidden",
     :name  "__anti-forgery-token",
     :value csrf/*anti-forgery-token*}]
   [:input {:type "hidden", :name "days", :value (str days)}]
   [:button.inline-flex.items-center.justify-center.rounded-full.text-xs.font-medium.transition-all
    {:type "submit",
     :class
     "border border-dark-border text-gray-400 hover:text-neon-pink hover:border-neon-pink px-2 py-1"}
    label]])

(defn- clear-snooze-button
  "Clear the snooze date. Uses HTMX to preserve filters."
  [task-id]
  [:form.inline
   {:method     "post",
    :action     (str "/app/task/" task-id "/clear-snooze"),
    :hx-post    (str "/app/task/" task-id "/clear-snooze"),
    :hx-include "#filter-form",
    :hx-target  "#task-list",
    :hx-select  "#task-list",
    :hx-swap    "outerHTML"}
   [:input
    {:type  "hidden",
     :name  "__anti-forgery-token",
     :value csrf/*anti-forgery-token*}]
   [:button.inline-flex.items-center.justify-center.rounded-full.text-xs.font-medium.transition-all
    {:type "submit",
     :class
     "border border-dark-border text-gray-400 hover:text-neon-cyan hover:border-neon-cyan px-2 py-1"}
    "Clear"]])

(defn- task-row
  [task project-by-id today now]
  (let [id            (:xt/id task)
        state         (:task/state task)
        project-id    (:task/project-id task)
        project-label (get project-by-id project-id)
        domain        (:task/domain task)
        due-on        (:task/due-on task)
        snooze-until    (:task/snooze-until task)
        snooze-count    (or (:task/snooze-count task) 0)
        future-snoozed? (and snooze-until (.isAfter snooze-until today))
        stale-days    (staleness-days task now)
        overdue?      (and due-on (.isBefore due-on today))]
    [:div.flex.flex-col.gap-3.p-4.bg-dark-surface.rounded-lg.border.border-dark
     [:div.flex.flex-col.gap-2
      [:div.flex.flex-wrap.items-center.gap-2
       [:a.font-semibold.text-white.hover:underline
        {:href (str "/app/crud/form/task/edit/" id)}
        (:task/label task)]
       [:span.text-xs.uppercase.tracking-wide.text-gray-400.border.border-dark-border.rounded-full.px-2.py-0.5
        (task-state-labels state)]]
      [:div.flex.flex-wrap.items-center.gap-3.text-xs.text-gray-500
       (when project-label
         [:a.uppercase.tracking-wide.hover:text-gray-300
          {:href (str "/app/crud/form/project/edit/" project-id)}
          project-label])
       (when domain
         [:span.uppercase.tracking-wide (name domain)])
       (when overdue?
         [:span.text-red-400.font-medium "overdue"])
       (when due-on
         [:span
          (if overdue?
            (str "was due " due-on)
            (str "due " due-on))])
       (when (and stale-days (> stale-days 7))
         [:span.text-yellow-500 (str stale-days "d in state")])
       (when (>= snooze-count 2)
         [:span.text-orange-400 (str "snoozed " snooze-count "x")])
       (when future-snoozed?
         [:span (str "snoozed until " snooze-until)])]
      (when-let [notes (:task/notes task)]
        [:p.text-sm.text-gray-400.truncate
         {:style {:max-width "420px"}}
         notes])]

     [:div.flex.flex-wrap.items-center.gap-2
      [:div.flex.items-center.gap-1
       (when (not= state :later)
         (secondary-button id :later "Later"))
       (when (not= state :waiting)
         (secondary-button id :waiting "Waiting"))
       (when (not= state :now)
         (secondary-button id :now "Now"))
       (when (not= state :done)
         (secondary-button id :done "Done"))]
      [:div.flex.items-center.gap-1
       [:span.text-xs.text-gray-500 "Snooze"]
       (snooze-button id 1 "+1d")
       (snooze-button id 3 "+3d")
       (snooze-button id 7 "+1w")
       (when snooze-until
         (clear-snooze-button id))]]]))

(defn- task-list
  "Render the task list segment."
  [tasks project-by-id task-count today now]
  [:div#task-list
   [:div.flex.items-center.justify-between.mb-3
    [:h2.text-lg.font-semibold.text-neon-cyan "Tasks"]
    [:span.text-sm.text-gray-500 (str task-count " tasks")]]
   (if (seq tasks)
     (for [task tasks]
       ^{:key (str (:xt/id task))}
       (task-row task project-by-id today now))
     [:p.text-gray-400 "No tasks match these filters."])])

(def ^:private filter-active-class
  "border-neon-cyan")

(defn- task-filter-form
  "Render the filter form block."
  [{:keys [projects
           search
           project-selected
           state-selected
           domain-selected
           due-selected
           due-status-selected
           snoozed-filter
           sort-selected]}]
  (let [search-active?     (seq search)
        project-active?    (seq project-selected)
        state-active?      (not= state-selected "any")
        domain-active?     (seq domain-selected)
        due-on-active?     (seq due-selected)
        due-status-active? (not= due-status-selected "any")
        snoozed-active?    (not= snoozed-filter "any")
        sort-active?       (not= sort-selected "created-desc")]
    [:div.mb-8.bg-dark-surface.border.border-dark.rounded.p-4
     [:form#filter-form.space-y-4
      {:action      "/app/task/focus",
       :method      "get",
       :hx-get      "/app/task/focus",
       :hx-target   "#task-list",
       :hx-select   "#task-list",
       :hx-swap     "outerHTML",
       :hx-push-url "true"}
      [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
       [:div
        [:label.form-label {:for "task-search"} "Search"]
        [:div.mt-2
         [:input.form-input
          {:type         "search",
           :id           "task-search",
           :name         "search",
           :value        search,
           :placeholder  "Search label or notes...",
           :autocomplete "off",
           :class        (when search-active? filter-active-class)}]]]

       [:div
        [:label.form-label {:for "task-project"} "Project"]
        [:div.mt-2
         [:select.form-select
          {:id    "task-project",
           :name  "project",
           :class (when project-active? filter-active-class)}
          [:option {:value "", :selected (str/blank? project-selected)}
           "All projects"]
          [:option {:value "none", :selected (= project-selected "none")}
           "No project"]
          [:option {:value "has-project", :selected (= project-selected "has-project")}
           "Has project"]
          (for [project projects
                :let    [project-id (str (:xt/id project))]]
            ^{:key project-id}
            [:option
             {:value    project-id,
              :selected (= project-selected project-id)}
             (:project/label project)])]]]

       [:div
        [:label.form-label {:for "task-state"} "State"]
        [:div.mt-2
         [:select.form-select
          {:id    "task-state",
           :name  "state",
           :class (when state-active? filter-active-class)}
          [:option {:value "any", :selected (= state-selected "any")}
           "Any state"]
          [:option {:value "not-done", :selected (= state-selected "not-done")}
           "Not done"]
          (for [state task-states
                :let  [state-value (name state)]]
            ^{:key state-value}
            [:option
             {:value    state-value,
              :selected (= state-selected state-value)}
             (task-state-labels state)])]]]

       [:div
        [:label.form-label {:for "task-domain"} "Domain"]
        [:div.mt-2
         [:select.form-select
          {:id    "task-domain",
           :name  "domain",
           :class (when domain-active? filter-active-class)}
          [:option {:value "", :selected (str/blank? domain-selected)}
           "All domains"]
          (for [domain task-domains
                :let   [domain-value (name domain)]]
            ^{:key domain-value}
            [:option
             {:value    domain-value,
              :selected (= domain-selected domain-value)}
             (str/capitalize domain-value)])]]]

       [:div
        [:label.form-label {:for "task-due-on"} "Due by date"]
        [:div.mt-2
         [:input.form-input
          {:type         "date",
           :id           "task-due-on",
           :name         "due-on",
           :value        due-selected,
           :autocomplete "off",
           :class        (when due-on-active? filter-active-class)}]]]

       [:div
        [:label.form-label {:for "task-due-status"} "Due status"]
        [:div.mt-2
         [:select.form-select
          {:id    "task-due-status",
           :name  "due-status",
           :class (when due-status-active? filter-active-class)}
          [:option {:value "any", :selected (= due-status-selected "any")}
           "Any"]
          [:option {:value "set", :selected (= due-status-selected "set")}
           "Has due date"]
          [:option {:value "none", :selected (= due-status-selected "none")}
           "No due date"]
          [:option {:value "overdue", :selected (= due-status-selected "overdue")}
           "Overdue"]
          [:option {:value "today", :selected (= due-status-selected "today")}
           "Due today"]
          [:option {:value "next-7", :selected (= due-status-selected "next-7")}
           "Due next 7 days"]
          [:option {:value "future", :selected (= due-status-selected "future")}
           "Due later"]]]]

       [:div
        [:label.form-label {:for "task-snoozed"} "Availability"]
        [:div.mt-2
         [:select.form-select
          {:id    "task-snoozed",
           :name  "snoozed",
           :class (when snoozed-active? filter-active-class)}
          [:option {:value "any", :selected (= snoozed-filter "any")} "All"]
          [:option
           {:value    "exclude-future",
            :selected (= snoozed-filter "exclude-future")}
           "Actionable (available now)"]
          [:option {:value "future", :selected (= snoozed-filter "future")}
           "Snoozed (future)"]]]]

       [:div
        [:label.form-label {:for "task-sort"} "Sort"]
        [:div.mt-2
         [:select.form-select
          {:id    "task-sort",
           :name  "sort",
           :class (when sort-active? filter-active-class)}
          [:option
           {:value    "created-desc",
            :selected (= sort-selected "created-desc")}
           "Created (newest)"]
          [:option
           {:value    "due-asc",
            :selected (= sort-selected "due-asc")}
           "Due date (soonest)"]
          [:option
           {:value    "stale-desc",
            :selected (= sort-selected "stale-desc")}
           "Staleness (oldest)"]]]]]

      [:div.flex.items-center.gap-3
       [:button.form-button-primary {:type "submit"} "Apply"]
       [:a.text-sm.text-gray-400.hover:text-white {:href "/app/task/focus"}
        "Clear"]
       [:button#copy-link-btn.text-sm.text-gray-400.hover:text-neon-cyan.flex.items-center.gap-1
        {:type "button",
         :onclick
         "navigator.clipboard.writeText(window.location.href).then(function(){var btn=document.getElementById('copy-link-btn');btn.textContent='Copied!';setTimeout(function(){btn.textContent='Copy link'},1500)})"}
        "Copy link"]]]]))

(defn focus-view
  "Actionable task list with filters."
  [{:keys [biff/db session params], :as ctx}]
  (let [user-id             (:uid session)
        search              (normalize-param (:search params))
        state-param         (normalize-param (:state params))
        domain-param        (normalize-param (:domain params))
        project-param       (normalize-param (:project params))
        due-param           (normalize-param (:due-on params))
        due-status          (or (normalize-param (:due-status params)) "any")
        snoozed-filter      (or (normalize-param (:snoozed params)) "any")
        sort-key            (or (normalize-param (:sort params)) "created-desc")
        state-any?          (= state-param "any")
        state-not-done?     (= state-param "not-done")
        state-filter        (cond
                              (= state-param "any") nil
                              (= state-param "not-done") nil
                              state-param (keyword state-param)
                              :else :now)
        domain-filter       (parse-keyword-param domain-param)
        due-filter          (parse-local-date-param due-param)
        now                 (java.time.Instant/now)
        today               (java.time.LocalDate/now)
        projects            (queries/projects-for-user db user-id)
        project-by-id       (into {}
                                  (map (juxt :xt/id :project/label) projects))
        base-tasks          (focus-base-tasks db
                                              user-id
                                              state-filter
                                              state-any?
                                              state-not-done?)
        filtered-tasks      (->>
                             base-tasks
                             (filter #(or (nil? state-filter)
                                          (= (:task/state %) state-filter)))
                             (filter #(or (nil? domain-filter)
                                          (= (:task/domain %) domain-filter)))
                             (filter #(cond
                                          (nil? project-param) true
                                          (= project-param "none") (nil? (:task/project-id %))
                                          (= project-param "has-project") (some? (:task/project-id %))
                                          :else (= project-param
                                                   (some-> (:task/project-id %)
                                                           str))))
                             (filter #(or (nil? search)
                                          (matches-search? % search)))
                             (filter #(or (nil? due-filter)
                                          (due-on-or-before? % due-filter)))
                             (filter #(due-status-match? % due-status today))
                             (filter #(snoozed-filter-match? %
                                                             snoozed-filter
                                                             today)))
        tasks               (sort-focus-tasks filtered-tasks sort-key now)
        task-count          (count tasks)
        state-selected      (or state-param
                                (when (nil? state-filter) "any")
                                (name state-filter))
        domain-selected     (or domain-param "")
        project-selected    (or project-param "")
        due-selected        due-param
        due-status-selected due-status
        sort-selected       sort-key]

    (ui/page
     ctx
     (side-bar
      ctx
      [:div.max-w-5xl.mx-auto.p-4
       [:div.mb-6
        [:h1.text-2xl.font-bold "Task Focus"]]

       (task-filter-form
        {:projects            projects,
         :search              search,
         :project-selected    project-selected,
         :state-selected      state-selected,
         :domain-selected     domain-selected,
         :due-selected        due-selected,
         :due-status-selected due-status-selected,
         :snoozed-filter      snoozed-filter,
         :sort-selected       sort-selected})

       (task-list tasks project-by-id task-count today now)

       [:div.mt-8.pt-4.border-t.border-dark
        [:a.text-sm.text-gray-400.hover:text-white {:href "/app/crud/task"}
         "View all tasks →"]]]))))
