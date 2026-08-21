(ns plato.protocols
  "SOLID seams (ISP): rendering targets, tweens, playback and slide navigation
   are separate protocols so partial impls and alternate targets compose
   (DIP/OCP/LSP) — mirrors desargues.scene.protocols on the render side.")

(defprotocol IRenderTarget
  "A surface that turns a scene node into a visual element and applies animated
   attrs to it. DIP seam: SVG-hiccup target now, a headless test target later."
  (-element [target g node] "Static visual element (hiccup) for a node.")
  (-apply [target el attrs] "Return `el` with animated attrs merged in."))

(defprotocol ITween
  "One animation over a node across normalized time t in [0,1].
   OCP: a record per anim kind; adding a kind never touches the player."
  (-sample [tween t] "Attrs map (opacity/transform/fill/text) at time t."))

(defprotocol IPlayer
  "Drives a compiled timeline over wall-clock time."
  (-play! [player])
  (-pause! [player])
  (-seek! [player t]))

(defprotocol IDeck
  "Reveal-like navigation over slides. ISP: orthogonal to playback."
  (-slides [deck])
  (-current [deck])
  (-goto [deck i])
  (-advance [deck])
  (-retreat [deck]))
