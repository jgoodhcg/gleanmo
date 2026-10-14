(ns tech.jgood.gleanmo.ui
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [tech.jgood.gleanmo.settings :as settings]
            [com.biffweb :as biff]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :as ring-response]
            [rum.core :as rum]))

(defn css-path []
  (if-some [last-modified (some-> (io/resource "public/css/main.css")
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str "/css/main.css?t=" last-modified)
    "/css/main.css"))

(defn js-path []
  (if-some [last-modified (some-> (io/resource "public/js/main.js")
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str "/js/main.js?t=" last-modified)
    "/js/main.js"))

(defn base [{:keys [::recaptcha ::echarts] :as ctx} & body]
  (apply
   biff/base-html
   (-> ctx
       (merge #:base{:title       settings/app-name
                     :lang        "en-US"
                     :icon        "/img/glider.png"
                     :description (str settings/app-name " Description")
                     :image       "https://clojure.org/images/clojure-logo-120b.png"})
       (update :base/head (fn [head]
                            (cond->> head
                              :always
                              (concat [[:link {:rel "manifest" :href "/manifest.json"}]
                                       [:meta {:name "theme-color" :content "#0d1117"}]
                                       [:meta {:name "apple-mobile-web-app-capable" :content "yes"}]
                                       [:meta {:name "apple-mobile-web-app-status-bar-style" :content "black-translucent"}]
                                       [:link {:rel "apple-touch-icon" :href "/img/icon-192.png"}]
                                       ;; Third-party code is vendored (resources/public/vendor/README.md),
                                       ;; so every page runs what git holds rather than what a CDN serves.
                                       [:link {:rel "stylesheet" :href "/vendor/choices-11.2.4/choices.min.css"}]
                                       [:link {:rel "stylesheet" :href (css-path)}]
                                       [:link {:rel "stylesheet" :href "/fonts/space-mono/space-mono.css"}]
                                       [:script {:src (js-path)}]
                                       [:script {:src "/vendor/choices-11.2.4/choices.min.js" :defer true}]
                                       [:script {:src "/vendor/htmx-1.9.0/htmx.min.js"}]
                                       ;; Unused as of 2026-08-29 (zero call sites) and render-blocking on every
                                       ;; page. To bring either back, gate it on a ctx key the way ::echarts and
                                       ;; ::recaptcha are gated below. See roadmap/079-htmx-navigation-consistency.md.
                                       ;; Vendor it at htmx's own version: the unversioned ws.js URL tracks
                                       ;; htmx 2.x, which does not match 1.9.0.
                                       ;; [:script {:src "https://unpkg.com/htmx.org/dist/ext/ws.js"}]
                                       ;; [:script {:src "https://unpkg.com/hyperscript.org@0.9.8"}]
                                       [:script {:src "/vendor/sortable-1.15.0/Sortable.min.js"}]
                                       [:script {:defer true :data-domain "gleanmo.com" :src "https://plausible.io/js/script.js"}]])
                              (true? recaptcha)
                              (concat
                               [[:script {:src   "https://www.google.com/recaptcha/api.js"
                                          :async "async" :defer "defer"}]])
                              (true? echarts)
                              (concat
                               [[:script {:src "/vendor/echarts-6.0.0/echarts.min.js"}]])))))
   body))

(defn page [ctx & body]
  (base
   ctx
   [:.min-h-screen.w-full.font-sans
    (when (bound? #'csrf/*anti-forgery-token*)
      {:hx-headers (cheshire/generate-string
                    {:x-csrf-token csrf/*anti-forgery-token*})})
    body]))

(defn on-error [{:keys [status] :as ctx}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup
          (page
           ctx
           [:h1.text-lg.font-bold
            (if (= status 404)
              "Page not found."
              "Something went wrong.")]))})

(defn fragment
  "Render a partial HTML snippet, typically for HTMX responses. Mirrors `page`
  by accepting an unused `ctx` argument for future hooks, but can also be
  called with just the component."
  ([component]
   (fragment {} component))
  ([_ctx & body]
   (let [content (if (> (count body) 1)
                   (into [:<>] body)
                   (first body))]
     (rum/render-static-markup content))))
