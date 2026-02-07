(ns tech.jgood.gleanmo.app.task-today
  "Daily focus page for task execution."
  (:require
   [ring.middleware.anti-forgery :as csrf]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.ui.sortable :as sortable]))

(defn- week-boundaries
  "Get start and end instants for a week. Week starts on Monday."
  [today offset-weeks]
  (let [start-of-week (.with today (java.time.temporal.TemporalAdjusters/previousOrSame
                                    java.time.DayOfWeek/MONDAY))
        target-start (.plusWeeks start-of-week offset-weeks)
        target-end (.plusWeeks target-start 1)
        start-instant (.toInstant (.atStartOfDay target-start) java.time.ZoneOffset/UTC)
        end-instant (.toInstant (.atStartOfDay target-end) java.time.ZoneOffset/UTC)]
    [start-instant end-instant]))

(defn- progress-bar
  "Render a progress bar with percentage."
  [done total]
  (let [pct (if (zero? total) 0 (int (* 100 (/ done total))))
        _filled (if (zero? total) 0 (int (* 20 (/ done total))))]
    [:div.flex.items-center.gap-3
     [:span.text-lg.font-bold.text-white (str done "/" total)]
     [:div.flex-1.h-3.bg-dark.rounded-full.overflow-hidden
      [:div.h-full.bg-neon-cyan.transition-all.duration-300
       {:style {:width (str pct "%")}}]]
     [:span.text-sm.text-gray-400 (str pct "%")]]))

(defn- stats-block
  "Render the motivational stats block."
  [done-today total-today all-time this-week last-week]
  (let [week-diff (- this-week last-week)
        week-trend (cond
                     (pos? week-diff) (str "+" week-diff " vs last week")
                     (neg? week-diff) (str week-diff " vs last week")
                     :else "same as last week")]
    [:div.bg-dark-surface.border.border-dark.rounded-lg.p-4.mb-6
     [:div.mb-4
      [:div.text-sm.text-gray-400.mb-1 "Today's Progress"]
      (progress-bar done-today total-today)]
     [:div.grid.grid-cols-2.gap-4.text-sm
      [:div
       [:span.text-gray-400 "All time: "]
       [:span.text-white.font-semibold (str all-time " completed")]]
      [:div
       [:span.text-gray-400 "This week: "]
       [:span.text-white.font-semibold (str this-week " ")]
       [:span.text-gray-500 (str "(" week-trend ")")]]]]))

