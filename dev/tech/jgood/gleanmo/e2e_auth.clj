(ns tech.jgood.gleanmo.e2e-auth
  "Dev-only auth bypass for E2E testing. This namespace is only loaded in dev mode
   and should never be included in production builds."
  (:require [com.biffweb :as biff]
            [tech.jgood.gleanmo.db.mutations :as mutations]
            [tech.jgood.gleanmo.db.queries :as queries]
            [tech.jgood.gleanmo.schema :as schema-registry]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [tick.core :as t]))

(def demo-email "marketing-demo@localhost")

(def timeline-email "e2e-series@localhost")

(def demo-zone (java.time.ZoneId/of "America/Detroit"))

(def timeline-history-days 14)

(defn- now
  []
  (t/now))

(defn- ago
  [amount unit]
  (t/<< (now) (t/new-duration amount unit)))

(defn- later
  [amount unit]
  (t/>> (now) (t/new-duration amount unit)))

(defn- ensure-user!
  [{:keys [biff/db] :as ctx} email]
  (if-let [user (biff/lookup db :user/email email)]
    (:xt/id user)
    (let [user-id (random-uuid)
          joined  (now)]
      (biff/submit-tx ctx
                      [{:db/doc-type :user
                        :xt/id user-id
                        ::sm/type :user
                        ::sm/created-at joined
                        :user/email email
                        :user/joined-at joined
                        :user/time-zone "America/Detroit"
                        :user/show-sensitive false
                        :user/show-archived false
                        :user/show-bm-logs false}])
      user-id)))

(defn- seed-ctx
  [ctx user-id]
  (assoc ctx
         :session {:uid user-id}
         :user/settings {:email "marketing-demo@localhost"
                         :show-sensitive false
                         :show-archived false
                         :show-bm-logs false}))

(defn- entities-for
  [ctx user-id entity-type]
  (queries/all-for-user-query
   {:entity-type-str (name entity-type)
    :schema          (get schema-registry/schema entity-type)
    :filter-references false}
   (seed-ctx ctx user-id)))

(defn- demo-seeded?
  [ctx user-id]
  (some #(= "Product launch plan" (:project/label %))
        (entities-for ctx user-id :project)))

(defn- create!
  [ctx user-id entity-key data]
  (mutations/create-entity!
   (seed-ctx ctx user-id)
   {:entity-key entity-key
    :data       (merge {:user/id user-id} data)}))

