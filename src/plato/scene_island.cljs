(ns plato.scene-island
  "A Desargues scene mounted into a DOM element with no view library.

   This is the ONE live scene implementation. plato.scene-view wraps it in a
   Reagent component for the shell; the standalone :scene bundle calls
   `hydrate` on a prerendered page. Both drive the same player and the same
   SVG renderer, and both leave the same DOM behind:

     div.plato-scene > [div.plato-transport > button, input[type=range], output]
                     > svg

   Each frame serializes the scene's hiccup through plato.hiccup and swaps the
   <svg> in place. A scene is a few dozen nodes, so a string round-trip per
   frame is cheaper than carrying a virtual DOM for it."
  (:require [cljs.reader :as reader]
            [plato.desargues :as desargues]
            [plato.hiccup :as hiccup]
            [plato.player :as player]
            [plato.protocols :as p]
            [plato.render :as render]
            [plato.timeline :as timeline]))

(defn- fmt [t] (.toFixed t 2))

(defn- play-when-visible!
  "Play `browser-player` the first time `el` is on screen. Returns the observer,
   or nil when the browser has no IntersectionObserver."
  [el browser-player]
  (when (and el (exists? js/IntersectionObserver))
    (let [observer (atom nil)]
      (reset! observer
              (js/IntersectionObserver.
               (fn [entries]
                 (when (some #(.-isIntersecting %) (array-seq entries))
                   (.disconnect @observer)
                   (p/-play! browser-player)))
               #js {:threshold 0.2}))
      (.observe @observer el)
      @observer)))

(defn- element [doc tag]
  (.createElement doc tag))

(defn- build-transport [doc browser-player duration]
  (let [root (element doc "div")
        button (element doc "button")
        range (element doc "input")
        output (element doc "output")]
    (set! (.-className root) "plato-transport")
    (.setAttribute button "type" "button")
    (.addEventListener button "click"
                       (fn [_]
                         (if (:playing? @(player/state-atom browser-player))
                           (p/-pause! browser-player)
                           (p/-play! browser-player))))
    (.setAttribute range "type" "range")
    (.setAttribute range "min" "0")
    (.setAttribute range "max" (str duration))
    (.setAttribute range "step" "0.01")
    (.setAttribute range "aria-label" "Animation position")
    (.addEventListener range "input"
                       (fn [e]
                         (p/-seek! browser-player
                                   (js/parseFloat (.. e -target -value)))))
    (.append root button range output)
    {:root root :button button :range range :output output}))

(defn- sync-transport! [{:keys [button range output]} {:keys [t duration playing?]}]
  (set! (.-textContent button) (if playing? "Pause" "Play"))
  (.setAttribute button "aria-label" (if playing? "Pause animation" "Play animation"))
  (set! (.-value range) (str t))
  (set! (.-textContent output) (str (fmt t) " / " (fmt duration) " s")))

(defn- svg-node [doc html]
  (let [tpl (element doc "template")]
    (set! (.-innerHTML tpl) html)
    (.-firstElementChild (.-content tpl))))

(defn mount!
  "Mount `scene` (a `plato.desargues/scene` value) into `el`, replacing its
   children. `:autoplay?` starts playback when the element first becomes
   visible, not when it mounts. Returns a handle for `destroy!`."
  [el {:keys [graph autoplay? controls?]}]
  (let [graph (desargues/assert-graph! graph)
        doc (.-ownerDocument el)
        compiled (timeline/compile-timeline graph)
        browser-player (player/player (:duration compiled))
        state (player/state-atom browser-player)
        target (render/svg-target)
        transport (when controls? (build-transport doc browser-player (:duration compiled)))
        svg (atom nil)
        render-frame! (fn [{:keys [t]}]
                        (let [frame (timeline/frame compiled t)
                              node (svg-node doc (hiccup/->html
                                                  (render/scene-svg target graph frame
                                                                    (:node-ids compiled))))]
                          (if-let [old @svg]
                            (.replaceWith old node)
                            (.append el node))
                          (reset! svg node)))
        watch-key (gensym "plato-scene")]
    (set! (.-innerHTML el) "")
    (when transport (.append el (:root transport)))
    (render-frame! @state)
    (when transport (sync-transport! transport @state))
    (add-watch state watch-key
               (fn [_ _ old new]
                 (when (not= (:t old) (:t new)) (render-frame! new))
                 (when transport (sync-transport! transport new))))
    {:el el
     :player browser-player
     :watch-key watch-key
     :observer (when autoplay? (play-when-visible! el browser-player))}))

(defn destroy!
  "Stop a mounted scene: playback, its frame watch and its visibility observer."
  [{:keys [player watch-key observer]}]
  (when observer (.disconnect observer))
  (when player
    (remove-watch (player/state-atom player) watch-key)
    (player/destroy! player)))

(defn ^:export hydrate
  "Mount every `[data-plato-scene]` element on the page, each the first time
   it is on screen. The attribute holds the scene value as EDN, written by the
   static projection in plato.desargues; it is removed once read so a second
   call is a no-op. Deferring the mount to visibility keeps a deck's scenes
   off the main thread while the first slide paints; a browser without
   IntersectionObserver mounts them all at once. Returns the number of scenes
   found."
  []
  (let [els (array-seq (js/document.querySelectorAll "[data-plato-scene]"))
        mount-el! (fn [el]
                    (when-let [edn (.getAttribute el "data-plato-scene")]
                      (.removeAttribute el "data-plato-scene")
                      (mount! el (reader/read-string edn))))]
    (if (exists? js/IntersectionObserver)
      (let [observer (js/IntersectionObserver.
                      (fn [entries obs]
                        (doseq [entry (array-seq entries)
                                :when (.-isIntersecting entry)]
                          (.unobserve obs (.-target entry))
                          (mount-el! (.-target entry))))
                      #js {:threshold 0.1})]
        (doseq [el els] (.observe observer el)))
      (doseq [el els] (mount-el! el)))
    (count els)))

;; Loaded async by a prerendered page, this bundle may land after Reveal has
;; already laid the deck out and the bootstrap's guarded hydrate call has
;; found nothing to call. Then the deck is `.ready` and the work is ours to
;; start. In the Reagent shell nothing is `.ready` at load time, and the
;; wrapper mounts scenes itself, so this is a no-op there.
(when (and (exists? js/document)
           (.querySelector js/document ".reveal.ready [data-plato-scene]"))
  (hydrate))
