(ns plato.snapshot
  (:require [clojure.string :as str]
            [plato.geometry :as geometry]
            [plato.protocols :as protocols]
            [plato.render :as render]
            [plato.scene :as scene]
            [plato.timeline :as timeline]))

(defn- escape-xml [value]
  (str/escape (str value)
              {\& "&amp;"
               \< "&lt;"
               \> "&gt;"
               \" "&quot;"
               \' "&apos;"}))

(defn- attr-str [attrs]
  (apply str
         (map (fn [[key value]]
                (str " " (name key) "=\"" (escape-xml value) "\""))
              attrs)))

(defn hiccup->str [hiccup]
  (cond
    (string? hiccup) (escape-xml hiccup)
    (number? hiccup) (str hiccup)
    (nil? hiccup) ""
    (vector? hiccup)
    (let [[tag & body] hiccup
          [attrs children] (if (map? (first body))
                             [(first body) (rest body)]
                             [{} body])]
      (str "<" (name tag) (attr-str attrs) ">"
           (apply str (map hiccup->str children))
           "</" (name tag) ">"))
    :else ""))

(defn scene->svg [graph]
  (let [compiled (timeline/compile-timeline graph)
        frame (timeline/frame compiled (:duration compiled))
        target (render/svg-target)
        body (map (fn [id]
                    (protocols/-apply
                     target
                     (render/element target graph (scene/node graph id))
                     (get frame id {})))
                  (:node-ids compiled))]
    (hiccup->str
     (into [:svg {:xmlns "http://www.w3.org/2000/svg"
                  :viewBox (str "0 0 " geometry/view-w " " geometry/view-h)
                  :width geometry/view-w
                  :height geometry/view-h}
            [:rect {:x 0
                    :y 0
                    :width geometry/view-w
                    :height geometry/view-h
                    :fill "#0b0e13"}]]
           body))))

(defn write-svg! [path graph]
  (spit path (scene->svg graph))
  path)
