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
  "Serialized standalone SVG of `graph`, at its final frame or at `now` seconds.

  The one-argument arity is the final frame, which is what a still export
  wants. Passing a time is what a video export wants: every frame of a scene is
  reachable, not just the last one, because `plato.timeline/frame` was already
  a function of wall-clock time and only this namespace pinned it to the end."
  ([graph]
   (let [compiled (timeline/compile-timeline graph)]
     (scene->svg graph (:duration compiled) compiled)))
  ([graph now]
   (scene->svg graph now (timeline/compile-timeline graph)))
  ([graph now compiled]
   ;; The compiled timeline is accepted so that sampling many frames of one
   ;; scene compiles it once instead of once per frame.
   (let [frame (timeline/frame compiled (or now (:duration compiled)))
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
            groups)))))

(defn scene-duration
  "Wall-clock length of `graph` in seconds."
  [graph]
  (:duration (timeline/compile-timeline graph)))

(defn frame-times
  "The sample times, in seconds, for `graph` at `fps`.

  Both endpoints are included, so the last sample is the final frame and a
  scene that should come to rest actually does. A scene with no duration
  yields a single time: a still is a one-frame video, not an empty one."
  [graph fps]
  (let [dur (double (scene-duration graph))
        n (long (Math/round (* dur (double fps))))]
    (if (or (zero? n) (zero? dur))
      [0.0]
      (mapv (fn [i] (* dur (/ (double i) n))) (range (inc n))))))

(defn scene->frames
  "Lazy sequence of `[seconds svg-string]` for `graph` sampled at `fps`.

  The timeline is compiled once and reused for every frame; compiling per
  frame is the obvious way to write this and turns an O(n) export into O(n^2)."
  [graph fps]
  (let [compiled (timeline/compile-timeline graph)]
    (map (fn [t] [t (scene->svg graph t compiled)]) (frame-times graph fps))))

(defn write-frames!
  "Write one SVG per sampled frame into `dir`, returning the paths in order.

  Names are zero-padded (`frame-00000.svg`) so a lexical sort is a temporal
  one, which is what every downstream tool that globs a frame directory
  assumes."
  [dir graph fps]
  (let [dir-file (java.io.File. ^String dir)]
    (.mkdirs dir-file)
    (vec
     (map-indexed
      (fn [i [_ svg]]
        (let [path (str dir "/" (format "frame-%05d.svg" i))]
          (spit path svg)
          path))
      (scene->frames graph fps)))))

(defn write-svg! [path graph]
  (spit path (scene->svg graph))
  path)
