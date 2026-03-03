(ns tech.jgood.gleanmo.ui.icons
  "Inline Lucide SVG icon helpers.

   Each function returns a hiccup SVG element. Pass an optional opts map
   with :class to override the default size (\"w-4 h-4\").")

(defn- lucide
  "Base SVG wrapper for Lucide icons."
  [opts children]
  (let [css-class (or (:class opts) "w-4 h-4")]
    (into [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
                 :fill "none" :stroke "currentColor" :stroke-width "2"
                 :stroke-linecap "round" :stroke-linejoin "round"
                 :class css-class}]
          children)))

(defn clock-arrow-up
  "Carried-over indicator icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M12 6v6l1.56.78"}]
           [:path {:d "M13.227 21.925a10 10 0 1 1 8.767-9.588"}]
           [:path {:d "m14 18 4-4 4 4"}]
           [:path {:d "M18 22v-8"}]]))

(defn grip-vertical
  "Drag handle icon."
  [& [opts]]
  (lucide opts
          [[:circle {:cx "9" :cy "12" :r "1"}]
           [:circle {:cx "9" :cy "5" :r "1"}]
           [:circle {:cx "9" :cy "19" :r "1"}]
           [:circle {:cx "15" :cy "12" :r "1"}]
           [:circle {:cx "15" :cy "5" :r "1"}]
           [:circle {:cx "15" :cy "19" :r "1"}]]))

(defn plus
  "Add / create icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M5 12h14"}]
           [:path {:d "M12 5v14"}]]))

(defn pencil
  "Edit icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"}]
           [:path {:d "m15 5 4 4"}]]))

(defn arrow-right
  "Forward / defer icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M5 12h14"}]
           [:path {:d "m12 5 7 7-7 7"}]]))

(defn x
  "Close / remove icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M18 6 6 18"}]
           [:path {:d "m6 6 12 12"}]]))

(defn check
  "Checkmark / complete icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M20 6 9 17l-5-5"}]]))
