(ns tech.jgood.gleanmo.ui.icons
  "The single icon namespace: inline Lucide SVG helpers and the entity icon
   registry. Nothing outside this namespace draws an app icon or maps an
   entity to one (the landing page's bespoke marks in `home.clj` excepted).

   Each icon function returns a hiccup SVG element. Pass an optional opts map
   with :class to set size and colour (default \"w-4 h-4\"); icons stroke with
   `currentColor`, so a text colour class tints them.

   Style guide, so hand- and AI-drawn customs match:
   - Prefer real Lucide (lucide.dev, ISC). Copy the path children from the
     published SVG rather than drawing a look-alike or working from memory.
   - Where Lucide lacks a glyph, draw a custom in the house style, or take it
     from Tabler (MIT) and note the provenance in the icon's docstring.
   - 24×24 viewBox, `fill=\"none\"`, `stroke=\"currentColor\"`,
     `stroke-width=\"2\"`, round linecaps and linejoins — all supplied by the
     `lucide` wrapper, so an icon body is only its shape children.")

(defn- lucide
  "Base SVG wrapper for Lucide icons."
  [opts children]
  (let [css-class (or (:class opts) "w-4 h-4")]
    (into [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"
                 :fill "none" :stroke "currentColor" :stroke-width "2"
                 :stroke-linecap "round" :stroke-linejoin "round"
                 :aria-hidden "true"
                 :class css-class}]
          children)))

;; ---------------------------------------------------------------------------
;; Actions and chrome
;; ---------------------------------------------------------------------------

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

(defn trash-2
  "Delete icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M3 6h18"}]
           [:path {:d "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"}]
           [:path {:d "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"}]
           [:line {:x1 "10" :x2 "10" :y1 "11" :y2 "17"}]
           [:line {:x1 "14" :x2 "14" :y1 "11" :y2 "17"}]]))

(defn copy
  "Copy to clipboard icon."
  [& [opts]]
  (lucide opts
          [[:rect {:width "14" :height "14" :x "8" :y "8" :rx "2" :ry "2"}]
           [:path {:d "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"}]]))

(defn menu
  "Open navigation menu icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M4 12h16"}]
           [:path {:d "M4 18h16"}]
           [:path {:d "M4 6h16"}]]))

(defn house
  "Home icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"}]
           [:path {:d "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]]))

(defn chevron-right
  "Disclosure / expand icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "m9 18 6-6-6-6"}]]))

(defn search
  "Search / pattern detection icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "m21 21-4.34-4.34"}]
           [:circle {:cx "11" :cy "11" :r "8"}]]))

(defn pin
  "Pushpin icon — pin a task to today."
  [& [opts]]
  (lucide opts
          [[:path {:d "M12 17v5"}]
           [:path {:d "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"}]]))

(defn sparkles
  "Celebration / empty-state icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"}]
           [:path {:d "M20 3v4"}]
           [:path {:d "M22 5h-4"}]
           [:path {:d "M4 17v2"}]
           [:path {:d "M5 18H3"}]]))

(defn lock
  "Sensitive-data icon."
  [& [opts]]
  (lucide opts
          [[:rect {:width "18" :height "11" :x "3" :y "11" :rx "2" :ry "2"}]
           [:path {:d "M7 11V7a5 5 0 0 1 10 0v4"}]]))

(defn archive
  "Archived-data icon."
  [& [opts]]
  (lucide opts
          [[:rect {:width "20" :height "5" :x "2" :y "3" :rx "1"}]
           [:path {:d "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"}]
           [:path {:d "M10 12h4"}]]))

(defn settings
  "Account settings icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"}]
           [:circle {:cx "12" :cy "12" :r "3"}]]))

(defn shield
  "Monitoring icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"}]]))

(defn target
  "Task focus icon."
  [& [opts]]
  (lucide opts
          [[:circle {:cx "12" :cy "12" :r "10"}]
           [:circle {:cx "12" :cy "12" :r "6"}]
           [:circle {:cx "12" :cy "12" :r "2"}]]))

