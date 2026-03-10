(ns tech.jgood.gleanmo.app.dashboards
  (:require
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]))

(defn- show-bm-logs?
  [ctx]
  (true? (:show-bm-logs (queries/resolve-user-settings ctx))))

(defn dashboard-card
  "Create a card for dashboard navigation"
  [title description href icon-class color-class]
  [:a.block.group {:href href}
   [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark.transition-all.duration-300.hover:shadow-lg.hover:transform.hover:scale-105
    {:class (str "hover:border-" color-class)}
    [:div.flex.items-center.mb-3
     [:div.w-12.h-12.rounded-lg.flex.items-center.justify-center.mr-4
      {:class (str "bg-" color-class ".bg-opacity-20")}
      [:div.text-2xl {:class (str "text-" color-class)} icon-class]]
     [:h3.text-xl.font-semibold.text-white title]]
    [:p.text-gray-400.text-sm description]]])

(defn entities-dashboard
  "Dashboard for managing core entities"
  [ctx]
  (ui/page
   ctx
   (side-bar
    ctx
    [:div.container.mx-auto.p-6
     [:h1.text-3xl.font-bold.mb-8.text-white "Manage Entities"]
     [:p.mb-8.text-gray-400 "Create and manage your core data entities"]

     [:div.grid.grid-cols-1.md:grid-cols-2.gap-6
      (dashboard-card "Tasks" "Things to do, with behavioral signals"
                      "/app/crud/task" "✅" "neon-lime")
      (dashboard-card "Habits" "Daily routines you want to track"
                      "/app/crud/habit" "🎯" "neon-cyan")
      (dashboard-card "Meditations" "Types of meditation practices"
                      "/app/crud/meditation" "🧘" "neon-cyan")
      (dashboard-card "Medications" "Medications and dosages"
                      "/app/crud/medication" "💊" "neon-pink")
      (dashboard-card "Locations" "Places where activities happen"
                      "/app/crud/location" "📍" "neon-azure")
      (dashboard-card "Projects" "Time tracking projects"
                      "/app/crud/project" "🚀" "neon-yellow")]])))

(defn activity-logs-dashboard
  "Dashboard for viewing activity logs"
  [ctx]
  (ui/page
   ctx
   (side-bar
    ctx
    [:div.container.mx-auto.p-6
     [:h1.text-3xl.font-bold.mb-8.text-white "Activity Logs"]
     [:p.mb-8.text-gray-400 "View and manage your logged activities"]

     [:div.grid.grid-cols-1.md:grid-cols-2.gap-6
      (dashboard-card "Habit Logs" "Habit completion records"
                      "/app/crud/habit-log" "📋" "neon-lime")
      (dashboard-card "Meditation Logs" "Meditation session records"
                      "/app/crud/meditation-log" "📜" "neon-cyan")
      (when (show-bm-logs? ctx)
        (dashboard-card "BM Logs" "BM tracking entries"
                        "/app/crud/bm-log" "🧻" "neon-azure"))
      (dashboard-card "Medication Logs" "Medication intake records"
                      "/app/crud/medication-log" "📊" "neon-pink")
      (dashboard-card "Project Logs" "Time tracking entries"
                      "/app/crud/project-log" "⏰" "neon-yellow")]])))

(defn stats-dashboard
  "Dashboard for visualizations and statistics"
  [ctx]
  (ui/page
   ctx
   (side-bar
    ctx
    [:div.container.mx-auto.p-6
     [:h1.text-3xl.font-bold.mb-8.text-white "Stats & Charts"]
     [:p.mb-8.text-gray-400 "Visualize patterns and explore your data"]

     [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-6
      ;; Visualizations
      [:div.lg:col-span-3
       [:h2.text-xl.font-semibold.mb-4.text-neon-lime "📅 Activity Calendars"]]

      (dashboard-card "Habit Calendar" "Daily habit completion patterns"
                      "/app/viz/habit-log" "🗓️" "neon-lime")
      (dashboard-card "Meditation Calendar" "Meditation session frequency"
                      "/app/viz/meditation-log" "🗓️" "neon-cyan")
      (when (show-bm-logs? ctx)
        (dashboard-card "BM Calendar" "BM tracking calendar"
                        "/app/viz/bm-log" "🧻" "neon-azure"))
      (dashboard-card "Medication Calendar" "Medication intake calendar"
                      "/app/viz/medication-log" "🗓️" "neon-pink")
      (dashboard-card "Project Calendar" "Time tracking calendar"
                      "/app/viz/project-log" "🗓️" "neon-yellow")

      ;; Statistics
      [:div.lg:col-span-3.mt-8
       [:h2.text-xl.font-semibold.mb-4.text-neon-cyan "📊 Statistics"]]

      (dashboard-card "Habit Patterns" "Pattern detection and date predictions"
                      "/app/stats/habit-patterns" "🔍" "neon-lime")
      (dashboard-card "Meditation Stats" "Session duration and frequency stats"
                      "/app/stats/meditation" "📊" "neon-cyan")
      (when (show-bm-logs? ctx)
        (dashboard-card "BM Stats" "BM tracking statistics"
                        "/app/stats/bm" "🧻" "neon-azure"))
      (dashboard-card "Medication History" "Per-medication dosage timeline"
                      "/app/stats/medication-history" "💊" "neon-pink")]])))

(def routes
  ["/dashboards" {}
   ["/entities" {:get entities-dashboard}]
   ["/activity-logs" {:get activity-logs-dashboard}]
   ["/stats" {:get stats-dashboard}]])
