(ns tech.jgood.gleanmo.e2e-auth
  "Dev-only auth bypass for E2E testing. This namespace is only loaded in dev mode
   and should never be included in production builds."
  (:require [com.biffweb :as biff]
            [tech.jgood.gleanmo.db.mutations :as mutations]
            [tech.jgood.gleanmo.db.queries :as queries]
            [tech.jgood.gleanmo.schema :as schema-registry]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [tick.core :as t]))

(def demo-email "blog-demo@localhost")

(def demo-zone (java.time.ZoneId/of "America/Detroit"))

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
                        :user/show-bm-logs true}])
      user-id)))

(defn- seed-ctx
  [ctx user-id]
  (assoc ctx
         :session {:uid user-id}
         :user/settings {:email "blog-demo@localhost"
                         :show-sensitive false
                         :show-archived false
                         :show-bm-logs true}))

(defn- entities-for
  [ctx user-id entity-type]
  (queries/all-for-user-query
   {:entity-type-str (name entity-type)
    :schema          (get schema-registry/schema entity-type)
    :filter-references false}
   (seed-ctx ctx user-id)))

(defn- demo-seeded?
  [ctx user-id]
  (some #(= "Gleanmo screenshot refresh" (:project/label %))
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
      :user/show-bm-logs true})
    (let [home-id       (create! ctx user-id :location
                                 {:location/label "Home studio"
                                  :location/notes "Desk, notebook, tea"})
          couch-id      (create! ctx user-id :location
                                 {:location/label "Reading chair"})
          project-id    (create! ctx user-id :project
                                 {:project/label "Gleanmo screenshot refresh"
                                  :project/notes "Polishing the quantified-self home timeline"})
          blog-id       (create! ctx user-id :project
                                 {:project/label "Projects homepage"
                                  :project/notes "Writing the current-projects section"})
          meditation-id (create! ctx user-id :meditation
                                 {:meditation/label "Open monitoring"
                                  :meditation/notes "Quiet attention practice"})
          book-id       (create! ctx user-id :book
                                 {:book/label "The Creative Act"
                                  :book/title "The Creative Act"
                                  :book/author "Rick Rubin"
                                  :book/formats #{:hardcover}})
          habit-a-id    (create! ctx user-id :habit
                                 {:habit/label "Morning pages"
                                  :habit/notes "Three pages before email"})
          habit-b-id    (create! ctx user-id :habit
                                 {:habit/label "Walk outside"})
          med-id        (create! ctx user-id :medication
                                 {:medication/label "Vitamin D"
                                  :medication/notes "Morning stack"})]
      (create! ctx user-id :project-log
               {:project-log/project-id project-id
                :project-log/beginning  (ago 84 :minutes)
                :project-log/time-zone  "America/Detroit"
                :project-log/location-id home-id
                :project-log/notes      "Tuning timeline density and screenshot composition"})
      (create! ctx user-id :reading-log
               {:reading-log/book-id book-id
                :reading-log/beginning (ago 31 :minutes)
                :reading-log/time-zone "America/Detroit"
                :reading-log/location-id couch-id
                :reading-log/format :hardcover
                :reading-log/notes "Active reading timer for a calmer top strip"})
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
                :project-log/notes "Drafted the thumbnail copy and timeline notes"})
      (create! ctx user-id :reading-log
               {:reading-log/book-id book-id
                :reading-log/beginning (ago 26 :hours)
                :reading-log/end (ago 25 :hours)
                :reading-log/time-zone "America/Detroit"
                :reading-log/location-id couch-id
                :reading-log/format :hardcover
                :reading-log/notes "Marked a chapter that connects projects and practice"})
      (create! ctx user-id :habit-log
               {:habit-log/timestamp (ago 3 :hours)
                :habit-log/time-zone "America/Detroit"
                :habit-log/habit-ids #{habit-a-id habit-b-id}
                :habit-log/notes "Wrote, walked, then came back to polish the app"})
      (create! ctx user-id :medication-log
               {:medication-log/timestamp (ago 5 :hours)
                :medication-log/medication-id med-id
                :medication-log/dosage (float 1.0)
                :medication-log/unit :capsule})
      (create! ctx user-id :task
               {:task/label "Choose final blog screenshot"
                :task/notes "Use the seeded account after the running timers render"
                :task/state :now
                :task/due-on (t/date (t/in (now) demo-zone))
                :task/focus-date (t/date (t/in (now) demo-zone))
                :task/effort :low
                :task/mode :solo
                :task/domain :personal
                :task/project-id blog-id})
      (create! ctx user-id :calendar-event
               {:calendar-event/label "Coffee and project notes"
                :calendar-event/source :gleanmo
                :calendar-event/summary "Talk through what belongs on the projects homepage"
                :calendar-event/beginning (later 2 :hours)
                :calendar-event/end (later 3 :hours)
                :calendar-event/time-zone "America/Detroit"
                :calendar-event/color-neon :cyan})
      (create! ctx user-id :calendar-event
               {:calendar-event/label "Ship screenshot update"
                :calendar-event/source :gleanmo
                :calendar-event/summary "Export the final image for the blog"
                :calendar-event/beginning (later 25 :hours)
                :calendar-event/end (later 26 :hours)
                :calendar-event/time-zone "America/Detroit"
                :calendar-event/color-neon :green})
      (create! ctx user-id :symptom-log
               {:symptom-log/timestamp (ago 29 :hours)
                :symptom-log/type :fatigue
                :symptom-log/severity :mild
                :symptom-log/severity-score 2
                :symptom-log/location :generalized
                :symptom-log/notes "Low energy after a late night"})
      (create! ctx user-id :bm-log
               {:bm-log/timestamp (ago 28 :hours)
                :bm-log/bristol :b4-smooth-log
                :bm-log/pace :typical
                :bm-log/color :brown
                :bm-log/blood :none
                :bm-log/mucus false
                :bm-log/urgency :none
                :bm-log/incontinence false
                :bm-log/straining false
                :bm-log/odor :normal
                :bm-log/size :medium
                :bm-log/notes "Normal baseline"
                :bm-log/anxiety :none
                :bm-log/feeling-of-completeness :complete
                :bm-log/ease-of-passage :easy}))))

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

(def module
  {:routes [["/auth/e2e-login" {:get e2e-login-handler}]
            ["/auth/e2e-seed-timeline" {:get e2e-seed-timeline-handler}]]})
