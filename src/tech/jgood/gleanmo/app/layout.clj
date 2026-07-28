(ns tech.jgood.gleanmo.app.layout
  "Shared page vocabulary: the small set of layout components every
   authenticated page is built from.

   These exist because pages were written at different times and drifted —
   nine different `h1` treatments, ten different container widths, ad-hoc
   empty states. A documented class list would not have stopped the next page
   from drifting again, so the vocabulary is components: use `page-shell` and
   you get the right width, padding, and bottom clearance for the mobile tab
   bar without deciding anything.

   See roadmap/navigation-redesign.md."
  (:require
   [tech.jgood.gleanmo.app.shared :as shared]))

(def content-widths
  "Content column widths, chosen by what the page *is* rather than by taste.

   :narrow — focused single-column flows: forms, the workout and boulder
             screens, anything you operate one-handed on a phone.
   :normal — standard reading/content pages.
   :wide   — dashboards and card grids that earn the horizontal room.
   :full   — pages doing their own layout (calendar year grid)."
  {:narrow "max-w-2xl"
   :normal "max-w-4xl"
   :wide   "max-w-6xl"
   :full   "w-full"})

(defn page-shell
  "An authenticated page: sidebar chrome around a width-constrained column.

   Clearance for the fixed mobile tab bar is *not* handled here — it lives on
   the main content area in `shared/side-bar`, so pages that still use their
   own shell get it too."
  [ctx {:keys [width]} & content]
  (shared/side-bar
   ctx
   (into [:div {:class (str (get content-widths (or width :normal)
                                 (:normal content-widths))
                            " mx-auto w-full p-4 sm:p-6 space-y-6")}]
         content)))

(defn page-header
  "Page title, optional subtitle, optional right-aligned actions.

   One `h1` treatment for the whole app; pages that want emphasis get it from
   the subtitle or actions, not from a bigger heading."
  [{:keys [title subtitle actions]}]
  [:div.flex.items-start.justify-between.gap-3
   [:div.min-w-0
    [:h1.text-2xl.font-bold.text-white title]
    (when subtitle
      [:p.text-sm.text-gray-400.mt-1 subtitle])]
   (when actions
     [:div.flex.items-center.gap-3.shrink-0 actions])])

(defn section-header
  "The small uppercase label that separates sections within a page.
   `right` is an optional trailing element (a count, a link)."
  ([label] (section-header label nil))
  ([label right]
   [:div.flex.items-baseline.gap-3
    [:h2.text-xs.font-bold.tracking-widest.text-gray-400 label]
    [:div.h-px.flex-1.bg-dark-border]
    (when right [:div.text-xs.text-gray-500.shrink-0 right])]))

(defn card
  "Bordered surface panel. Accepts an optional leading attribute map."
  [& content]
  (let [[attrs content] (if (map? (first content))
                          [(first content) (rest content)]
                          [{} content])]
    (into [:div (update attrs :class
                        #(str "rounded-xl border border-dark bg-dark-surface "
                              "p-4 sm:p-6" (when % (str " " %))))]
          content)))

(defn empty-state
  "What a surface shows when it has nothing yet.

   Always offers a way forward: before this vocabulary existed, several empty
   states were bare text that left the user stuck (see the timer dashboard
   note in roadmap/inline-entity-creation.md)."
  [{:keys [message action]}]
  [:div {:class (str "rounded-xl border border-dashed border-dark "
                     "p-6 text-center space-y-3")}
   [:p.text-sm.text-gray-400 message]
   (when action [:div action])])
