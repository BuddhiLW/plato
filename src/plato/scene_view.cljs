(ns plato.scene-view
  (:require [reagent.core :as r]
            [plato.player :as player]
            [plato.protocols :as p]
            [plato.render :as render]
            [plato.timeline :as timeline]
            [plato.content :as content]
            [plato.desargues :as desargues]))

(defn- transport [browser-player playback]
  (let [{:keys [t duration playing?]} playback]
    [:div.plato-transport
     [:button {:type "button"
               :aria-label (if playing? "Pause animation" "Play animation")
               :on-click #(if playing?
                            (p/-pause! browser-player)
                            (p/-play! browser-player))}
      (if playing? "Pause" "Play")]
     [:input {:type "range"
              :min 0
              :max duration
              :step 0.01
              :value t
              :aria-label "Animation position"
              :on-change #(p/-seek! browser-player
                                    (js/parseFloat (.. % -target -value)))}]
     [:output (str (.toFixed t 2) " / " (.toFixed duration 2) " s")]]))

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

(defn scene-view
  "Reagent component for a Desargues scene. `:autoplay?` starts playback when the
   scene first becomes visible, not when the deck mounts."
  [{:keys [graph autoplay? controls?]}]
  (r/with-let [compiled (timeline/compile-timeline graph)
               browser-player (player/player (:duration compiled))
               target (render/svg-target)
               observer (atom nil)]
    (let [playback @(player/state-atom browser-player)
          frame (timeline/frame compiled (:t playback))]
      [:div.plato-scene
       {:ref (fn [el]
               (when (and autoplay? el (nil? @observer))
                 (reset! observer (play-when-visible! el browser-player))))}
       (when controls?
         [transport browser-player playback])
       (render/scene-svg target graph frame (:node-ids compiled))])
    (finally
      (when-let [obs @observer] (.disconnect obs))
      (player/destroy! browser-player))))

(defmethod content/render :desargues [scene]
  ;; plato.desargues installs the STATIC :desargues method the exporter uses;
  ;; this one must be installed after it to win in the browser, so the require
  ;; above is load-bearing. Calling into that namespace keeps the dependency
  ;; visible to tooling that would otherwise prune an unused require — and it
  ;; holds the live method to the same precondition as the static one.
  [scene-view (update scene :graph desargues/assert-graph!)])
