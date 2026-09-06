(ns plato.scene-view
  "The Reagent face of plato.scene-island: a component that hands its DOM node
   to the island on mount and takes it back on unmount. The scene itself is
   played, drawn and scrubbed by the island, so the shell and a prerendered
   page run one implementation."
  (:require [reagent.core :as r]
            [plato.scene-island :as island]
            [plato.content :as content]
            [plato.desargues :as desargues]))

(defn scene-view
  "Reagent component for a Desargues scene. `:autoplay?` starts playback when the
   scene first becomes visible, not when the deck mounts."
  [scene]
  (let [node (atom nil)
        handle (atom nil)]
    (r/create-class
     {:display-name "PlatoSceneView"
      :component-did-mount
      (fn [_]
        (reset! handle (island/mount! @node scene)))
      :component-will-unmount
      (fn [_]
        (when-let [h @handle] (island/destroy! h))
        (reset! handle nil))
      :reagent-render
      (fn [_]
        [:div.plato-scene {:ref #(reset! node %)}])})))

(defmethod content/render :desargues [scene]
  ;; plato.desargues installs the STATIC :desargues method the exporter uses;
  ;; this one must be installed after it to win in the browser, so the require
  ;; above is load-bearing. Calling into that namespace keeps the dependency
  ;; visible to tooling that would otherwise prune an unused require — and it
  ;; holds the live method to the same precondition as the static one.
  [scene-view (update scene :graph desargues/assert-graph!)])
