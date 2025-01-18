(ns tech.jgood.gleanmo.schema.ical-url-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def ical-url
  (-> [:map {:closed true}
       [:xt/id :ical-url/id]
       [::sm/type [:enum :ical-url]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:ical-url/user-id :user/id]
       [:ical-url/url :string]
       [:ical-url/refreshed-at {:optional true} :instant]
       [:ical-url/last-fetched {:optional true} :instant]
       ;; [:ical-url/fetch-interval {:optional true} :duration] ;; duration can be represented in ISO 8601 format
       [:ical-url/source {:optional true} :string]
       [:ical-url/active {:optional true} :boolean]
       [:ical-url/label {:optional true} :string]
       [:ical-url/notes {:optional true} :string]
       [:ical-url/visibility {:optional true} [:enum :public :private]]]
      (concat sm/legacy-meta)
      ;; DEPRECATED
      (concat [[:ical-url/name {:optional true} :string]])
      vec))
