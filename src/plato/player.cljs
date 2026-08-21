(ns plato.player
  (:require [reagent.core :as r]
            [plato.clock :as clock]
            [plato.protocols :as p]))

(defn- now-seconds []
  (/ (js/performance.now) 1000.0))

(declare request-frame!)

(defrecord BrowserPlayer [state raf-id]
  p/IPlayer
  (-play! [player]
    (swap! state clock/play (now-seconds))
    (request-frame! player)
    player)
  (-pause! [player]
    (swap! state clock/pause)
    (when-let [id @raf-id]
      (js/cancelAnimationFrame id)
      (reset! raf-id nil))
    player)
  (-seek! [player t]
    (swap! state clock/seek t (now-seconds))
    player))

(defn request-frame! [{:keys [state raf-id] :as player}]
  (when (and (:playing? @state) (nil? @raf-id))
    (reset! raf-id
            (js/requestAnimationFrame
             (fn [_]
               (reset! raf-id nil)
               (swap! state clock/tick (now-seconds))
               (request-frame! player))))))

(defn player [duration]
  (->BrowserPlayer (r/atom (clock/initial-state duration)) (atom nil)))

(defn state-atom [player]
  (:state player))

(defn destroy! [player]
  (p/-pause! player))