(defn boxes
  "Manage entities icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M2.97 12.92A2 2 0 0 0 2 14.63v3.24a2 2 0 0 0 .97 1.71l3 1.8a2 2 0 0 0 2.06 0L12 19v-5.5l-5-3-4.03 2.42Z"}]
           [:path {:d "m7 16.5-4.74-2.85"}]
           [:path {:d "m7 16.5 5-3"}]
           [:path {:d "M7 16.5v5.17"}]
           [:path {:d "M12 13.5V19l3.97 2.38a2 2 0 0 0 2.06 0l3-1.8a2 2 0 0 0 .97-1.71v-3.24a2 2 0 0 0-.97-1.71L17 10.5l-5 3Z"}]
           [:path {:d "m17 16.5-5-3"}]
           [:path {:d "m17 16.5 4.74-2.85"}]
           [:path {:d "M17 16.5v5.17"}]
           [:path {:d "M7.97 4.42A2 2 0 0 0 7 6.13v4.37l5 3 5-3V6.13a2 2 0 0 0-.97-1.71l-3-1.8a2 2 0 0 0-2.06 0l-3 1.8Z"}]
           [:path {:d "M12 8 7.26 5.15"}]
           [:path {:d "m12 8 4.74-2.85"}]
           [:path {:d "M12 13.5V8"}]]))

(defn list-checks
  "Activity logs icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "m3 17 2 2 4-4"}]
           [:path {:d "m3 7 2 2 4-4"}]
           [:path {:d "M13 6h8"}]
           [:path {:d "M13 12h8"}]
           [:path {:d "M13 18h8"}]]))

(defn calendar-days
  "Calendar view icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M8 2v4"}]
           [:path {:d "M16 2v4"}]
           [:rect {:width "18" :height "18" :x "3" :y "4" :rx "2"}]
           [:path {:d "M3 10h18"}]
           [:path {:d "M8 14h.01"}]
           [:path {:d "M12 14h.01"}]
           [:path {:d "M16 14h.01"}]
           [:path {:d "M8 18h.01"}]
           [:path {:d "M12 18h.01"}]
           [:path {:d "M16 18h.01"}]]))

(defn chart-column
  "Statistics icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M3 3v16a2 2 0 0 0 2 2h16"}]
           [:path {:d "M18 17V9"}]
           [:path {:d "M13 17V5"}]
           [:path {:d "M8 17v-3"}]]))

;; ---------------------------------------------------------------------------
;; Entity glyphs — read these through `entity-icon`, not directly, wherever
;; the icon stands for an entity type.
;; ---------------------------------------------------------------------------

(defn square-check
  "Task icon."
  [& [opts]]
  (lucide opts
          [[:rect {:width "18" :height "18" :x "3" :y "3" :rx "2"}]
           [:path {:d "m9 12 2 2 4-4"}]]))

(defn repeat-icon
  "Habit icon (Lucide `repeat`; renamed to avoid shadowing `clojure.core/repeat`)."
  [& [opts]]
  (lucide opts
          [[:path {:d "m17 2 4 4-4 4"}]
           [:path {:d "M3 11v-1a4 4 0 0 1 4-4h14"}]
           [:path {:d "m7 22-4-4 4-4"}]
           [:path {:d "M21 13v1a4 4 0 0 1-4 4H3"}]]))

(defn medit
  "Meditation icon. Custom — Lucide has no lotus or meditation pose. Two
   concentric rings carried over from the original overview glyph, restroked
   at the house stroke width."
  [& [opts]]
  (lucide opts
          [[:circle {:cx "12" :cy "12" :r "8"}]
           [:circle {:cx "12" :cy "12" :r "2.5"}]]))

(defn pill
  "Medication icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "m10.5 20.5 10-10a4.95 4.95 0 1 0-7-7l-10 10a4.95 4.95 0 1 0 7 7Z"}]
           [:path {:d "m8.5 8.5 7 7"}]]))

(defn map-pin
  "Location icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"}]
           [:circle {:cx "12" :cy "10" :r "3"}]]))

(defn briefcase
  "Project icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M16 20V4a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"}]
           [:rect {:width "20" :height "14" :x "2" :y "6" :rx "2"}]]))

(defn book-open
  "Book / reading icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M12 7v14"}]
           [:path {:d "M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z"}]]))

