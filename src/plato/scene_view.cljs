(ns plato.scene-view
  (:require [reagent.core :as r]
            [plato.geometry :as geometry]
            [plato.player :as player]
            [plato.protocols :as p]
            [plato.render :as render]
            [plato.scene :as scene]
            [plato.timeline :as timeline]))

(defn- node-view [target graph frame id]
  [:g {:key (str id)}
   (p/-apply target
             (p/-element target graph (scene/node graph id))
             (get frame id {}))])

(defn- scene-canvas [target graph frame node-ids]
  (into
   [:svg {:viewBox (str "0 0 " geometry/view-w " " geometry/view-h)
          :preserveAspectRatio "xMidYMid meet"
          :role "img"
          :aria-label (str (scene/scene-name graph))}]
   (map #(node-view target graph frame %) node-ids)))

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

(defn scene-view [{:keys [graph autoplay? controls?]}]
  (r/with-let [compiled (timeline/compile-timeline graph)
               browser-player (player/player (:duration compiled))
               target (render/svg-target)
               _ (when autoplay? (p/-play! browser-player))]
    (let [playback @(player/state-atom browser-player)
          frame (timeline/frame compiled (:t playback))]
      [:div.plato-scene
       (when controls?
         [transport browser-player playback])
       [scene-canvas target graph frame (:node-ids compiled)]])
    (finally
      (player/destroy! browser-player))))
