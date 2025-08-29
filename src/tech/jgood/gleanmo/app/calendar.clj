(ns tech.jgood.gleanmo.app.calendar
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone str->instant!]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(defn color-neon->tailwind
  "Maps neon color keywords to neon Tailwind background color classes."
  [color-neon]
  (case (or color-neon :blue)
    :blue   "bg-neon-azure"
    :cyan   "bg-neon-cyan"
    :green  "bg-neon-lime" 
    :violet "bg-neon-violet"
    :red    "bg-neon-pink"
    :orange "bg-neon-amber"
    "bg-neon-azure")) ; fallback

(defn calendar-day-cell
  "Renders a single day cell for the calendar grid."
  [year month day month-name days-in-month next-month-days cell-width
   events-by-date]
  (let [current-date        (t/date (str year
                                         "-" (format "%02d" month)
                                         "-" (format "%02d" day)))
        today               (t/today)
        day-of-week         (t/int (t/day-of-week current-date))
        is-today            (= current-date today)
        is-weekend          (or (= day-of-week 6) (= day-of-week 7)) ; Saturday
                                                                     ; = 6,
                                                                     ; Sunday =
                                                                     ; 7
        is-past             (t/< current-date today)
        is-last-day         (= day days-in-month)
        is-last-month       (= month 12)
        needs-bottom-border (or is-last-month (> day next-month-days))
        date-str            (str year
                                 "-" (format "%02d" month)
                                 "-" (format "%02d" day))
        border-classes      (str (if is-today "border-2 border-neon-gold" "border-t border-l border-dark")
                                 (when (and is-last-day (not is-today)) " border-r")
                                 (when (and needs-bottom-border (not is-today)) " border-b"))
        ;; Background: weekends get subtle surface; weekdays transparent
        bg-class            (cond
                              is-weekend "bg-dark-surface"
                              :else      "")
        ;; Past day styling - fade text and reduce opacity while keeping accessible
        past-classes        (when is-past "opacity-60")
        text-classes        (if is-past "text-gray-600" "text-gray-500")
        ;; Get events for this day
        day-events          (get events-by-date date-str [])]
    [:div.day-cell.hover:border-neon-gold.hover:border-2.text-xs.cursor-pointer.relative.transition-all
     {:key       (str month "-" day),
      :title     (str month-name " " day ", " year),
      :class     (str border-classes " " bg-class " " text-classes " " past-classes),
      :hx-get    (str "/app/calendar/event-form?date=" date-str),
      :hx-target "#bc-modal",
      :hx-swap   "innerHTML",
      :style     {:width        cell-width,
                  :aspect-ratio "3/4"}}
     [:div.absolute.top-1.left-1 day]

     ;; Event boxes
     (when (seq day-events)
       [:div.absolute.top-6.left-1.right-1.bottom-1.space-y-1
        (for [event (take 3 day-events)] ; Limit to 3 events to avoid
                                         ; overflow
          (let [bg-color (color-neon->tailwind (:calendar-event/color-neon event))
                eid      (:xt/id event)]
            [:a.block.w-full.rounded.px-1.py-0.5.text-xs.text-white.overflow-hidden.cursor-pointer.transition-all.hover:opacity-90.hover:border-2.hover:border-neon-gold.focus-visible:outline.focus-visible:outline-2.focus-visible:outline-neon-gold
             {:key      eid,
              :class    bg-color,
              :title    (:calendar-event/label event),
              :href     (str "/app/crud/form/calendar-event/edit/" eid),
              :onclick  "event.stopPropagation();"}
             [:div.truncate (:calendar-event/label event)]]))])]))

(defn calendar-navigation
  "Renders the navigation section for the calendar."
  []
  [:div.mt-8.text-center
   [:a.inline-flex.items-center.px-4.py-2.bg-gray-700.hover:bg-gray-600.text-white.rounded.transition-colors
    {:href "/app"}
    "← Back to Home"]])

(defn htmx-modal-container
  "Renders the HTMX modal container for the calendar."
  []
  [:div#bc-modal.fixed.inset-0.z-50.flex.items-center.justify-center.bg-black.bg-opacity-50.hidden
   {:hx-on
    "htmx:afterSwap: (this.innerHTML.trim()===''? this.classList.add('hidden') : this.classList.remove('hidden'))"}])

(defn calendar-month-row
  [{:keys [year
           month
           cell-width
           events-by-date]}]
  (let [first-day       (t/date
                         (str year "-" (format "%02d" month) "-01"))
        month-name      (t/format (t/formatter "MMM") first-day)
        last-day        (t/last-day-of-month first-day)
        days-in-month   (t/day-of-month last-day)
        days            (range 1 (inc days-in-month))
        ;; Calculate next month's days to determine which cells
        ;; need bottom borders
        next-month-days (if (< month 12)
                          (let [next-first-day (t/date (str year
                                                            "-"
                                                            (format
                                                             "%02d"
                                                             (inc
                                                              month))
                                                            "-01"))
                                next-last-day  (t/last-day-of-month
                                                next-first-day)]
                            (t/day-of-month next-last-day))
                          0)] ; December has no next month
    [:div.month-row.flex.items-center.w-full {:key month}
     [:div.month-label.w-8.px-1.mx-0.flex-shrink-0.text-primary.font-medium.text-sm.text-right.uppercase
      {:title month-name}
      month-name]
     [:div.days-container.flex.border.border-transparent.hover:border-gray-600.rounded-md.transition-colors
      (for [day days]
        (calendar-day-cell year
                           month
                           day
                           month-name
                           days-in-month
                           next-month-days
                           cell-width
                           events-by-date))]]))

