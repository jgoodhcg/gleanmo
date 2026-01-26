(ns tasks.util
  "Shared utilities for CLI tasks.")

;; =============================================================================
;; Terminal Colors
;; =============================================================================

(def ^:private codes
  {:green  "\u001b[32m"
   :red    "\u001b[31m"
   :yellow "\u001b[33m"
   :cyan   "\u001b[36m"
   :bold   "\u001b[1m"
   :reset  "\u001b[0m"})

(defn colorize
  "Wrap text in ANSI color codes."
  [color text]
  (str (get codes color "") text (:reset codes)))

(defn print-green [msg] (println (colorize :green msg)))
(defn print-red [msg] (println (colorize :red msg)))
(defn print-yellow [msg] (println (colorize :yellow msg)))
(defn print-cyan [msg] (println (colorize :cyan msg)))