(defn store
  "Book source icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7"}]
           [:path {:d "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"}]
           [:path {:d "M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4"}]
           [:path {:d "M2 7h20"}]
           [:path {:d "M22 7v3a2 2 0 0 1-2 2a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 16 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 12 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 8 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 4 12a2 2 0 0 1-2-2V7"}]]))

(defn dumbbell
  "Exercise icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M17.596 12.768a2 2 0 1 0 2.829-2.829l-1.768-1.767a2 2 0 0 0 2.828-2.829l-2.828-2.828a2 2 0 0 0-2.829 2.828l-1.767-1.768a2 2 0 1 0-2.829 2.829z"}]
           [:path {:d "m2.5 21.5 1.4-1.4"}]
           [:path {:d "m20.1 3.9 1.4-1.4"}]
           [:path {:d "M5.343 21.485a2 2 0 1 0 2.829-2.828l1.767 1.768a2 2 0 1 0 2.829-2.829l-6.364-6.364a2 2 0 1 0-2.829 2.829l1.768 1.767a2 2 0 0 0-2.828 2.829z"}]
           [:path {:d "m9.6 14.4 4.8-4.8"}]]))

(defn timer
  "Timer icon — also the exercise-set entity icon."
  [& [opts]]
  (lucide opts
          [[:line {:x1 "10" :x2 "14" :y1 "2" :y2 "2"}]
           [:line {:x1 "12" :x2 "15" :y1 "14" :y2 "11"}]
           [:circle {:cx "12" :cy "14" :r "8"}]]))

(defn list-ordered
  "Exercise line icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M10 12h11"}]
           [:path {:d "M10 18h11"}]
           [:path {:d "M10 6h11"}]
           [:path {:d "M4 10h2"}]
           [:path {:d "M4 6h1v4"}]
           [:path {:d "M6 18H4c0-1 2-2 2-3s-1-1.5-2-1"}]]))

(defn mountain
  "Bouldering icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "m8 3 4 8 5-5 5 15H2L8 3z"}]]))

(defn thermometer
  "Symptom icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z"}]]))

(defn smile
  "Mood icon."
  [& [opts]]
  (lucide opts
          [[:circle {:cx "12" :cy "12" :r "10"}]
           [:path {:d "M8 14s1.5 2 4 2 4-2 4-2"}]
           [:line {:x1 "9" :x2 "9.01" :y1 "9" :y2 "9"}]
           [:line {:x1 "15" :x2 "15.01" :y1 "9" :y2 "9"}]]))

(defn droplet
  "BM log icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z"}]]))

(defn calendar
  "Calendar event icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M8 2v4"}]
           [:path {:d "M16 2v4"}]
           [:rect {:width "18" :height "18" :x "3" :y "4" :rx "2"}]
           [:path {:d "M3 10h18"}]]))

(defn flag
  "Goal icon."
  [& [opts]]
  (lucide opts
          [[:path {:d "M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528"}]]))

;; ---------------------------------------------------------------------------
;; Entity icon registry
;; ---------------------------------------------------------------------------

(def ^:private entity-icons
  "One icon per entity type. Parents and their logs share an icon; the label
   beside it already tells them apart."
  {:task             square-check
   :habit            repeat-icon
   :habit-log        repeat-icon
   :meditation       medit
   :meditation-log   medit
   :medication       pill
   :medication-log   pill
   :location         map-pin
   :project          briefcase
   :project-log      briefcase
   :book             book-open
   :reading-log      book-open
   :book-source      store
   :exercise         dumbbell
   :exercise-session dumbbell
   :exercise-set     timer
   :exercise-line    list-ordered
   :boulder-problem  mountain
   :boulder-attempt  mountain
   :boulder-session  mountain
   :symptom-episode  thermometer
   :symptom-log      thermometer
   :mood-log         smile
   :bm-log           droplet
   :calendar-event   calendar
   :goal             flag})

(defn entity-icon
  "The icon for an entity type. `entity` is a keyword or string entity key
   (`:habit-log` or \"habit-log\"); unknown or nil keys get a generic map pin.
   `opts` is passed through to the icon (see ns docstring)."
  ([entity] (entity-icon entity nil))
  ([entity opts]
   (let [icon-fn (get entity-icons (some-> entity keyword) map-pin)]
     (icon-fn opts))))
