(ns plato.snapshot
  "Static final-frame SVG export of a Desargues scene graph."
  (:require [plato.geometry :as geometry]
            [plato.hiccup :as hiccup]
            [plato.render :as render]
            [plato.timeline :as timeline]))

(defn hiccup->str
  "Hiccup value -> HTML/XML string."
  [hiccup]
  (hiccup/->html hiccup))

(defn scene->svg
  "Serialized standalone SVG of `graph` at its final frame."
  [graph]
  (let [compiled (timeline/compile-timeline graph)
        frame (timeline/frame compiled (:duration compiled))
        [_ attrs & groups] (render/scene-svg (render/svg-target) graph frame
                                             (:node-ids compiled))]
    (hiccup->str
     (into [:svg (assoc attrs
                        :xmlns "http://www.w3.org/2000/svg"
                        :width geometry/view-w
                        :height geometry/view-h)
            [:rect {:x 0
                    :y 0
                    :width geometry/view-w
                    :height geometry/view-h
                    :fill "#0b0e13"}]]
           groups))))

(defn write-svg! [path graph]
  (spit path (scene->svg graph))
  path)
