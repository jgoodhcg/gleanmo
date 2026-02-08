(ns tasks.migrations.m001-airtable-import-medications
  "Import medication catalog entities from Airtable EDN export."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.biffweb :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.medication :as med]
   [tasks.util :as u]))

(defn- find-user-by-email
  [db email]
  (first (q db
            '{:find  (pull ?e [:xt/id :user/email])
              :where [[?e :user/email email]]
              :in    [email]}
            email)))

(defn- file-error
  [message]
  (u/print-red (str "  ERROR: " message))
  false)

(defn- valid-file?
  [file-path]
  (cond
    (str/blank? file-path)
    (file-error "--file is required")

    (not (.exists (io/file file-path)))
    (file-error (str "--file not found: " file-path))

    (not (.isFile (io/file file-path)))
    (file-error (str "--file is not a regular file: " file-path))

    (not (.canRead (io/file file-path)))
    (file-error (str "--file is not readable: " file-path))

    :else
    true))

(defn run
  "Run the medication import migration."
  [{:keys [file email db]}]
  (u/print-cyan "Running m001-airtable-import-medications")
  (println "  File:" file)
  (println "  Email:" email)
  (let [user (cond
               (str/blank? email)
               (do
                 (u/print-red "  ERROR: --email is required for user lookup")
                 nil)

               :else
               (if-let [found-user (find-user-by-email db email)]
                 (do
                   (u/print-green (str "  Found user: " (:xt/id found-user)))
                   (println "  Email:" (:user/email found-user))
                   found-user)
                 (do
                   (u/print-yellow (str "  No user found for email: " email))
                   nil)))]
    (when (and user (valid-file? file))
      (let [records     (core/read-airtable-file file)
            medications (med/convert-airtable-medications file (:xt/id user))
            validation  (med/validate-medications medications)]
        (u/print-green (str "  Airtable records loaded: " (count records)))
        (println "  Medication entities converted:" (count medications))
        (println "  Medication validation summary:")
        (println "    Total:" (:total validation))
        (println "    Passed:" (:passed validation))
        (println "    Failed:" (count (:failed validation))))))
  (println "  (migration body not yet implemented)"))