(defn year-calendar
  [{:keys [session biff/db params], :as ctx}]
  (let [;; Determine which year to display: prefer query param with sane bounds
        current-year   (t/int (t/year (t/today)))
        year-param     (:year params)
        parsed-year    (when (some? year-param)
                         (try
                           (Integer/parseInt (str year-param))
                           (catch Exception _ nil)))
        year           (cond
                         (and parsed-year (<= 1900 parsed-year 2100)) parsed-year
                         :else                                          current-year)
        months         (range 1 13)
        ;; Calculate available width for days (full screen minus month
        ;; label and padding). Month label is 2rem; effective right padding 1rem
        ;; (left padding offset by negative margin in desktop layout).
        cell-width     "calc((100vw - 2rem - 1rem) / 31)" ; month-label + padding

        ;; Query events for the user and current year
        user-id        (:uid session)
        user-tz        (get-user-time-zone ctx)
        events         (when user-id
                         (db/get-events-for-user-year db user-id year user-tz))
        event-count    (count events)

        ;; Group events by date string (YYYY-MM-DD)
        events-by-date (group-by (fn [event]
                                   (let [dt   (:calendar-event/beginning event)
                                         zone (java.time.ZoneId/of (or user-tz
                                                                       "UTC"))]
                                     (when dt
                                       (-> dt
                                           (t/in zone)
                                           (t/date)
                                           str))))
                                 events)]

    (ui/page
     {}
     [:div.w-full.min-h-screen.p-4
      ;; Mobile/small screen message
      [:div.block.md:hidden.text-center.py-12.px-6
       [:div.max-w-md.mx-auto.bg-gray-800.rounded-lg.p-6.border.border-gray-600
        [:h2.text-xl.font-semibold.text-white.mb-4 "Desktop View Required"]
        [:p.text-gray-300.mb-4 
         "The year-at-a-glance calendar is designed for desktop viewing to show all 12 months and events clearly."]
        [:p.text-gray-400.text-sm.mb-6
         "Please use a larger screen (tablet or desktop) to access the full calendar experience."]
        [:a.inline-flex.items-center.px-4.py-2.bg-gray-700.hover:bg-gray-600.text-white.rounded.transition-colors
         {:href "/app"}
         "← Back to Home"]]]
      
      ;; Desktop calendar (hidden on mobile)
      [:div.hidden.md:block
       ;; Top navigation bar (full-width at top)
       [:div.fixed.top-0.inset-x-0.z-40.bg-dark-surface.border-b.border-dark.py-2.px-3
        [:div.flex.items-center.justify-between
         [:a.link.text-sm {:href "/app"} "← Home"]
         [:div.flex.items-center.space-x-2
          [:a.link.text-sm
           {:href (str "/app/calendar/year?year=" (dec year))}
           (str "← " (dec year))]
          [:h1.text-xl.font-bold.text-primary (str year " Calendar")]
          [:a.link.text-sm
           {:href (str "/app/calendar/year?year=" (inc year))}
           (str (inc year) " →")]]]]

       ;; Desktop content offset for fixed header
       [:div.mt-12.pl-0.pr-4.-ml-4
         ;; Hidden element to handle calendar refresh
        [:div#calendar-refresh-trigger.hidden
         {:hx-get (str "/app/calendar/year?year=" year)
          :hx-trigger "eventCreated from:body"
          :hx-select "#calendar-content"
          :hx-target "#calendar-content"
          :hx-swap "outerHTML"}]

        [:div.year-calendar
         [:div#calendar-content
          (for [month months]
            (calendar-month-row
             (pot/map-of year
                         month
                         cell-width
                         events-by-date)))]]]]

       ;; HTMX modal target container (fixed overlay, hidden by default)
      (htmx-modal-container)])))

