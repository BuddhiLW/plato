(ns plato.color
  "L0 primitive: semantic color keyword -> CSS hex, resolved from the generated
   theme tokens. Pure."
  (:require [plato.theme :as theme]))

(def palette
  "Semantic color keyword -> CSS hex."
  (select-keys (:color theme/tokens) (get-in theme/tokens [:scene :palette])))

(def fallback
  "CSS hex used for an unknown color name."
  (get (:color theme/tokens) (get-in theme/tokens [:scene :fallback]) "#8A8F98"))

(defn hex
  "CSS color for a semantic keyword; passes strings through; theme fallback."
  [c]
  (cond
    (string? c)  c
    (keyword? c) (get palette c fallback)
    :else        fallback))
