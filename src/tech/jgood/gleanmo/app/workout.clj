(ns tech.jgood.gleanmo.app.workout
  "Custom workout flow: one screen to run an exercise session — start/stop
   timed blocks and enter sets — instead of bouncing between generic CRUD
   forms. A block is one timed chunk of work (a superset when it holds sets
   of different exercises)."
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.exercise-schema :as exercise-schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(def ^:private screen-url "/app/exercise/session")

(defn- format-elapsed
  [beginning]
  (let [total (t/seconds (t/between beginning (t/now)))
        h     (quot total 3600)
        m     (quot (mod total 3600) 60)]
    (if (pos? h) (str h "h " m "m") (str m "m"))))

(defn- open-session
  [{:keys [biff/db session] :as ctx}]
  (first (queries/active-timers-for-user
          db (:uid session) :exercise-session
          :exercise-session/beginning :exercise-session/end
          :user-settings (queries/resolve-user-settings ctx))))

(defn- session-blocks
  [ctx session-id]
  (->> (queries/all-for-user-query {:entity-type-str "exercise-block"
                                    :schema exercise-schema/exercise-block}
                                   ctx)
       (filter #(= session-id (:exercise-block/session-id %)))
       (sort-by :exercise-block/beginning)
       vec))

(defn- sets-by-block
  [ctx block-ids]
  (->> (queries/all-for-user-query {:entity-type-str "exercise-set"
                                    :schema exercise-schema/exercise-set}
                                   ctx)
       (filter #(contains? block-ids (:exercise-set/block-id %)))
       (sort-by :tech.jgood.gleanmo.schema.meta/created-at)
       (group-by :exercise-set/block-id)))

(defn- exercises-for-user
  [ctx]
  (->> (queries/all-for-user-query {:entity-type-str "exercise"
                                    :schema exercise-schema/exercise}
                                   ctx)
       (sort-by #(some-> (:exercise/label %) str/lower-case))))

(defn- parse-int* [s] (when-not (str/blank? s) (parse-long s)))
(defn- parse-num* [s] (when-not (str/blank? s) (parse-double s)))

(defn- set-summary
  [ex-set exercises-by-id]
  (let [ex-label (get-in exercises-by-id [(:exercise-set/exercise-id ex-set) :exercise/label] "Unknown")
        reps     (:exercise-set/reps ex-set)
        weight   (:exercise-set/weight ex-set)
        unit     (some-> (:exercise-set/weight-unit ex-set) name)
        distance (:exercise-set/distance ex-set)
        d-unit   (some-> (:exercise-set/distance-unit ex-set) name)]
    (str ex-label
         (when reps (str " × " reps))
         (when weight (str " @ " weight (when unit (str " " unit))))
         (when distance (str " — " distance (when d-unit (str " " d-unit)))))))

(defn- set-form
  "Sticky set entry form for a block: last-entered values arrive as query
   params from add-set!'s redirect so supersets/repeat sets need minimal
   input."
  [block-id exercises params]
  (let [p (fn [k] (let [v (get params k)] (when-not (str/blank? (str v)) (str v))))]
    (biff/form
     {:action (str "/app/exercise/block/" block-id "/set"), :method "post",
      :class "flex flex-wrap items-end gap-2"}
     [:div
      [:label.form-label {:for "exercise-id"} "Exercise"]
      [:select.form-select {:name "exercise-id" :required true}
       (for [ex exercises]
         [:option {:value (:xt/id ex)
                   :selected (= (str (:xt/id ex)) (p :exercise-id))}
          (:exercise/label ex)])]]
     [:div {:class "w-20"}
      [:label.form-label {:for "reps"} "Reps"]
      [:input.form-input {:type "number" :step "1" :name "reps"
                          :value (p :reps)}]]
     [:div {:class "w-24"}
      [:label.form-label {:for "weight"} "Weight"]
      [:input.form-input {:type "number" :step "any" :name "weight"
                          :value (p :weight)}]]
     [:div
      [:label.form-label {:for "weight-unit"} "Unit"]
      [:select.form-select {:name "weight-unit"}
       (for [u ["lbs" "kg"]]
         [:option {:value u :selected (= u (or (p :weight-unit) "lbs"))} u])]]
     [:button.form-button-primary {:type "submit"} "Add set"])))

(defn- block-card
  [{:keys [xt/id] :as block} block-sets exercises-by-id running?]
  [:div.bg-dark-surface.rounded-lg.p-4.border
   {:class (if running? "border-neon-cyan" "border-dark")}
   [:div.flex.items-center.justify-between
    [:div
     [:span.text-sm.text-gray-400.uppercase.tracking-wide
      (if running? "Current block" "Block")]
     [:span.text-sm.text-neon-cyan.ml-3
      (if running?
        (str "Running " (format-elapsed (:exercise-block/beginning block)))
        (when-let [end (:exercise-block/end block)]
          (let [secs (t/seconds (t/between (:exercise-block/beginning block) end))]
            (str (quot secs 60) "m " (mod secs 60) "s"))))]]
    [:div.flex.items-center.gap-3
     (when running?
       (biff/form {:action (str "/app/exercise/block/" id "/stop"), :method "post"}
                  [:button.bg-red-500.bg-opacity-20.text-red-400.px-3.py-1.rounded.text-sm
                   {:type "submit"} "Stop block"]))
     [:a.link.text-sm {:href (str "/app/crud/form/exercise-block/edit/" id
                                  "?redirect=" (java.net.URLEncoder/encode screen-url "UTF-8"))}
      "edit"]]]
   (when (seq block-sets)
     [:ul.mt-2.space-y-1
      (for [ex-set block-sets]
        [:li.text-sm.text-gray-300 (set-summary ex-set exercises-by-id)])])])

(defn- active-session-view
  [ctx session params]
  (let [session-id (:xt/id session)
        blocks     (session-blocks ctx session-id)
        running    (first (filter #(nil? (:exercise-block/end %)) blocks))
        sets       (sets-by-block ctx (set (map :xt/id blocks)))
        exercises  (exercises-for-user ctx)
        ex-by-id   (into {} (map (juxt :xt/id identity)) exercises)
        ;; sets attach to the running block, else the most recent stopped one
        set-target (or running (last blocks))]
    [:div.container.mx-auto.p-6.space-y-6
     [:div.flex.items-center.justify-between
      [:div
       [:h1.text-3xl.font-bold.text-white "🏋️ Workout"]
       [:p.text-sm.text-neon-cyan
        (str "Session running " (format-elapsed (:exercise-session/beginning session)))]]
      (biff/form {:action (str "/app/exercise/session/" session-id "/end"), :method "post"}
                 [:button.bg-red-500.bg-opacity-20.text-red-400.px-3.py-2.rounded.text-sm.font-medium
                  {:type "submit"} "End session"])]

     (when-not running
       (biff/form {:action (str "/app/exercise/session/" session-id "/block/start"), :method "post"}
                  [:button.form-button-primary {:type "submit"} "Start block"]))

     (when set-target
       [:div.bg-dark-surface.rounded-lg.p-4.border.border-dark
        [:h2.text-lg.font-semibold.text-white.mb-3 "Add set"]
        (if (seq exercises)
          (set-form (:xt/id set-target) exercises params)
          [:p.text-sm.text-gray-400
           "No exercises yet. "
           [:a.link {:href (str "/app/crud/form/exercise/new?redirect="
                                (java.net.URLEncoder/encode screen-url "UTF-8"))}
            "Create one"]
           " first."])])

     (when (seq blocks)
       [:div.space-y-3
        [:h2.text-lg.font-semibold.text-white "Blocks"]
        (for [block (reverse blocks)]
          ^{:key (:xt/id block)}
          (block-card block (get sets (:xt/id block)) ex-by-id
                      (= (:xt/id block) (:xt/id running))))])

     [:div.text-sm
      [:a.link {:href (str "/app/crud/form/exercise-session/edit/" session-id
                           "?redirect=" (java.net.URLEncoder/encode screen-url "UTF-8"))}
       "edit session"]]]))

(defn- idle-view
  [ctx]
  (let [recent (->> (queries/all-for-user-query
                     {:entity-type-str "exercise-session"
                      :schema exercise-schema/exercise-session}
                     ctx)
                    (take 5))]
    [:div.container.mx-auto.p-6.space-y-6
     [:h1.text-3xl.font-bold.text-white "🏋️ Workout"]
     (biff/form {:action "/app/exercise/session/start", :method "post"}
                [:button.form-button-primary {:type "submit"} "Start session"])
     (when (seq recent)
       [:div.space-y-2
        [:h2.text-lg.font-semibold.text-white "Recent sessions"]
        (for [s recent]
          ^{:key (:xt/id s)}
          [:a.block.no-underline
           {:href (str "/app/crud/form/exercise-session/edit/" (:xt/id s)
                       "?redirect=" (java.net.URLEncoder/encode screen-url "UTF-8"))}
           [:div.bg-dark-surface.rounded.p-3.border.border-dark.hover:border-neon-cyan.text-sm.text-gray-300
            (or (:exercise-session/label s)
                (str (:exercise-session/beginning s)))]])])]))

(defn workout-page
  [{:keys [params] :as ctx}]
  (let [session (open-session ctx)]
    (ui/page
     ctx
     (side-bar
      ctx
      (if session
        (active-session-view ctx session params)
        (idle-view ctx))))))

(defn- redirect-home
  []
  {:status 303 :headers {"location" screen-url}})

(defn- owned-entity
  [{:keys [biff/db session path-params]} entity-key]
  (let [entity-id (java.util.UUID/fromString (:id path-params))]
    (queries/get-entity-for-user db entity-id (:uid session) entity-key)))

(defn start-session!
  [{:keys [session] :as ctx}]
  (when-not (open-session ctx)
    (mutations/create-entity! ctx {:entity-key :exercise-session
                                   :data {:user/id (:uid session)
                                          :exercise-session/beginning (t/now)}}))
  (redirect-home))

(defn end-session!
  [ctx]
  (when-let [sess (owned-entity ctx :exercise-session)]
    ;; close any running block along with the session
    (doseq [block (session-blocks ctx (:xt/id sess))
            :when (nil? (:exercise-block/end block))]
      (mutations/update-entity! ctx {:entity-key :exercise-block
                                     :entity-id (:xt/id block)
                                     :data {:exercise-block/end (t/now)}}))
    (when (nil? (:exercise-session/end sess))
      (mutations/update-entity! ctx {:entity-key :exercise-session
                                     :entity-id (:xt/id sess)
                                     :data {:exercise-session/end (t/now)}})))
  (redirect-home))

(defn start-block!
  [{:keys [session] :as ctx}]
  (when-let [sess (owned-entity ctx :exercise-session)]
    (mutations/create-entity! ctx {:entity-key :exercise-block
                                   :data {:user/id (:uid session)
                                          :exercise-block/session-id (:xt/id sess)
                                          :exercise-block/beginning (t/now)}}))
  (redirect-home))

(defn stop-block!
  [ctx]
  (when-let [block (owned-entity ctx :exercise-block)]
    (when (nil? (:exercise-block/end block))
      (mutations/update-entity! ctx {:entity-key :exercise-block
                                     :entity-id (:xt/id block)
                                     :data {:exercise-block/end (t/now)}})))
  (redirect-home))

(defn add-set!
  [{:keys [session params] :as ctx}]
  (if-let [block (owned-entity ctx :exercise-block)]
    (let [exercise-id (some-> (:exercise-id params) java.util.UUID/fromString)
          reps        (parse-int* (:reps params))
          weight      (parse-num* (:weight params))
          unit        (when weight (keyword (or (:weight-unit params) "lbs")))]
      (when exercise-id
        (mutations/create-entity!
         ctx
         {:entity-key :exercise-set
          :data (cond-> {:user/id (:uid session)
                         :exercise-set/block-id (:xt/id block)
                         :exercise-set/exercise-id exercise-id}
                  reps   (assoc :exercise-set/reps reps)
                  weight (assoc :exercise-set/weight weight)
                  unit   (assoc :exercise-set/weight-unit unit))}))
      ;; sticky form: carry the entered values back as query params
      {:status 303
       :headers {"location"
                 (str screen-url
                      "?exercise-id=" exercise-id
                      (when reps (str "&reps=" reps))
                      (when weight (str "&weight=" weight))
                      (when unit (str "&weight-unit=" (name unit))))}})
    (redirect-home)))

(def routes
  ["/exercise" {}
   ["/session" {:get workout-page}]
   ["/session/start" {:post start-session!}]
   ["/session/:id/end" {:post end-session!}]
   ["/session/:id/block/start" {:post start-block!}]
   ["/block/:id/stop" {:post stop-block!}]
   ["/block/:id/set" {:post add-set!}]])
