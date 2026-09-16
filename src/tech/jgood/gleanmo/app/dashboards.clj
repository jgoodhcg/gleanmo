(ns tech.jgood.gleanmo.app.dashboards
  (:require
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.ui.icons :as icons]))

(defn- show-bm-logs?
  [ctx]
  (true? (:show-bm-logs (queries/resolve-user-settings ctx))))

(defn dashboard-card
  "Create a card for dashboard navigation. `icon` is an icon function from
   `ui.icons` (called at card size, tinted with the card accent) or an entity
   key, which is looked up in the entity icon registry."
  [title description href icon color-class]
  [:a.block.group {:href href}
   [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark.transition-all.duration-300.hover:shadow-lg.hover:transform.hover:scale-105
    {:class (str "hover:border-" color-class)}
    [:div.flex.items-center.mb-3
     [:div.w-12.h-12.rounded-lg.flex.items-center.justify-center.mr-4
      {:class (str "bg-" color-class ".bg-opacity-20")}
      (let [opts {:class (str "w-6 h-6 text-" color-class)}]
        (if (keyword? icon)
          (icons/entity-icon icon opts)
          (icon opts)))]
     [:h3.text-xl.font-semibold.text-white title]]
    [:p.text-gray-400.text-sm description]]])

(defn- icon-label
  "A section header label led by an icon."
  [icon label]
  [:span.inline-flex.items-center.gap-2 (icon {:class "w-3.5 h-3.5"}) label])

(defn entities-dashboard
  "Dashboard for managing core entities"
  [ctx]
  (ui/page
   ctx
   (layout/page-shell
    ctx {:width :wide}
    (layout/page-header
     {:title "Manage Entities"
      :subtitle "Create and manage your core data entities"})
    ;; First, and in its own section: every card below lands on a generated
    ;; CRUD list and these do not. Mixed into that grid, a custom screen read
    ;; as though it were just another CRUD entity.
    (layout/section-header "PURPOSE-BUILT SCREENS")
    [:p.text-sm.text-gray-400
     "Hand-built for jobs the generic CRUD forms handle awkwardly."]
    [:div.grid.grid-cols-1.md:grid-cols-2.gap-6
     (dashboard-card "Goals"
                     "Targets, weekly rhythms, and books to finish, from your logs"
                     "/app/goals" :goal "neon-cyan")
     (dashboard-card "Retire / Restore Problems"
                     "Bulk-manage which problems are on the wall"
                     "/app/boulder/problems" :boulder-problem "neon-lime")]

    (layout/section-header "ENTITIES")
    [:div.grid.grid-cols-1.md:grid-cols-2.gap-6
     (dashboard-card "Tasks" "Things to do, with behavioral signals"
                     "/app/crud/task" :task "neon-lime")
     (dashboard-card "Habits" "Daily routines you want to track"
                     "/app/crud/habit" :habit "neon-cyan")
     (dashboard-card "Meditations" "Types of meditation practices"
                     "/app/crud/meditation" :meditation "neon-cyan")
     (dashboard-card "Medications" "Medications and dosages"
                     "/app/crud/medication" :medication "neon-pink")
     (dashboard-card "Locations" "Places where activities happen"
                     "/app/crud/location" :location "neon-azure")
     (dashboard-card "Projects" "Time tracking projects"
                     "/app/crud/project" :project "neon-yellow")
     (dashboard-card "Books" "Books you're reading or have read"
                     "/app/crud/book" :book "neon-azure")
     (dashboard-card "Book Sources" "Where books come from"
                     "/app/crud/book-source" :book-source "neon-azure")
     (dashboard-card "Exercises" "Exercise definitions for workouts"
                     "/app/crud/exercise" :exercise "neon-pink")
     (dashboard-card "Boulder Problems" "Gym problems, walls, and holds"
                     "/app/crud/boulder-problem" :boulder-problem "neon-lime")
     (dashboard-card "Symptom Episodes" "Illness or injury periods"
                     "/app/crud/symptom-episode" :symptom-episode "neon-pink")])))

