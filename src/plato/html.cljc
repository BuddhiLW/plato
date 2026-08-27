(ns plato.html
  "Deck data -> a standalone Reveal.js HTML document.

   Pure: builds hiccup and strings, performs no I/O. Slide <section> attributes
   come from `plato.deck/section-attrs`, slide bodies from `plato.content/render`,
   serialization from `plato.hiccup/->html`."
  (:require [clojure.string :as str]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.hiccup :as hiccup]
            [plato.json :as json]))

;; ── JSON ────────────────────────────────────────────────────────────────────

(def camel-key
  "kebab-case config key -> the camelCase name Reveal expects. One definition,
   in plato.json; this is the name the exporter reads it under."
  json/camel-key)

(defn ->json
  "Clojure value -> JSON string. Map keys are camelCased; keywords and symbols
   render as strings."
  [value]
  (json/write value {:key-fn json/camel-key}))

;; ── slides ──────────────────────────────────────────────────────────────────

(defn slide-hiccup
  "Slide map -> [:section attrs ...]. String content becomes a Reveal markdown
   section; :notes becomes a trailing [:aside.notes ...].

   Markdown rides in a <textarea data-template>, Reveal's RCDATA container: the
   browser escapes its content, so markdown holding </script> cannot break out
   of the page. :notes is a content value and is rendered like any other."
  [{:keys [content notes] :as slide}]
  (let [attrs (deck/section-attrs slide)
        aside (when notes [:aside.notes (content/render notes)])]
    (if (string? content)
      [:section (assoc attrs :data-markdown "")
       [:textarea {:data-template ""} content]
       aside]
      [:section attrs
       (content/render content)
       aside])))

(defn entry-hiccup
  "Deck entry -> hiccup. A :stack becomes a <section> wrapping its child
   sections; anything else is a slide."
  [entry]
  (if (= :stack (:plato/type entry))
    (into [:section (deck/section-attrs entry)] (map entry-hiccup) (:slides entry))
    (slide-hiccup entry)))

(defn slides-hiccup
  "Deck -> [:div.slides & entries]."
  [deck]
  (into [:div.slides] (map entry-hiccup) (:slides deck)))

;; ── document ────────────────────────────────────────────────────────────────

(def default-opts
  {:asset-base "."
   :theme "night"
   :math? false
   :stylesheets []
   :scripts []})

(def plugins
  "Reveal dist plugins, in load order. :file is served from
   <asset-base>/vendor/plugin/<file>.js and :global is the UMD name handed to
   Reveal.initialize — one definition with two projections, so a plugin can
   never be scripted without being registered, or registered without a script.
   :needs names the option that must be set for an optional plugin to load."
  [{:file "markdown" :global "RevealMarkdown"}
   {:file "highlight" :global "RevealHighlight"}
   {:file "notes" :global "RevealNotes"}
   {:file "math" :global "RevealMath.KaTeX" :needs :math?}
   {:file "search" :global "RevealSearch"}
   {:file "zoom" :global "RevealZoom"}])

(defn active-plugins
  "The plugins `opts` enables. An optional plugin stays out unless its :needs
   option is set: the vendored math build fetches KaTeX from a CDN at init, so
   a page that never asked for math must not phone home."
  [opts]
  (remove (fn [{:keys [needs]}] (and needs (not (get opts needs)))) plugins))

(defn plugin-scripts
  "Basenames of the plugin scripts `opts` enables, in load order. Each is
   served from <asset-base>/vendor/plugin/<name>.js."
  [opts]
  (mapv :file (active-plugins opts)))

(defn plugin-globals
  "Globals the enabled UMD plugin builds define, as passed to
   Reveal.initialize. RevealMath is a plugin object carrying its variants;
   KaTeX is the selected one."
  [opts]
  (mapv :global (active-plugins opts)))

(defn init-script
  "Inline Reveal bootstrap source for `config`, registering the plugins `opts`
   enables."
  ([config] (init-script config {}))
  ([config opts]
   (str "Reveal.initialize(Object.assign("
        (->json (or config {}))
        ", {plugins: [" (str/join ", " (plugin-globals opts)) "]}));")))

(defn- stylesheet [href]
  [:link {:rel "stylesheet" :href href}])

(defn- head-hiccup [deck {:keys [asset-base theme title stylesheets]}]
  (into [:head
         [:meta {:charset "utf-8"}]
         [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
         [:title (or title (:title deck) "Plato")]
         ;; reveal.js 6 ships reset.css separately from reveal.css; without it
         ;; the page inherits the browser's default margins.
         (stylesheet (str asset-base "/vendor/reset.css"))
         (stylesheet (str asset-base "/vendor/reveal.css"))
         (stylesheet (str asset-base "/vendor/theme/" (name theme) ".css"))
         (stylesheet (str asset-base "/vendor/highlight/monokai.css"))
         (stylesheet (str asset-base "/css/plato.css"))]
        (map stylesheet)
        stylesheets))

(defn- body-hiccup [deck {:keys [asset-base scripts] :as opts}]
  (-> [:body
       [:div.reveal (slides-hiccup deck)]
       [:script {:src (str asset-base "/vendor/reveal.js")}]]
      (into (map (fn [plugin]
                   [:script {:src (str asset-base "/vendor/plugin/" plugin ".js")}]))
            (plugin-scripts opts))
      (into (map (fn [src] [:script {:src src}])) scripts)
      (conj [:script {:type "text/javascript"} (init-script (:config deck) opts)])))

(defn deck-hiccup
  "Deck -> the whole [:html ...] document. opts: :asset-base :theme :title
   :stylesheets :scripts."
  ([deck] (deck-hiccup deck {}))
  ([deck opts]
   (let [opts (merge default-opts opts)]
     [:html {:lang (or (:lang deck) "en")}
      (head-hiccup deck opts)
      (body-hiccup deck opts)])))

(defn deck->html
  "Deck -> a standalone Reveal.js HTML document string."
  ([deck] (deck->html deck {}))
  ([deck opts] (str "<!doctype html>\n" (hiccup/->html (deck-hiccup deck opts)))))
