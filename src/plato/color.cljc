(ns plato.color
  "L0 primitive: semantic color keyword -> CSS hex. Pure. Mirrors the palette
   desargues emits (colors cross the scene-graph as keywords, resolved here).")

(def palette
  {:teal   "#5CD0B3"
   :gold   "#F0AC5F"
   :grey   "#8A8F98"
   :gray   "#8A8F98"
   :red    "#FC6255"
   :yellow "#FFFF3B"
   :white  "#FFFFFF"
   :blue   "#58C4DD"
   :green  "#83C167"
   :purple "#9A72AC"
   :orange "#FF862F"
   :pink   "#FF69B4"
   :black  "#000000"})

(defn hex
  "CSS color for a semantic keyword; passes strings through; grey fallback."
  [c]
  (cond
    (string? c)  c
    (keyword? c) (get palette c "#8A8F98")
    :else        "#8A8F98"))
