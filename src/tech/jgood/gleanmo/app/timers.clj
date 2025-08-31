(ns tech.jgood.gleanmo.app.timers
  (:require
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]))

(def timer-entities
  "List of entities that support timer functionality"
  [{:entity-key :project-log
    :entity-str "project-log"
    :display-name "Projects"
    :description "Track time spent working on projects"
    :icon "📋"
    :route "/app/timer/project-log"}
   {:entity-key :meditation-log
    :entity-str "meditation-log"
    :display-name "Meditation"
    :description "Log focused meditation sessions"
    :icon "🧘"
    :route "/app/timer/meditation-log"}])

(defn timer-entity-card
  "Create a card for a timer-enabled entity"
  [{:keys [display-name description icon route]}]
  [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark.transition-all.duration-300.hover:shadow-lg.hover:border-neon-yellow
   [:div.flex.items-start.justify-between
    [:div.flex-1
     [:div.flex.items-center.gap-3.mb-2
      [:span.text-2xl icon]
      [:h3.text-lg.font-semibold.text-primary display-name]]
     [:p.text-sm.text-gray-400.mb-4 description]]
    [:div
     [:a.bg-neon-yellow.bg-opacity-20.text-neon-yellow.px-4.py-2.rounded.font-medium.hover:bg-opacity-30.transition-all.no-underline
      {:href route}
      "Open Timers"]]]])

(defn timers-dashboard
  "Main timers dashboard showing all available timer entities"
  [ctx]
  (ui/page
   ctx
   (side-bar
    ctx
    [:div.max-w-4xl
     [:h1.text-3xl.font-bold.text-primary.mb-2 "⏱️ Timers Dashboard"]
     [:p.text-gray-400.mb-8 "Track time across different activities and projects"]

     [:div.grid.gap-6.md:grid-cols-2.lg:grid-cols-1
      (for [entity timer-entities]
        ^{:key (:entity-key entity)}
        (timer-entity-card entity))]])))

(def routes
  ["/timers" {:get timers-dashboard}])
