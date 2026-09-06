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
   of the page. data-markdown goes on a NESTED div, never on the section: the
   plugin replaces the innerHTML of the element it finds it on, and on the
   section that swallows the notes aside beside the template. Same container
   rule as plato.core/slide-view; only the template element differs, because
   a serialized page and a React tree are different threat models.
   :notes is a content value and is rendered like any other."
  [{:keys [content notes] :as slide}]
  (let [attrs (deck/section-attrs slide)
        aside (when notes [:aside.notes (content/render notes)])]
    (if (string? content)
      [:section attrs
       [:div {:data-markdown ""}
        [:textarea {:data-template ""} content]]
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
   :fit? false
   :live-scenes? false
   :stylesheets []
   :scripts []})

(def plugins
  "Reveal plugins, in load order. :file is served from
   <asset-base>/vendor/plugin/<file>.js unless :src names another path under
   :asset-base, and :global is the UMD name handed to Reveal — one definition
   with two projections, so a plugin can never be scripted without being
   registered, or registered without a script.
   :needs names the option that must be set for an optional plugin to load.

   :async? marks a plugin nothing on the first paint depends on: its script
   loads async and registers itself on load. Reveal initializes a plugin
   registered after it is ready, and queues one registered before
   Reveal.initialize ran, so either arrival order works. markdown and
   highlight are NOT async: one converts sections before layout, the other
   builds the line-highlight fragments layout counts. math is: KaTeX renders
   in place and lays the deck out again once it has, and its bundle is the
   largest script a math deck carries.

   highlight is plato's own build of the upstream plugin (plato.highlight):
   highlight.js core plus the languages the decks use, a tenth of the dist
   plugin's size, and the same module the Reagent shell registers."
  [{:file "markdown" :global "RevealMarkdown"}
   {:file "highlight" :src "/vendor/plato-highlight/main.js" :global "RevealHighlight"}
   {:file "notes" :global "RevealNotes" :async? true}
   {:file "math" :global "RevealMath.KaTeX" :needs :math? :async? true}
   {:file "search" :global "RevealSearch" :async? true}
   {:file "zoom" :global "RevealZoom" :async? true}])

(defn active-plugins
  "The plugins `opts` enables. An optional plugin stays out unless its :needs
   option is set: math brings the whole of KaTeX with it, and a page without a
   formula must not carry that."
  [opts]
  (remove (fn [{:keys [needs]}] (and needs (not (get opts needs)))) plugins))

(defn plugin-src
  "A plugin's script path relative to :asset-base: its :src when it has one,
   else /vendor/plugin/<file>.js."
  [{:keys [file src]}]
  (or src (str "/vendor/plugin/" file ".js")))

(defn plugin-scripts
  "Script paths of the plugins `opts` enables, relative to :asset-base, in
   load order."
  [opts]
  (mapv plugin-src (active-plugins opts)))

(defn plugin-script-tag
  "The <script> for one plugin. An :async? plugin loads off the critical path
   and hands itself to Reveal on load; the others are ordinary sync scripts
   the bootstrap names in its plugins list."
  [asset-base {:keys [global async?] :as plugin}]
  [:script (cond-> {:src (str asset-base (plugin-src plugin))}
             async? (assoc :async ""
                           :onload (str "Reveal.registerPlugin(" global ")")))])

(defn plugin-globals
  "Globals of the plugins Reveal.initialize registers up front: the enabled,
   non-async ones. An async plugin registers itself when its script lands.
   RevealMath is a plugin object carrying its variants; KaTeX is the selected
   one."
  [opts]
  (mapv :global (remove :async? (active-plugins opts))))

(def fit-runtime
  "The standalone plato.fit bundle, relative to :asset-base, and the call that
   starts it. Built by the shadow :fit target — the SAME plato.fit the Reagent
   shell loads, compiled on its own, so an exported page judges fit by one
   definition rather than a second one written for it."
  {:src "/vendor/plato-fit/main.js"
   :call "plato.fit.fitDeck();"})

(def scene-runtime
  "The standalone scene bundle, relative to :asset-base, and the call that
   hydrates every `[data-plato-scene]` element into a live, scrub-able scene.
   Built by the shadow :scene target — the SAME plato.scene-island the Reagent
   shell mounts through plato.scene-view, compiled on its own, so an exported
   page plays a scene by one definition rather than a second one written for
   it. Opt-in through :live-scenes?: without it an export stays the standalone
   final-frame page it always was.

   The script is loaded async: a scene is never on the first slide's critical
   path, so it must not hold up Reveal's first paint. Either side may arrive
   first, so the bootstrap's call is guarded and the bundle also hydrates
   itself on load when the deck is already ready; hydrate is idempotent."
  {:src "/vendor/plato-scene/main.js"
   :async? true
   :call "if (window.plato && plato.scene_island) plato.scene_island.hydrate();"})

(def katex-runtime
  "Where a page finds KaTeX, relative to :asset-base: the katex npm package's
   dist tree, vendored by `bb assets`. Reveal's KaTeX plugin reads the path
   from the config's :katex :local and loads <local>/dist/katex.min.{js,css}
   and the auto-render extension from there, and never from its CDN default."
  {:local "/vendor/katex"})

(defn reveal-config
  "The config handed to Reveal.initialize: the deck's own :config, plus where
   KaTeX lives when the page carries the math plugin. Both render targets call
   this, so the live shell and the export point at the same copy. A deck may
   set its own :katex options (delimiters, macros); only :local is filled in."
  [deck opts]
  (let [{:keys [asset-base math?]} (merge default-opts opts)
        math? (boolean (or math? (:math? deck)))]
    (cond-> (or (:config deck) {})
      math? (update :katex #(merge {:local (str asset-base (:local katex-runtime))} %)))))

(defn needs-fit-runtime?
  "Does this deck have to carry plato.fit to render correctly?

   True when any slide declares {:overflow :shrink}: the scale that makes such a
   slide fit is measured from a laid-out page, so an export without the runtime
   would show the overflow the author already answered for. Read off the deck
   rather than asked of the caller — an author who declared :shrink has said
   everything plato needs, and a flag they could forget would silently undo it."
  [deck]
  (boolean (some #(= :shrink (:overflow %)) (deck/leaf-slides deck))))

(defn init-script
  "Inline Reveal bootstrap source for `config`, registering the plugins `opts`
   enables.

   With :fit? set the bootstrap also starts plato.fit, and with :live-scenes?
   it hydrates the scenes. Both chain off the promise `Reveal.initialize`
   returns rather than calling straight through: that promise resolves once the
   deck is laid out, which is the first moment a slide's size is a fact rather
   than a guess."
  ([config] (init-script config {}))
  ([config {:keys [fit? live-scenes?] :as opts}]
   (let [calls (cond-> []
                 fit? (conj (:call fit-runtime))
                 live-scenes? (conj (:call scene-runtime)))]
     (str "Reveal.initialize(Object.assign("
          (->json (or config {}))
          ", {plugins: [" (str/join ", " (plugin-globals opts)) "]}))"
          (when (seq calls)
            (str ".then(function () { " (str/join " " calls) " })"))
          ";"))))

(defn- stylesheet [href]
  [:link {:rel "stylesheet" :href href}])

(defn- head-hiccup [deck {:keys [asset-base theme title description stylesheets]}]
  (let [description (or description (:description deck))]
    (into (cond-> [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
            description (conj [:meta {:name "description" :content description}])
            true (conj [:title (or title (:title deck) "Plato")]
                       ;; reveal.js 6 ships reset.css separately from reveal.css;
                       ;; without it the page inherits the browser's default margins.
                       (stylesheet (str asset-base "/vendor/reset.css"))
                       (stylesheet (str asset-base "/vendor/reveal.css"))
                       (stylesheet (str asset-base "/vendor/theme/" (name theme) ".css"))
                       (stylesheet (str asset-base "/vendor/highlight/monokai.css"))
                       (stylesheet (str asset-base "/css/plato.css"))))
          (map stylesheet)
          stylesheets)))

(defn- body-hiccup [deck {:keys [asset-base scripts after-slides] :as opts}]
  (let [fit? (or (:fit? opts) (needs-fit-runtime? deck))
        live-scenes? (boolean (:live-scenes? opts))
        opts (assoc opts :fit? fit? :live-scenes? live-scenes?)]
    (-> [:body
         [:div.reveal (slides-hiccup deck)]]
        (into after-slides)
        (conj [:script {:src (str asset-base "/vendor/reveal.js")}])
        (into (map #(plugin-script-tag asset-base %)) (active-plugins opts))
        (cond-> fit? (conj [:script {:src (str asset-base (:src fit-runtime))}])
                live-scenes? (conj [:script {:src (str asset-base (:src scene-runtime))
                                             :async ""}]))
        (into (map (fn [src] [:script {:src src}])) scripts)
        (conj [:script {:type "text/javascript"} (init-script (reveal-config deck opts) opts)]))))

(defn deck-hiccup
  "Deck -> the whole [:html ...] document. opts: :asset-base :theme :title
   :description :stylesheets :scripts :math? :fit? :live-scenes? :after-slides.

   :math? is read off the deck as well as the opts: a deck that declares
   {:math? true} has said it needs the plugin, and the live shell reads the
   same key, so the two render targets cannot disagree about it."
  ([deck] (deck-hiccup deck {}))
  ([deck opts]
   (let [opts (-> (merge default-opts opts)
                  (assoc :math? (boolean (or (:math? opts) (:math? deck)))))]
     [:html {:lang (or (:lang deck) "en")}
      (head-hiccup deck opts)
      (body-hiccup deck opts)])))

(defn deck->html
  "Deck -> a standalone Reveal.js HTML document string."
  ([deck] (deck->html deck {}))
  ([deck opts] (str "<!doctype html>\n" (hiccup/->html (deck-hiccup deck opts)))))
