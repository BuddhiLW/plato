(ns plato.snapshot
  "SVG export of a Desargues scene graph: one frame, or every frame.

  Sampling a scene into SVG is arithmetic over the compiled timeline, so it
  runs wherever plato's core runs. Only the last two functions here touch a
  filesystem; everything above them is pure."
  (:require [plato.geometry :as geometry]
            [plato.hiccup :as hiccup]
            [plato.render :as render]
            [plato.timeline :as timeline]
            #?(:rust [clojure.rust.io :as io]
               :clj  [clojure.java.io :as io])))

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
        ;; Round half up. `Math/round` is the obvious spelling and clojurust
        ;; has no such static; duration and fps are both non-negative here,
        ;; which is the range where adding 0.5 and truncating agrees with it.
        n (long (+ 0.5 (* dur (double fps))))]
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

(defn frame-name
  "File name for frame `i`, zero-padded to five digits."
  [i]
  (let [digits (str i)
        zeros (apply str (repeat (max 0 (- 5 (count digits))) "0"))]
    (str "frame-" zeros digits ".svg")))

(defn write-frames!
  "Write one SVG per sampled frame into `dir`, returning the paths in order.

  Names are zero-padded (`frame-00000.svg`) so a lexical sort is a temporal
  one, which is what every downstream tool that globs a frame directory
  assumes."
  [dir graph fps]
  (vec
   (map-indexed
    (fn [i [_ svg]]
      (let [path (str dir "/" (frame-name i))]
        ;; `make-parents` creates the frame's directory, which is the only
        ;; reason the directory is created at all — it is the one portable
        ;; spelling of mkdir -p across the hosts plato runs on.
        (io/make-parents path)
        (spit path svg)
        path))
    (scene->frames graph fps))))

(defn write-svg!
  "Write `graph`'s final frame to `path`, returning the path."
  [path graph]
  (io/make-parents path)
  (spit path (scene->svg graph))
  path)