(defn- seed-demo-data!
  [ctx user-id]
  (when-not (demo-seeded? ctx user-id)
    (mutations/update-user!
     ctx
     user-id
     {:user/time-zone "America/Detroit"
      :user/show-sensitive false
      :user/show-archived false
      :user/show-bm-logs false})
    (let [home-id       (create! ctx user-id :location
                                 {:location/label "Studio desk"
                                  :location/notes "Notebook, laptop, tea"})
          couch-id      (create! ctx user-id :location
                                 {:location/label "Reading nook"})
          project-id    (create! ctx user-id :project
                                 {:project/label "Product launch plan"
                                  :project/notes "Preparing a focused project update"})
          blog-id       (create! ctx user-id :project
                                 {:project/label "Portfolio refresh"
                                  :project/notes "Updating the current-projects section"})
          meditation-id (create! ctx user-id :meditation
                                 {:meditation/label "Breath focus"
                                  :meditation/notes "Short reset between work blocks"})
          book-id       (create! ctx user-id :book
                                 {:book/label "Designing Calm Tools"
                                  :book/title "Designing Calm Tools"
                                  :book/author "Example Press"
                                  :book/formats #{:hardcover}})
          habit-a-id    (create! ctx user-id :habit
                                 {:habit/label "Morning notes"
                                  :habit/notes "Review priorities before email"})
          habit-b-id    (create! ctx user-id :habit
                                 {:habit/label "Daily walk"})]
      (create! ctx user-id :project-log
               {:project-log/project-id project-id
                :project-log/beginning  (ago 84 :minutes)
                :project-log/time-zone  "America/Detroit"
                :project-log/location-id home-id
                :project-log/notes      "Refined launch checklist and next actions"})
      (create! ctx user-id :reading-log
               {:reading-log/book-id book-id
                :reading-log/beginning (ago 31 :minutes)
                :reading-log/time-zone "America/Detroit"
                :reading-log/location-id couch-id
                :reading-log/format :hardcover
                :reading-log/notes "Active reading timer for a quiet top strip"})
      (create! ctx user-id :meditation-log
               {:meditation-log/type-id meditation-id
                :meditation-log/location-id home-id
                :meditation-log/beginning (ago 7 :hours)
                :meditation-log/end (ago 6 :hours)
                :meditation-log/position :sitting
                :meditation-log/guided false
                :meditation-log/interrupted false
                :meditation-log/time-zone "America/Detroit"
                :meditation-log/sequence-completed true})
      (create! ctx user-id :project-log
               {:project-log/project-id blog-id
                :project-log/beginning (ago 4 :hours)
                :project-log/end (ago 2 :hours)
                :project-log/time-zone "America/Detroit"
                :project-log/location-id home-id
                :project-log/notes "Drafted project summaries and selected visuals"})
      (create! ctx user-id :reading-log
               {:reading-log/book-id book-id
                :reading-log/beginning (ago 26 :hours)
                :reading-log/end (ago 25 :hours)
                :reading-log/time-zone "America/Detroit"
                :reading-log/location-id couch-id
                :reading-log/format :hardcover
                :reading-log/notes "Marked a chapter about durable product habits"})
      (create! ctx user-id :habit-log
               {:habit-log/timestamp (ago 3 :hours)
                :habit-log/time-zone "America/Detroit"
                :habit-log/habit-ids #{habit-a-id habit-b-id}
                :habit-log/notes "Notes, walk, then a clean planning block"})
      (create! ctx user-id :task
               {:task/label "Choose launch image"
                :task/notes "Use the demo account after active timers render"
                :task/state :now
                :task/due-on (t/date (t/in (now) demo-zone))
                :task/focus-date (t/date (t/in (now) demo-zone))
                :task/effort-score 2
                :task/mode :solo
                :task/domain :work
                :task/project-id blog-id})
      (create! ctx user-id :calendar-event
               {:calendar-event/label "Planning review"
                :calendar-event/source :gleanmo
                :calendar-event/summary "Review project priorities and launch notes"
                :calendar-event/beginning (later 2 :hours)
                :calendar-event/end (later 3 :hours)
                :calendar-event/time-zone "America/Detroit"
                :calendar-event/color-neon :cyan})
      (create! ctx user-id :calendar-event
               {:calendar-event/label "Publish project update"
                :calendar-event/source :gleanmo
                :calendar-event/summary "Export the final image and publish the update"
                :calendar-event/beginning (later 25 :hours)
                :calendar-event/end (later 26 :hours)
                :calendar-event/time-zone "America/Detroit"
                :calendar-event/color-neon :green}))))

(def timeline-parent-definitions
  [[:location :location/label
    {:location/label "Timeline studio"
     :location/notes "Stable fixture for visual-timeline captures"}]
   [:project :project/label
    {:project/label "Timeline project"
     :project/notes "A project whose activity grows one day at a time"}]
   [:meditation :meditation/label
    {:meditation/label "Timeline breath practice"}]
   [:book :book/label
    {:book/label "The Long Now"
     :book/title "The Long Now"
     :book/author "Gleanmo Fixture"
     :book/formats #{:hardcover}}]
   [:habit :habit/label
    {:habit/label "Morning reflection"}]
   [:habit :habit/label
    {:habit/label "Evening walk"}]
   [:medication :medication/label
    {:medication/label "Vitamin D"}]
   [:exercise :exercise/label
    {:exercise/label "Goblet squat"
     :exercise/source "Timeline fixture"}]
   [:boulder-problem :boulder-problem/label
    {:boulder-problem/label "Blue corner"
     :boulder-problem/gym "Timeline Climbing"
     :boulder-problem/difficulty "blue"
     :boulder-problem/hold-color "blue"
     :boulder-problem/wall "corner"
     :boulder-problem/grade :v3}]])

(defn- timeline-ctx
  [ctx user-id]
  (assoc ctx
         :session {:uid user-id}
         :user/settings {:email timeline-email
                         :show-sensitive false
                         :show-archived false
                         :show-bm-logs true}))

(defn- create-many!
  [ctx user-id entity-specs]
  (mutations/create-entities!
   (timeline-ctx ctx user-id)
   (mapv (fn [spec]
           (update spec :data #(merge {:user/id user-id} %)))
         entity-specs)))

(defn- at-local-time
  [date hour minute]
  (-> date
      (.atTime hour minute)
      (.atZone demo-zone)
      .toInstant))

(defn- parent-ids-from
  [ids]
  (zipmap [:location :project :meditation :book :habit-a :habit-b
           :medication :exercise :boulder-problem]
          ids))

(defn- existing-timeline-parent-ids
  [ctx user-id]
  (let [ids (mapv (fn [[entity-type attr data]]
                    (some-> (queries/get-entity-by-attribute-for-user
                             (:biff/db ctx)
                             user-id
                             entity-type
                             attr
                             (get data attr))
                            :xt/id))
                  timeline-parent-definitions)]
    (when (every? some? ids)
      (parent-ids-from ids))))

(defn- timeline-day-label
  [date]
  (str "Timeline pulse " date))

(defn- timeline-day-specs
  [date {:keys [location project meditation book habit-a habit-b medication
                exercise boulder-problem]}]
  (let [day-number (.toEpochDay date)
        choice     #(nth % (mod day-number (count %)))
        morning    (at-local-time date 8 0)
        midday     (at-local-time date 12 30)
        evening    (at-local-time date 18 0)]
    [{:entity-key :habit-log
      :data {:habit-log/timestamp morning
             :habit-log/time-zone "America/Detroit"
             :habit-log/habit-ids (if (even? day-number)
                                    #{habit-a habit-b}
                                    #{habit-a})
             :habit-log/notes "A steady daily signal for the visual timeline"}}
     {:entity-key :meditation-log
      :data {:meditation-log/type-id meditation
             :meditation-log/location-id location
             :meditation-log/beginning (at-local-time date 7 15)
             :meditation-log/end (at-local-time date 7 30)
             :meditation-log/position :sitting
             :meditation-log/guided false
             :meditation-log/interrupted false
             :meditation-log/time-zone "America/Detroit"
             :meditation-log/sequence-completed true}}
     {:entity-key :bm-log
      :data {:bm-log/timestamp (at-local-time date 8 30)
             :bm-log/bristol (choice [:b3-cracked-log :b4-smooth-log :b5-soft-blobs])
             :bm-log/pace :typical
             :bm-log/color :brown
             :bm-log/blood :none
             :bm-log/mucus false
             :bm-log/urgency :mild
             :bm-log/incontinence false
             :bm-log/straining false
             :bm-log/odor :normal
             :bm-log/size :medium
             :bm-log/anxiety :none
             :bm-log/feeling-of-completeness :complete
             :bm-log/ease-of-passage :normal}}
     {:entity-key :medication-log
      :data {:medication-log/timestamp (at-local-time date 9 0)
             :medication-log/medication-id medication
             :medication-log/dosage (float 1)
             :medication-log/unit :tablet}}
     {:entity-key :reading-log
      :data {:reading-log/book-id book
             :reading-log/beginning (at-local-time date 20 0)
             :reading-log/end (at-local-time date 20 45)
             :reading-log/time-zone "America/Detroit"
             :reading-log/location-id location
             :reading-log/format :hardcover
             :reading-log/notes "Read another section of the timeline fixture"}}
     {:entity-key :project-log
      :data {:project-log/project-id project
             :project-log/beginning (at-local-time date 10 0)
             :project-log/end (at-local-time date 11 20)
             :project-log/time-zone "America/Detroit"
             :project-log/location-id location
             :project-log/notes "Moved the timeline project forward"}}
     {:entity-key :symptom-log
      :data {:symptom-log/timestamp midday
             :symptom-log/type :fatigue
             :symptom-log/severity-score (choice [2 3 3 5])
             :symptom-log/location :generalized
             :symptom-log/notes "Synthetic fixture data"}}
     {:entity-key :mood-log
      :data {:mood-log/timestamp evening
             :mood-log/valence (choice [0 1 2])
             :mood-log/arousal (choice [-1 0 1])
             :mood-log/stress (choice [0 1 2])
             :mood-log/tags #{:work}
             :mood-log/notes "Daily visual-timeline pulse"}}
     {:entity-key :exercise-session
      :data {:exercise-session/label (str "Strength " date)
             :exercise-session/beginning (at-local-time date 16 0)
             :exercise-session/end (at-local-time date 16 40)
             :exercise-session/notes (str "Goblet squat reference: " exercise)}}
     {:entity-key :boulder-session
      :data {:boulder-session/label (str "Climbing " date)
             :boulder-session/beginning (at-local-time date 17 0)
             :boulder-session/end (at-local-time date 18 15)
             :boulder-session/gym "Timeline Climbing"
             :boulder-session/perceived-exertion (choice [:moderate :hard :moderate])
             :boulder-session/notes (str "Problem reference: " boulder-problem)}}
     {:entity-key :calendar-event
      :data {:calendar-event/label (timeline-day-label date)
             :calendar-event/source :gleanmo
             :calendar-event/summary "Daily fixture marker and planning review"
             :calendar-event/beginning (at-local-time date 14 0)
             :calendar-event/end (at-local-time date 14 30)
             :calendar-event/time-zone "America/Detroit"
             :calendar-event/color-neon (choice [:cyan :green :violet])}}]))

(defn- timeline-day-seeded?
  [ctx user-id date]
  (some? (queries/get-entity-by-attribute-for-user
          (:biff/db ctx)
          user-id
          :calendar-event
          :calendar-event/label
          (timeline-day-label date))))

(defn- seed-timeline-data!
  [ctx user-id]
  (mutations/update-user!
   ctx
   user-id
   {:user/time-zone "America/Detroit"
    :user/show-sensitive false
    :user/show-archived false
    :user/show-bm-logs true})
  (let [today               (java.time.LocalDate/now demo-zone)
        existing-parent-ids (existing-timeline-parent-ids ctx user-id)
        parent-ids          (or existing-parent-ids
                                (->> timeline-parent-definitions
                                     (mapv (fn [[entity-key _ data]]
                                             {:entity-key entity-key
                                              :data data}))
                                     (create-many! ctx user-id)
                                     parent-ids-from))]
    (if (timeline-day-seeded? ctx user-id today)
      parent-ids
      (let [dates (if existing-parent-ids
                    [today]
                    (mapv #(.minusDays today %)
                          (range (dec timeline-history-days) -1 -1)))]
        (create-many! ctx user-id
                      (mapcat #(timeline-day-specs % parent-ids) dates))
        parent-ids))))

(defn e2e-login-handler
  "Dev-only endpoint that bypasses email verification.
   GET /auth/e2e-login?email=test@localhost -> sets session and redirects to /app"
  [{:keys [biff/db session params] :as ctx}]
  (let [email (get params :email "e2e-test@localhost")
        user (biff/lookup db :user/email email)]
    (if user
      ;; User exists, set session
      {:status 303
       :headers {"Location" "/app"}
       :session (assoc session :uid (:xt/id user))}
      ;; Create user first, then set session
      (let [user-id (random-uuid)
            now (t/now)]
        (biff/submit-tx ctx
                        [{:db/doc-type :user
                          :xt/id user-id
                          ::sm/type :user
                          ::sm/created-at now
                          :user/email email
                          :user/joined-at now}])
        {:status 303
         :headers {"Location" "/app"}
         :session (assoc session :uid user-id)}))))

(defn e2e-seed-timeline-handler
  "Dev-only endpoint that creates a screenshot-friendly dataset and signs in as it."
  [{:keys [session params] :as ctx}]
  (let [email   (get params :email demo-email)
        user-id (ensure-user! ctx email)]
    (seed-demo-data! ctx user-id)
    {:status 303
     :headers {"Location" "/app"}
     :session (assoc session :uid user-id)}))

(defn e2e-seed-series-handler
  "Dev-only endpoint that advances the rolling screenshot-series fixture and signs in."
  [{:keys [session params] :as ctx}]
  (let [email   (get params :email timeline-email)
        user-id (ensure-user! ctx email)]
    (seed-timeline-data! ctx user-id)
    {:status 303
     :headers {"Location" "/app"}
     :session (assoc session :uid user-id)}))

(def module
  {:routes [["/auth/e2e-login" {:get e2e-login-handler}]
            ["/auth/e2e-seed-timeline" {:get e2e-seed-timeline-handler}]
            ["/auth/e2e-seed-series" {:get e2e-seed-series-handler}]]})
