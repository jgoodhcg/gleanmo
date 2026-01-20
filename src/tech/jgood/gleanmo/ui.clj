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
                              (concat [[:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/choices.js/public/assets/styles/choices.min.css"}]
                                       [:link {:rel "stylesheet" :href (css-path)}]
                                       [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
                                       [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
                                       [:link {:href "https://fonts.googleapis.com/css2?family=Space+Mono:ital,wght@0,400;0,700;1,400;1,700&display=swap" :rel "stylesheet"}]
                                       [:script {:src (js-path)}]
                                       [:script {:src "https://cdn.jsdelivr.net/npm/choices.js/public/assets/scripts/choices.min.js" :defer true}]
                                       [:script {:src "https://unpkg.com/htmx.org@1.9.0"}]
                                       [:script {:src "https://unpkg.com/htmx.org/dist/ext/ws.js"}]
                                       [:script {:src "https://unpkg.com/hyperscript.org@0.9.8"}]
                                       [:script {:defer true :data-domain "gleanmo.com" :src "https://plausible.io/js/script.js"}]])
                              (true? recaptcha)
                              (concat
                               [[:script {:src   "https://www.google.com/recaptcha/api.js"
                                          :async "async" :defer "defer"}]])
                              (true? echarts)
                              (concat
                               [[:script {:src "https://cdn.jsdelivr.net/npm/echarts@6.0.0/dist/echarts.min.js"}]])))))
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