(defn- action-button
  "Small action button for task row."
  [task-id action label color-class]
  [:form.inline
   {:method  "post"
    :action  (str "/app/task/" task-id "/" action)
    :hx-post (str "/app/task/" task-id "/" action)
    :hx-target "#today-content"
    :hx-select "#today-content"
    :hx-swap "outerHTML"}
   [:input {:type "hidden" :name "__anti-forgery-token" :value csrf/*anti-forgery-token*}]
   [:input {:type "hidden" :name "origin" :value "today"}]
   [:button.px-2.py-1.text-xs.rounded.border.transition-all
    {:type "submit" :class color-class}
    label]])

(defn- complete-button
  "Checkbox-style complete button."
  [task-id]
  [:form.inline
   {:method  "post"
    :action  (str "/app/task/" task-id "/complete-today")
    :hx-post (str "/app/task/" task-id "/complete-today")
    :hx-target "#today-content"
    :hx-select "#today-content"
    :hx-swap "outerHTML"}
   [:input {:type "hidden" :name "__anti-forgery-token" :value csrf/*anti-forgery-token*}]
   [:button.w-6.h-6.rounded.border-2.border-gray-500.hover:border-neon-cyan.hover:bg-neon-cyan.hover:bg-opacity-20.transition-all.flex.items-center.justify-center
    {:type "submit" :title "Complete"}
    [:span.text-transparent.hover:text-neon-cyan "✓"]]])

(defn- task-row-content
  "Render the content of a single task row (without sortable wrapper)."
  [task order project-by-id today]
  (let [id (:xt/id task)
        focus-date (:task/focus-date task)
        carried-over? (and focus-date (.isBefore focus-date today))
        project-id (:task/project-id task)
        project-label (get project-by-id project-id)]
    [:div.flex.items-center.gap-3.p-3.bg-dark-surface.rounded-lg.border.border-dark.group.cursor-move
     ;; Complete button
     (complete-button id)
     ;; Order number
     [:span.text-gray-500.text-sm.w-6 (str order ".")]
     ;; Task content
     [:div.flex-1.min-w-0
      [:div.flex.items-center.gap-2
       [:a.font-medium.text-white.hover:underline.truncate
        {:href (str "/app/crud/form/task/edit/" id)}
        (:task/label task)]
       (when carried-over?
         [:span.text-xs.text-yellow-500.border.border-yellow-500.rounded-full.px-2.py-0.5
          "carried over"])
       (when project-label
         [:span.text-xs.text-gray-500 project-label])]]
     ;; Actions (visible on hover)
     [:div.flex.items-center.gap-1.opacity-0.group-hover:opacity-100.transition-opacity
      (action-button id "defer-today" "tomorrow" "border-gray-600 text-gray-400 hover:border-neon-pink hover:text-neon-pink")
      (action-button id "remove-from-today" "remove" "border-gray-600 text-gray-400 hover:border-red-500 hover:text-red-500")]]))

(defn- empty-state
  "Render empty state when no tasks for today."
  []
  [:div.text-center.py-12
   [:div.text-4xl.mb-4 "✨"]
   [:h3.text-lg.font-medium.text-white.mb-2 "All done for today!"]
   [:p.text-gray-400 "Add tasks from your backlog to plan your day."]])

(defn- task-list
  "Render the ordered task list with drag-and-drop reordering."
  [tasks project-by-id today]
  (if (seq tasks)
    (sortable/sortable-list
     {:endpoint "/app/task/reorder-today"
      :class "space-y-2"}
     (map-indexed
      (fn [idx task]
        ^{:key (str (:xt/id task))}
        (sortable/sortable-item
         (:xt/id task)
         (task-row-content task (inc idx) project-by-id today)))
      tasks))
    [:div.space-y-2
     (empty-state)]))

(defn- quick-add-form
  []
  [:form#today-quick-add.flex.items-center.gap-2.mb-6
   {:method     "post"
    :action     "/app/task/quick-add-today"
    :hx-post    "/app/task/quick-add-today"
    :hx-target  "#today-content"
    :hx-select  "#today-content"
    :hx-swap    "outerHTML"}
   [:input {:type "hidden" :name "__anti-forgery-token" :value csrf/*anti-forgery-token*}]
   [:label.sr-only {:for "today-quick-add-label"} "Quick add task"]
   [:input.form-input.flex-1
    {:id "today-quick-add-label"
     :name "label"
     :placeholder "Quick add a task..."
     :autocomplete "off"
     :data-original-value ""}]
   [:button.inline-flex.items-center.justify-center.rounded-md.bg-neon-cyan.text-dark.font-semibold.px-4.py-2.transition-all.hover:bg-transparent.hover:text-neon-cyan.border.border-neon-cyan
    {:type "submit"}
    "Add"]])

(defn- backlog-picker-button
  []
  [:a.inline-flex.items-center.gap-2.px-4.py-2.bg-dark-surface.border.border-dark.rounded-lg.text-gray-300.hover:text-neon-cyan.hover:border-neon-cyan.transition-all
   {:href "/app/task/focus?today-filter=exclude"}
   [:span "+"]
   [:span "Add from backlog"]])

(defn today-content
  "The main content block (for HTMX partial updates)."
  [{:keys [biff/db session]}]
  (let [user-id (:uid session)
        today (java.time.LocalDate/now)
        ;; Get tasks for today
        focused-tasks (queries/tasks-for-today db user-id today)
        completed-today (queries/tasks-completed-today db user-id today)
        ;; Stats
        done-today (count completed-today)
        total-today (+ (count focused-tasks) done-today)
        all-time (queries/count-tasks-completed-all-time db user-id)
        [this-week-start this-week-end] (week-boundaries today 0)
        [last-week-start last-week-end] (week-boundaries today -1)
        this-week (queries/count-tasks-completed-in-range db user-id this-week-start this-week-end)
        last-week (queries/count-tasks-completed-in-range db user-id last-week-start last-week-end)
        ;; Projects for display
        projects (queries/projects-for-user db user-id)
        project-by-id (into {} (map (juxt :xt/id :project/label) projects))]
    [:div#today-content
     ;; Header
     [:div.flex.items-center.justify-between.mb-6
      [:h1.text-2xl.font-bold "Today"]
      [:span.text-gray-400
       (.format today (java.time.format.DateTimeFormatter/ofPattern "EEE, MMM d"))]]
     ;; Stats
     (stats-block done-today total-today all-time this-week last-week)
     ;; Quick add
     (quick-add-form)
     ;; Task list
     (task-list focused-tasks project-by-id today)
     ;; Add from backlog
     [:div.mt-6.pt-4.border-t.border-dark
      (backlog-picker-button)]]))

(defn today-view
  "Full page view for /app/task/today"
  [ctx]
  (ui/page
   ctx
   (side-bar
    ctx
    [:div.max-w-2xl.mx-auto.p-4
     (today-content ctx)])))