(defn activity-logs-dashboard
  "Dashboard for viewing activity logs"
  [ctx]
  (ui/page
   ctx
   (layout/page-shell
    ctx {:width :wide}
    (layout/page-header
     {:title "Activity Logs"
      :subtitle "View and manage your logged activities"})
    [:div.grid.grid-cols-1.md:grid-cols-2.gap-6
     (dashboard-card "Habit Logs" "Habit completion records"
                     "/app/crud/habit-log" :habit-log "neon-lime")
     (dashboard-card "Meditation Logs" "Meditation session records"
                     "/app/crud/meditation-log" :meditation-log "neon-cyan")
     (when (show-bm-logs? ctx)
       (dashboard-card "BM Logs" "BM tracking entries"
                       "/app/crud/bm-log" :bm-log "neon-azure"))
     (dashboard-card "Medication Logs" "Medication intake records"
                     "/app/crud/medication-log" :medication-log "neon-pink")
     (dashboard-card "Project Logs" "Time tracking entries"
                     "/app/crud/project-log" :project-log "neon-yellow")
     (dashboard-card "Reading Logs" "Book reading session records"
                     "/app/crud/reading-log" :reading-log "neon-azure")
     (dashboard-card "Symptom Logs" "Symptom entries and vitals"
                     "/app/crud/symptom-log" :symptom-log "neon-pink")
     (dashboard-card "Mood Logs" "Mood, energy, and stress check-ins"
                     "/app/crud/mood-log" :mood-log "neon-cyan")
     (dashboard-card "Exercise Sessions" "Workout session records"
                     "/app/crud/exercise-session" :exercise-session "neon-pink")
     (dashboard-card "Exercise Sets" "Timed sets within workouts"
                     "/app/crud/exercise-set" :exercise-set "neon-pink")
     (dashboard-card "Exercise Lines" "Reps/weight of an exercise within a set"
                     "/app/crud/exercise-line" :exercise-line "neon-pink")
     (dashboard-card "Boulder Sessions" "Climbing gym sessions"
                     "/app/crud/boulder-session" :boulder-session "neon-lime")
     (dashboard-card "Boulder Attempts" "Problem attempts and sends"
                     "/app/crud/boulder-attempt" :boulder-attempt "neon-lime")])))

(defn stats-dashboard
  "Dashboard for visualizations and statistics"
  [ctx]
  (ui/page
   ctx
   (layout/page-shell
    ctx {:width :wide}
    (layout/page-header
     {:title "Stats & Charts"
      :subtitle "Visualize patterns and explore your data"})
    [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-6
      ;; Visualizations
     [:div.lg:col-span-3 (layout/section-header
                          (icon-label icons/calendar-days "ACTIVITY CALENDARS"))]

     (dashboard-card "Habit Calendar" "Daily habit completion patterns"
                     "/app/viz/habit-log" :habit-log "neon-lime")
     (dashboard-card "Meditation Calendar" "Meditation session frequency"
                     "/app/viz/meditation-log" :meditation-log "neon-cyan")
     (when (show-bm-logs? ctx)
       (dashboard-card "BM Calendar" "BM tracking calendar"
                       "/app/viz/bm-log" :bm-log "neon-azure"))
     (dashboard-card "Medication Calendar" "Medication intake calendar"
                     "/app/viz/medication-log" :medication-log "neon-pink")
     (dashboard-card "Project Calendar" "Time tracking calendar"
                     "/app/viz/project-log" :project-log "neon-yellow")
     (dashboard-card "Symptom Calendar" "Symptom log frequency"
                     "/app/viz/symptom-log" :symptom-log "neon-pink")
     (dashboard-card "Mood Calendar" "Mood check-in patterns"
                     "/app/viz/mood-log" :mood-log "neon-cyan")
     (dashboard-card "Exercise Calendar" "Workout session frequency"
                     "/app/viz/exercise-session" :exercise-session "neon-pink")
     (dashboard-card "Bouldering Calendar" "Climbing session frequency"
                     "/app/viz/boulder-session" :boulder-session "neon-lime")

      ;; Statistics
     [:div.lg:col-span-3.mt-4 (layout/section-header
                               (icon-label icons/chart-column "STATISTICS"))]

     (dashboard-card "Habit Patterns" "Pattern detection and date predictions"
                     "/app/stats/habit-patterns" icons/search "neon-lime")
     (dashboard-card "Meditation Stats" "Session duration and frequency stats"
                     "/app/stats/meditation" :meditation-log "neon-cyan")
     (when (show-bm-logs? ctx)
       (dashboard-card "BM Stats" "BM tracking statistics"
                       "/app/stats/bm" :bm-log "neon-azure"))
     (dashboard-card "Medication History" "Per-medication dosage timeline"
                     "/app/stats/medication-history" :medication-log "neon-pink")])))

(def routes
  ["/dashboards" {}
   ["/entities" {:get entities-dashboard}]
   ["/activity-logs" {:get activity-logs-dashboard}]
   ["/stats" {:get stats-dashboard}]])
