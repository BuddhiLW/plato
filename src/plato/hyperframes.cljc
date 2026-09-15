(ns plato.hyperframes
  "Deck data -> a HyperFrames slideshow composition.

   HyperFrames (github.com/heygen-com/hyperframes) turns HTML with `data-*`
   timing attributes and seekable animations into a navigable deck
   (`hyperframes present`), per-slide stills (`hyperframes snapshot`) and a
   deterministic MP4 (`hyperframes render`). This is plato's third render
   target beside Reagent and the Reveal export: the deck's leaf slides become
   scenes timed end to end on one composition timeline, and a JSON island
   tells the HyperFrames player their order, speaker notes and hold-points.

   Pure: builds hiccup and strings, performs no I/O. Slide bodies come from
   `plato.content/render`, as in plato.html; what differs is the container and
   a post-pass that gives Reveal-only constructs a meaning HyperFrames can
   seek: fragments become timed steps, native markdown sections are expanded
   through the markdown front end, and media is placed on the timeline.

   A page carries no GSAP. Every scene registers its own timeline object on
   `window.__timelines`, the duck-typed contract the HyperFrames runtime seeks
   (pause/seek/time/duration), and that object reveals the scene's fragments
   and seeks its Desargues scenes from the ONE `plato.scene-island`
   implementation the Reveal export also loads."
  (:require [clojure.string :as str]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.desargues :as desargues]
            [plato.doc :as doc]
            [plato.hiccup :as hiccup]
            [plato.json :as json]
            [plato.source :as source]
            [plato.text :as text]
            [plato.timeline :as timeline]))

;; ── policy ──────────────────────────────────────────────────────────────────

(def timing
  "How long a slide holds, in seconds, when it does not say so with :seconds.
   :slide     a slide with nothing to reveal
   :fragment  the gap between one fragment step and the next
   :tail      kept after the last step, or after a scene's final frame"
  {:slide 5.0 :fragment 1.0 :tail 1.0})

(def frame
  "Authored frame size of every scene, in pixels."
  {:width 1920 :height 1080})

(defn scene-id?
  "Whether HyperFrames treats `id` as a slide scene. The player skips `main`
   (the root timeline) and any id naming a caption or ambient overlay, so a
   slide with such an id would silently drop out of the deck."
  [id]
  (let [s (str/lower-case (name id))]
    (not (or (= s "main")
             (str/includes? s "caption")
             (str/includes? s "ambient")))))

(defn scene-id
  "The composition id a slide gets: its own id, unless HyperFrames would not
   treat that as a scene. The rule is a substring match, so no readable
   prefix escapes it; such an id becomes `s-<stable hash>`, the same spelling
   plato.doc gives a title that slugs to nothing."
  [id]
  (if (scene-id? id) (name id) (str "s-" (text/stable-hash (name id)))))

;; ── hiccup walking ──────────────────────────────────────────────────────────

(defn- element? [node]
  (and (vector? node) (keyword? (first node))))

(defn- split-element
  "[tag attrs children] of a hiccup element; attrs is nil when absent."
  [[tag & body]]
  (if (map? (first body))
    [tag (first body) (rest body)]
    [tag nil body]))

(defn- join-element [tag attrs children]
  (into (if attrs [tag attrs] [tag]) children))

(declare transform)

(defn- transform-children [f state children]
  (reduce (fn [[state acc] child]
            (let [[state child] (transform f state child)]
              [state (conj acc child)]))
          [state []]
          children))

(defn- transform
  "Rebuild hiccup `node`, threading `state` through
   `(f state tag attrs children) -> [state attrs']` on every element, parents
   first and siblings in document order. Seqs are rebuilt element-wise; a
   component call `[fn ...]` and every other value pass through untouched."
  [f state node]
  (cond
    (element? node)
    (let [[tag attrs children] (split-element node)
          [state attrs] (f state tag attrs children)
          [state children] (transform-children f state children)]
      [state (join-element tag attrs children)])

    (seq? node)
    (let [[state xs] (transform-children f state node)]
      [state (seq xs)])

    :else [state node]))

