(ns plato.fmt
  "Cross-platform number formatting.")

(defn fixed
  "Format number `v` with `dp` decimal places."
  [v dp]
  #?(:cljs (.toFixed v dp)
     :default (format (str "%." dp "f") (double v))))
