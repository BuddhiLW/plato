(ns plato.protocols
  "Rendering, tween, playback, and deck protocols.")

(defprotocol IRenderTarget
  "Turns scene nodes into visual elements and applies animated attributes."
  (-element [target g node] "Static visual element (hiccup) for a node.")
  (-apply [target el attrs] "Return `el` with animated attrs merged in."))

(defprotocol ITween
  "Samples one node animation at normalized time t in [0,1]."
  (-sample [tween t] "Attrs map (opacity/transform/fill/text) at time t."))

(defprotocol IPlayer
  "Drives a compiled timeline over wall-clock time."
  (-play! [player])
  (-pause! [player])
  (-seek! [player t]))

(defprotocol IDeck
  "Navigates a collection of slides."
  (-slides [deck])
  (-current [deck])
  (-goto [deck i])
  (-advance [deck])
  (-retreat [deck]))
