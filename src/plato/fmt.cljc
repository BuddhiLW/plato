(ns plato.fmt
  "L0 primitive: cross-platform number formatting so the render core is cljc
   (browser via CLJS, snapshots via CLJ).")

(defn fixed
  "Format number `v` with `dp` decimal places."
  [v dp]
  #?(:cljs (.toFixed v dp)
     :clj  (format (str "%." dp "f") (double v))))