(defn- classes
  "Every class an element carries, from its tag shorthand and its :class."
  [tag attrs]
  (let [[_ _ tag-classes] (hiccup/parse-tag tag)
        attr-classes (some-> (:class attrs) hiccup/class-string (str/split #"\s+"))]
    (into (set tag-classes) attr-classes)))

(defn- tag-name [tag]
  (first (hiccup/parse-tag tag)))

(defn- fragment? [tag attrs]
  (contains? (classes tag attrs) "fragment"))

(defn- parse-index [value]
  (if (number? value)
    (long value)
    #?(:cljs (js/parseInt (str value) 10)
       :default (Integer/parseInt (str value)))))

(defn- fragment-index
  "Reveal's ordering: an explicit :data-fragment-index is kept, and every
   fragment without one is numbered in document order from 0."
  [state attrs]
  (if-some [explicit (:data-fragment-index attrs)]
    [state (parse-index explicit)]
    [(update state :auto inc) (:auto state)]))

;; ── markdown ────────────────────────────────────────────────────────────────

(defn markdown-hiccup
  "Markdown text -> hiccup, through the markdown front end's document IR: the
   same blocks a Markdown-authored deck renders, so a string slide looks the
   same here as it does under Reveal's markdown plugin."
  [text]
  (let [document (source/->document :markdown text)
        sections (mapcat #(tree-seq (comp seq :children) :children %)
                         (:sections document))]
    (into [:div.plato-markdown]
          (map (fn [section]
                 (content/render (:content (doc/section->slide section)))))
          sections)))

(defn- textarea-text
  "The text a Reveal markdown container carries in its <textarea>."
  [children]
  (let [[_ _ text] (split-element (first children))]
    (apply str text)))

(defn- expand-markdown
  "Replace every Reveal markdown container in `node` with its rendered
   blocks. Runs before fragments are counted, so a fragment the markdown
   declares is a step like any other."
  [node]
  (cond
    (element? node)
    (let [[tag attrs children] (split-element node)]
      (if (contains? attrs :data-markdown)
        (expand-markdown (markdown-hiccup (textarea-text children)))
        (join-element tag attrs (map expand-markdown children))))

    (seq? node) (doall (map expand-markdown node))
    :else node))

;; ── the two walks over a slide body ─────────────────────────────────────────

(defn fragment-indices
  "Raw fragment indices of `node`, in document order."
  [node]
  (:raw (first (transform (fn [state tag attrs _]
                            (if (fragment? tag attrs)
                              (let [[state i] (fragment-index state attrs)]
                                [(update state :raw conj i) attrs])
                              [state attrs]))
                          {:auto 0 :raw []}
                          node))))

(defn- media-attrs
  "Place a media element on the composition timeline for the whole slide,
   under the id the renderer discovers it by. Reveal's data-autoplay goes:
   HyperFrames owns playback. A video is muted unless it declares its own
   audio, and plays inline so the renderer can capture it."
  [attrs kind id {:keys [start seconds]}]
  (cond-> (-> attrs
              (dissoc :data-autoplay)
              (assoc :id (or (:id attrs) id)
                     :data-start start
                     :data-duration seconds))
    (= "video" kind) (assoc :playsInline true)
    (and (= "video" kind) (not (:data-has-audio attrs))) (assoc :muted true)))

(defn annotate
  "Body hiccup with every fragment stamped with its step rank
   (:data-plato-step) and every media element timed to the slide. `ranks` maps
   a raw fragment index to its step; `slide` is the slide's {:id :start
   :seconds}, and a media element without an id is named after it."
  [ranks slide node]
  (second (transform (fn [state tag attrs _]
                       (let [kind (tag-name tag)
                             [state attrs] (if (fragment? tag attrs)
                                             (let [[state i] (fragment-index state attrs)]
                                               [state (assoc attrs :data-plato-step (get ranks i))])
                                             [state attrs])]
                         (if (#{"video" "audio"} kind)
                           [(update state :media inc)
                            (media-attrs attrs kind
                                         (str (:id slide) "-" kind "-" (:media state))
                                         slide)]
                           [state attrs])))
                     {:auto 0 :media 0}
                     node)))

;; ── text ────────────────────────────────────────────────────────────────────

(defn hiccup-text
  "The text content of `node`, the way a speaker-notes panel would show it."
  [node]
  (cond
    (string? node) node
    (number? node) (str node)
    (and (vector? node) (fn? (first node))) nil
    (element? node) (let [[_ _ children] (split-element node)]
                      (hiccup-text children))
    (sequential? node) (not-empty (str/join " " (keep hiccup-text node)))
    :else nil))

(defn notes-text
  "A slide's :notes content value -> plain text, or nil when there is none."
  [notes]
  (some-> notes
          content/render
          expand-markdown
          hiccup-text
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

;; ── scenes ──────────────────────────────────────────────────────────────────

(defn scene-graphs
  "Every Desargues scene graph a content value carries, in document order."
  [content]
  (->> (tree-seq coll? seq content)
       (filter #(and (map? %) (= :desargues (:plato/type %))))
       (map :graph)))

(defn- scene-seconds [graph]
  (double (:duration (timeline/compile-timeline (desargues/assert-graph! graph)))))

;; ── planning ────────────────────────────────────────────────────────────────

(defn- body-hiccup
  "A slide's content as hiccup. A string is native Reveal markdown and is
   expanded here, because no markdown plugin runs in a HyperFrames page."
  [{:keys [content]}]
  (expand-markdown (if (string? content)
                     (markdown-hiccup content)
                     (content/render content))))

(defn plan-slide
  "One leaf slide entering the composition at `start` seconds ->
     {:id :start :seconds :steps :holds :body :notes}
   :steps are the fragment reveal times local to the scene, :holds the
   absolute hold-points the player stops at. The player enters a slide at its
   first hold, so a slide with fragments holds first at its start, with
   nothing revealed, the way Reveal enters it; then at every step; and a
   Desargues scene holds at its final frame, so a slide that animates is
   entered settled."
  [slide start]
  (let [id (scene-id (:id slide))
        body (body-hiccup slide)
        raw (fragment-indices body)
        ranks (zipmap (sort (distinct raw)) (range))
        steps (mapv #(* (inc %) (:fragment timing)) (range (count ranks)))
        scene-ends (mapv scene-seconds (scene-graphs (:content slide)))
        seconds (double (or (:seconds slide)
                            (max (:slide timing)
                                 (+ (reduce max 0.0 steps) (:tail timing))
                                 (+ (reduce max 0.0 scene-ends) (:tail timing)))))
        holds (->> (concat (when (seq steps) [0.0]) steps scene-ends)
                   (map #(+ start (min (double %) seconds)))
                   distinct
                   sort
                   vec)]
    {:id id
     :start (double start)
     :seconds seconds
     :steps steps
     :holds holds
     :notes (notes-text (:notes slide))
     :background (select-keys slide [:background-color :background-image
                                     :background-gradient :background-video])
     :body (annotate ranks {:id id :start (double start) :seconds seconds} body)}))

(defn plan
  "Deck -> its slides planned end to end, stacks flattened into the main line
   in the order Reveal walks them. Throws when renaming a reserved slide id
   (see `scene-id`) collides with another slide's id."
  [deck]
  (let [slides (deck/leaf-slides deck)
        ids (map (comp scene-id :id) slides)
        collisions (keep (fn [[id n]] (when (> n 1) id)) (frequencies ids))]
    (when (seq collisions)
      (throw (ex-info (str "Slide ids collide once HyperFrames' reserved ids are prefixed: "
                           (pr-str (vec collisions)))
                      {:ids (vec collisions)})))
    (second (reduce (fn [[t plans] slide]
                      (let [p (assoc (plan-slide slide t) :track (inc (count plans)))]
                        [(+ t (:seconds p)) (conj plans p)]))
                    [0.0 []]
                    slides))))

;; ── island ──────────────────────────────────────────────────────────────────

(def island-type "application/hyperframes-slideshow+json")

(defn island
  "The slideshow manifest the HyperFrames player reads, as data."
  [plans]
  {:slides (mapv (fn [{:keys [id notes holds]}]
                   (cond-> {:scene-id id}
                     notes (assoc :notes notes)
                     (seq holds) (assoc :fragments holds)))
                 plans)})

(defn island-json
  "The island as one compact line. Compact on purpose: the HyperFrames
   presenter reads the island out of its own light DOM on a zero-delay timer
   after the element connects, and does not retry, so a manifest still
   streaming in when that timer fires leaves the deck unbound. Fewer bytes
   before the closing tag is the one lever a composition has over that race."
  [plans]
  (json/write (island plans) {:key-fn json/camel-key}))

;; ── scenes as hiccup ────────────────────────────────────────────────────────

(defn- background-style [{:keys [background-color background-image background-gradient]}]
  (cond-> {}
    background-color (assoc :background-color background-color)
    background-gradient (assoc :background-image background-gradient)
    background-image (assoc :background-image (str "url(" background-image ")")
                            :background-size "cover"
                            :background-position "center")))

(defn scene-hiccup
  "A planned slide as a HyperFrames scene: the composition element, timed on
   its own Studio track, holding the slide body in one full-frame box. The
   composition is the timed unit; the box is not, so media inside it is the
   only timed thing it wraps. The `reveal` class is there so the content
   rules plato.css scopes under it apply without Reveal's own sheet."
  [{:keys [id start seconds steps body background track]} {:keys [width height]}]
  (let [video (:background-video background)]
    [:div.reveal.plato-slide
     (cond-> {:id id
              :data-composition-id id
              :data-start start
              :data-duration seconds
              :data-track-index track
              :data-width width
              :data-height height
              :data-plato-steps (json/write steps)}
       (seq (background-style background)) (assoc :style (background-style background)))
     (when video
       [:video.clip.plato-background {:id (str id "-background")
                                      :src video
                                      :data-start start
                                      :data-duration seconds
                                      :muted true
                                      :playsInline true
                                      :loop true}])
     [:section.clip {:id (str id "-body")} body]]))

;; ── runtime ─────────────────────────────────────────────────────────────────

(def root-id
  "Composition id of the deck's root: the one id HyperFrames never treats as
   a scene, so the slideshow sees every slide and only the slides."
  "main")

(def runtime-js
  "Registers the deck's timelines on window.__timelines: the duck-typed
   contract the HyperFrames runtime seeks (pause/seek/time/duration).

   One object per scene, under the scene's composition id. HyperFrames hands
   a non-root timeline the time since its scene started, so everything there
   is local to the scene: a fragment step becomes visible once that time
   reaches it, and every Desargues scene in the slide is seeked to it through
   plato.scene-island, when that bundle is loaded.

   One object for the root, under `main`, spanning the whole deck. Seeking it
   seeks every scene to its own local time, so the deck plays the same under
   the runtime and under a player that binds the root timeline directly."
  (str
   "(function () {\n"
   "  var registry = window.__timelines = window.__timelines || {};\n"
   "  var island = window.plato && window.plato.scene_island;\n"
   "  var root = document.querySelector('[data-composition-id=\"" root-id "\"]');\n"
   "  var scenes = [];\n"
   "  function timeline(duration, apply) {\n"
   "    var t = 0, playing = false;\n"
   "    return {\n"
   "      play: function () { playing = true; },\n"
   "      pause: function () { playing = false; },\n"
   "      paused: function (value) { if (value === undefined) return !playing; playing = !value; },\n"
   "      seek: function (seconds) { t = Math.max(0, Math.min(duration, Number(seconds) || 0)); apply(t); },\n"
   "      time: function () { return t; },\n"
   "      duration: function () { return duration; },\n"
   "      timeScale: function () {},\n"
   "      add: function () {},\n"
   "      set: function () {}\n"
   "    };\n"
   "  }\n"
   "  Array.prototype.forEach.call(document.querySelectorAll('[data-composition-id][data-plato-steps]'), function (el) {\n"
   "    var id = el.getAttribute('data-composition-id');\n"
   "    var steps = JSON.parse(el.getAttribute('data-plato-steps'));\n"
   "    var start = parseFloat(el.getAttribute('data-start')) || 0;\n"
   "    var duration = parseFloat(el.getAttribute('data-duration')) || 0;\n"
   "    var fragments = el.querySelectorAll('[data-plato-step]');\n"
   "    var players = [];\n"
   "    if (island && island.seekable) {\n"
   "      Array.prototype.forEach.call(el.querySelectorAll('[data-plato-scene]'), function (node) {\n"
   "        var player = island.seekable(node);\n"
   "        if (player) players.push(player);\n"
   "      });\n"
   "    }\n"
   "    var scene = timeline(duration, function (t) {\n"
   "      var shown = 0;\n"
   "      for (var i = 0; i < steps.length; i++) if (t + 1e-6 >= steps[i]) shown = i + 1;\n"
   "      Array.prototype.forEach.call(fragments, function (f) {\n"
   "        f.classList.toggle('visible', parseInt(f.getAttribute('data-plato-step'), 10) < shown);\n"
   "      });\n"
   "      players.forEach(function (p) { p.seek(t); });\n"
   "    });\n"
   "    registry[id] = scene;\n"
   "    scenes.push({ start: start, timeline: scene });\n"
   "    scene.seek(0);\n"
   "  });\n"
   "  if (root) {\n"
   "    registry['" root-id "'] = timeline(parseFloat(root.getAttribute('data-duration')) || 0, function (t) {\n"
   "      scenes.forEach(function (s) { s.timeline.seek(t - s.start); });\n"
   "    });\n"
   "  }\n"
   "})();"))

(def scene-runtime
  "The standalone scene bundle, relative to :asset-base — the same
   plato.scene-island the Reveal export loads. Loaded synchronously here: the
   runtime script after it mounts the scenes, and a HyperFrames page is only
   ever captured once every script has run."
  {:src "/vendor/plato-scene/main.js"})

(def hyperframes-runtime
  "The HyperFrames runtime, relative to :asset-base: @hyperframes/core's
   dist/hyperframe.runtime.iife.js, vendored by `bb assets`. The page carries
   it because the deck has no root composition: the player binds a root-less
   page straight to the first scene's timeline and never injects the runtime,
   and only the runtime reports the scene list the slideshow navigates.
   Linked first in <head>, where HyperFrames' own servers place it; the render
   pipeline recognises the file name and does not load a second copy."
  {:src "/hyperframes/hyperframe.runtime.iife.js"})

;; ── document ────────────────────────────────────────────────────────────────

(def base-css
  "The clip box and the reveal states. Colors and faces come from the plato
   theme tokens when the page links them, with fallbacks so a page without the
   sheet is still legible. Nothing here knows the frame size: that is
   `frame-css`, so one deck can export at 1920x1080 and at 1080x1920."
  (str
   "body{margin:0;background:#000}\n"
   ".plato-deck{position:relative;overflow:hidden}\n"
   ".plato-slide{position:absolute;inset:0;overflow:hidden;"
   "background:var(--plato-bg,#141414);color:var(--plato-fg,#f2efe9);"
   "font-family:var(--plato-sans,system-ui,sans-serif);font-size:44px;line-height:1.35}\n"
   ".clip{position:absolute;inset:0;display:grid;place-items:center;"
   "padding:96px 144px;box-sizing:border-box}\n"
   ".plato-slide h1{font-size:96px;line-height:1.05;margin:0 0 0.4em}\n"
   ".plato-slide h2{font-size:72px;line-height:1.1;margin:0 0 0.4em}\n"
   ".plato-slide h3{font-size:56px;margin:0 0 0.4em}\n"
   ".plato-slide img,.plato-slide video{max-width:100%;max-height:100%}\n"
   ".plato-background{object-fit:cover;z-index:0;padding:0}\n"
   ".plato-slide section.clip{z-index:1}\n"
   ".plato-scene svg{width:100%;height:100%}\n"
   ".plato-transport{display:none}\n"
   ".fragment{opacity:0;transition:opacity 0.3s}\n"
   ".fragment.visible{opacity:1}\n"))

(defn frame-css
  "The pixel frame of one export. The deck and every slide are exactly this
   box, which is what makes a vertical ad (1080x1920) the same deck as a
   16:9 one. Sizes are interpolated as authored, so a caller may pass the
   number 1080 or the string a CLI flag carries."
  [{:keys [width height]}]
  (str ".plato-deck{width:" width "px;height:" height "px}\n"
       ".plato-slide{width:" width "px;height:" height "px}\n"))

(def default-opts
  (merge frame
         {:asset-base "."
          :stylesheets []}))

(defn- stylesheet [href]
  [:link {:rel "stylesheet" :href href}])

(defn- head-hiccup [deck {:keys [asset-base title description stylesheets width height] :as opts}]
  (let [description (or description (:description deck))]
    (into (cond-> [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport" :content (str "width=" width ", height=" height)}]
                   [:script {:src (str asset-base (:src hyperframes-runtime))}]]
            description (conj [:meta {:name "description" :content description}])
            true (conj [:title (or title (:title deck) "Plato")]
                       [:style (str base-css (frame-css opts))]
                       (stylesheet (str asset-base "/css/plato.css"))))
          (map stylesheet)
          stylesheets)))

(defn needs-scene-runtime?
  "Does any slide carry a Desargues scene? Then the page loads the scene
   bundle, so the scene is seeked rather than shown as its final frame."
  [deck]
  (boolean (some (comp seq scene-graphs :content) (deck/leaf-slides deck))))

(defn total-seconds
  "How long the whole deck runs: where its last scene ends."
  [plans]
  (let [{:keys [start seconds]} (last plans)]
    (+ (double (or start 0.0)) (double (or seconds 0.0)))))

(defn root-hiccup
  "The deck as one root composition holding every scene. The root is what
   makes the deck a composition HyperFrames can play end to end: its duration
   is the render length, and the scenes inside it are the slides the
   slideshow navigates."
  [plans {:keys [width height] :as opts}]
  (into [:div.plato-deck {:id root-id
                          :data-composition-id root-id
                          :data-start 0
                          :data-duration (total-seconds plans)
                          :data-width width
                          :data-height height}]
        (map #(scene-hiccup % opts))
        plans))

(defn- body-hiccup* [deck plans {:keys [asset-base] :as opts}]
  (-> [:body
       [:script {:type island-type} (str "\n" (island-json plans) "\n")]
       (root-hiccup plans opts)]
      (cond-> (needs-scene-runtime? deck)
        (conj [:script {:src (str asset-base (:src scene-runtime))}]))
      (conj [:script runtime-js])))

(defn deck-hiccup
  "Deck -> the whole [:html ...] document of a HyperFrames slideshow. opts:
   :asset-base :title :description :stylesheets :width :height."
  ([deck] (deck-hiccup deck {}))
  ([deck opts]
   (let [opts (merge default-opts opts)
         plans (plan deck)]
     [:html {:lang (or (:lang deck) "en")}
      (head-hiccup deck opts)
      (body-hiccup* deck plans opts)])))

(defn deck->composition
  "Deck -> a HyperFrames slideshow composition, as the index.html string of a
   HyperFrames project."
  ([deck] (deck->composition deck {}))
  ([deck opts] (str "<!doctype html>\n" (hiccup/->html (deck-hiccup deck opts)))))

;; ── presenter ───────────────────────────────────────────────────────────────

(def presenter-scripts
  "The HyperFrames player and slideshow elements, relative to :asset-base:
   @hyperframes/player's global builds, vendored by `bb assets`."
  ["/hyperframes/hyperframes-player.global.js"
   "/hyperframes/hyperframes-slideshow.global.js"])

(def presenter-css
  (str "html,body{height:100%;margin:0;background:#0a0a0a;overflow:hidden}\n"
       "hyperframes-slideshow{display:block;position:relative;width:100vw;height:100vh}\n"
       "hyperframes-player{position:absolute;inset:0}\n"))

(defn presenter-hiccup
  "The deck's presenter page: <hyperframes-slideshow> around an
   <hyperframes-player> showing the composition, with the island beside it —
   the page `hyperframes present` builds, written to disk so any static
   server presents the deck.

   The element scripts come AFTER the element, at the end of the body. The
   slideshow element initialises on a zero-delay timer after it connects and
   reads its player and island out of its own children; loaded in the head,
   as HyperFrames' presenter loads them, it can connect while the parser is
   still inside its open tag, find no children, and never try again. Defined
   once the children exist, it cannot miss them."
  [deck plans {:keys [asset-base title after-player]}]
  [:html {:lang (or (:lang deck) "en")}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (str (or title (:title deck) "Plato") " — Presenter")]
    [:style presenter-css]]
   (-> [:body
        [:hyperframes-slideshow {:tabindex 0 :sound true}
         [:hyperframes-player {:interactive true :src "index.html"}]
         [:script {:type island-type} (str "\n" (island-json plans) "\n")]]]
       (into after-player)
       (into (map (fn [src] [:script {:src (str asset-base src)}])) presenter-scripts))])

(defn deck->presenter
  "Deck -> the present.html string of a HyperFrames project. opts: those of
   deck-hiccup, plus :after-player, hiccup appended after the slideshow
   element and before the scripts that upgrade it."
  ([deck] (deck->presenter deck {}))
  ([deck opts]
   (let [opts (merge default-opts opts)]
     (str "<!doctype html>\n" (hiccup/->html (presenter-hiccup deck (plan deck) opts))))))
