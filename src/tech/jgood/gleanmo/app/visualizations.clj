(ns tech.jgood.gleanmo.app.visualizations
  (:require
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.ui :as ui]))

(defn visualizations-dashboard
  "Dashboard showing all available visualizations for temporal entities."
  [ctx]
  (ui/page
   ctx
   (side-bar 
    ctx
    [:div.container.mx-auto.p-6
     [:h1.text-3xl.font-bold.mb-6 "Data Visualizations"]
     [:p.mb-8.text-gray-400 "Interactive charts for your tracked activities"]
     
     [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-6
      ;; Habit Logs
      [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark-border
       [:h3.text-xl.font-semibold.mb-3.text-neon-lime "Habit Tracking"]
       [:p.text-gray-400.mb-4 "Calendar heatmap showing daily habit completion patterns"]
       [:a.inline-block.bg-neon-lime.text-black.px-4.py-2.rounded.font-medium.hover:bg-opacity-80
        {:href "/app/viz/habit-log"} "View Calendar"]]
      
      ;; Meditation Logs  
      [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark-border
       [:h3.text-xl.font-semibold.mb-3.text-neon-cyan "Meditation Sessions"]
       [:p.text-gray-400.mb-4 "Timeline and frequency charts for meditation practice"]
       [:a.inline-block.bg-neon-cyan.text-black.px-4.py-2.rounded.font-medium.hover:bg-opacity-80
        {:href "/app/viz/meditation-log"} "View Charts"]]
      
      ;; BM Logs
      [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark-border
       [:h3.text-xl.font-semibold.mb-3.text-neon-azure "Health Tracking"]
       [:p.text-gray-400.mb-4 "Daily health metrics and pattern analysis"] 
       [:a.inline-block.bg-neon-azure.text-black.px-4.py-2.rounded.font-medium.hover:bg-opacity-80
        {:href "/app/viz/bm-log"} "View Calendar"]]
      
      ;; Medication Logs
      [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark-border
       [:h3.text-xl.font-semibold.mb-3.text-neon-pink "Medication Tracking"]
       [:p.text-gray-400.mb-4 "Track medication doses and timing patterns"]
       [:a.inline-block.bg-neon-pink.text-black.px-4.py-2.rounded.font-medium.hover:bg-opacity-80
        {:href "/app/viz/medication-log"} "View Calendar"]]]
     
     [:div.mt-12
      [:h2.text-2xl.font-bold.mb-4 "About Visualizations"]
      [:div.bg-dark-surface.rounded-lg.p-6.border.border-dark-border
       [:p.text-gray-400.mb-4 
        "These visualizations are automatically generated based on the temporal patterns in your data. Each chart type is optimized for the specific data structure:"]
       [:ul.list-disc.list-inside.text-gray-400.space-y-2
        [:li "📅 Calendar heatmaps show activity frequency over time"]
        [:li "⏱️ Timeline charts display duration and intervals"] 
        [:li "📊 Pattern analysis reveals trends and habits"]
        [:li "🎨 All charts use your custom theme colors"]]]]])))

(def routes
  ["/visualizations" {:get visualizations-dashboard}])