(defn big-calendar-event-form
  "HTMX fragment: Modal form to create a simple event for a given date.
   Expects query param `date` as YYYY-MM-DD."
  [{:keys [params], :as ctx}]
  (let [date (or (:date params) (str (t/date (t/today))))
        tz   (get-user-time-zone ctx)]
    [:div.form-section.shadow-xl.w-full.max-w-md
     [:div.flex.items-center.justify-between.mb-2
      [:h3.form-header "Add Event"]
      [:button.text-secondary.hover:text-primary
       {:type "button",
        :aria-label "Close",
        :onclick
        "var m=htmx.find('#bc-modal'); m.innerHTML=''; m.classList.add('hidden');"}
       "✕"]]

     (biff/form
      {:id        "bc-event-form",
       :hx-post   "/app/calendar/events",
       :hx-target "#bc-modal",
       :hx-swap   "innerHTML"}

      [:div.text-sm.mb-3
       [:span.opacity-80 "Date: "] [:span.font-mono date]
       [:span.opacity-60 (str " • All-day (" tz ")")]]

      [:div.mb-3
       [:label.form-label {:for "calendar-event-label"} "Label"]
       [:input#w-calendar-event-label.form-input.w-full
        {:type         "text",
         :name         "calendar-event/label",
         :required     true,
         :autocomplete "off"}]]

      [:div.mb-3
       [:label.form-label {:for "calendar-event-color-neon"} "Color"]
       [:select#calendar-event-color-neon.form-input.w-full
        {:name "calendar-event/color-neon"}
        [:option {:value "blue"} "Azure"]
        [:option {:value "cyan"} "Cyan"]
        [:option {:value "green"} "Lime"]
        [:option {:value "violet"} "Violet"]
        [:option {:value "red"} "Pink"]
        [:option {:value "orange"} "Amber"]
]]

      ;; Hidden to keep original clicked date if needed later
      [:input {:type "hidden", :name "date", :value date}]
      [:input {:type "hidden", :name "calendar-event/all-day", :value "true"}]

      [:div.flex.justify-end.space-x-2.mt-4
       [:button.form-button-secondary
        {:type "button",
         :onclick
         "var m=htmx.find('#bc-modal'); m.innerHTML=''; m.classList.add('hidden');"}
        "Cancel"]
       [:button.form-button-primary {:type "submit"} "Create"]])]))

(defn big-calendar-create-event!
  [{:keys [session params], :as ctx}]
  (let [kw-key  (fn [k]
                  (cond (keyword? k) k
                        (string? k)  (keyword k)
                        (symbol? k)  (keyword k)
                        :else        k))
        p       (->> params
                     (remove (fn [[k _]] (= (kw-key k) :__anti-forgery-token)))
                     (into {} (map (fn [[k v]] [(kw-key k) v]))))
        user-id     (:uid session)
        label       (some-> (:calendar-event/label p)
                            str/trim)
        color-neon (keyword (:calendar-event/color-neon p "blue"))
        date        (:date p)
        tz          (get-user-time-zone ctx)]
    (cond
      (or (str/blank? label) (str/blank? date))
      (let [error "Label and date are required."]
        (log/warn error)
        [:div.form-section.shadow-xl.w-full.max-w-md
         [:div.flex.items-center.justify-between.mb-2
          [:h3.form-header "Add Event"]
          [:button.text-secondary.hover:text-primary
           {:type "button",
            :aria-label "Close",
            :onclick
            "var m=htmx.find('#bc-modal'); m.innerHTML=''; m.classList.add('hidden');"}
           "✕"]]
         [:div.text-sm.mb-3.text-secondary error]
         (biff/form
          {:hx-get    (str "/app/calendar/event-form?date=" date),
           :hx-target "#bc-modal",
           :hx-swap   "innerHTML"}
          [:button.form-button-secondary "Back"])])

      :else
      (try
        (let [start-str  (str date "T00:00")
                ;; Convert local midnight in user TZ to UTC instant
              zid        (java.time.ZoneId/of (or tz "UTC"))
              start-inst (str->instant! start-str zid)
              event      {:user/id user-id,
                          :calendar-event/label label,
                          :calendar-event/beginning start-inst,
                          :calendar-event/all-day true,
                          :calendar-event/time-zone tz,
                          :calendar-event/color-neon color-neon,
                          :calendar-event/source :gleanmo}
              eid        (random-uuid)
              doc        (merge {:xt/id          eid,
                                 ::sm/type       :calendar-event,
                                 ::sm/created-at (t/now)}
                                event)
              tx         [(merge {:db/doc-type :calendar-event,
                                  :xt/id       (:xt/id doc)}
                                 doc)]
              txres      (biff/submit-tx ctx tx)]
            ;; Clear modal content and trigger calendar refresh
          {:status 200, :headers {"content-type" "text/html", "HX-Trigger" "eventCreated"}, :body ""})
        (catch Exception e
          (log/error e "BigCal create-event: submit-tx error")
          (let [error (str "Failed to create event: " (.getMessage e))]
            [:div.form-section.shadow-xl.w-full.max-w-md
             [:div.flex.items-center.justify-between.mb-2
              [:h3.form-header "Add Event"]
              [:button.text-secondary.hover:text-primary
               {:type "button",
                :aria-label "Close",
                :onclick
                "var m=htmx.find('#bc-modal'); m.innerHTML=''; m.classList.add('hidden');"}
               "✕"]]
             [:div.text-sm.mb-3.text-secondary error]
             (biff/form
              {:hx-get    (str "/app/calendar/event-form?date=" date),
               :hx-target "#bc-modal",
               :hx-swap   "innerHTML"}
              [:button.form-button-secondary "Back"])]))))))
