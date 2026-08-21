(ns plato.snapshot
  "CLJ-only: render a scene graph to a standalone SVG string (server-side export,
   e.g. slide thumbnails / static previews). Reuses the pure cljc render core,
   demonstrating the L0-L3 core runs unchanged outside the browser."
  (:require [plato.render :as render]
            [plato.scene :as sc]
            [plato.geometry :as geo]
            [clojure.java.io :as io]))

(defn- attr-str [m]
  (apply str (for [[k v] m] (str " " (name k) "=\"" v "\""))))

(defn hiccup->str [h]
  (cond
    (string? h) h
    (number? h) (str h)
    (nil? h)    ""
    (vector? h) (let [[tag & r] h
                      [attrs kids] (if (map? (first r)) [(first r) (rest r)] [{} r])]
                  (str "<" (name tag) (attr-str attrs) ">"
                       (apply str (map hiccup->str kids))
                       "</" (name tag) ">"))
    :else ""))

(defn scene->svg
  "Standalone SVG string for a scene graph (final-state snapshot)."
  [g]
  (let [t    (render/svg-target)
        body (for [[_ nd] (sort-by key (sc/nodes g))] (render/element t g nd))]
    (hiccup->str
     (into [:svg {:xmlns "http://www.w3.org/2000/svg"
                  :viewBox (str "0 0 " geo/view-w " " geo/view-h)
                  :width geo/view-w :height geo/view-h}
            [:rect {:x 0 :y 0 :width geo/view-w :height geo/view-h :fill "#0b0e13"}]]
           body))))

(defn -main [& _]
  (doseq [[nm sym] [["credit_creation"          'plato.scenes.credit-creation]
                    ["fractional_reserve"        'plato.scenes.fractional-reserve]
                    ["financial_intermediation"  'plato.scenes.financial-intermediation]]]
    (require sym)
    (let [g   @(ns-resolve sym 'graph)
          out (str "snapshots/" nm ".svg")]
      (io/make-parents out)
      (spit out (scene->svg g))
      (println :WROTE out))))
