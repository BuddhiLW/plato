(ns plato.video
  "A deck of Desargues scenes as a sequence of video frames, with no browser.

  Every frame is `(slide, t)`, and a scene frame is a pure function of t, so
  the whole video is a vector of values computed up front and rendered in any
  order, on any number of cores. This namespace is that plan and nothing else:
  it touches no filesystem and no process, so it runs wherever plato's core
  runs. Rasterising and encoding the plan is the JVM boundary in
  `plato.video.encode`.

  Only slides whose content is one Desargues scene are video-able here. A deck
  with HTML slides still goes through HyperFrames, which owns a browser."
  (:require [plato.snapshot :as snap]
            [plato.timeline :as timeline]
            [plato.scene :as sc]))

(def default-fps 30)

(defn scene-graph
  "The Desargues graph a slide shows, or nil when its content is not a scene."
  [slide]
  (let [content (:content slide)]
    (when (= :desargues (:plato/type content))
      (:graph content))))

(defn slide-seconds
  "How long `slide` holds in the video: its declared :seconds, else the length
   of its scene. A slide that animates for 2 s and says :seconds 4.5 holds its
   final frame for the last 2.5 s, which is how the HyperFrames export plays it."
  [slide compiled]
  (double (or (:seconds slide) (:duration compiled) 0)))

(defn frame-count
  "Frames a clip of `seconds` takes at `fps`, rounded half up. Written as
   add-and-truncate because clojurust has no `Math/round`; both inputs are
   non-negative, where the two agree."
  [seconds fps]
  (long (+ 0.5 (* (double seconds) (double fps)))))

(defn slide-frames
  "Frame plan for one slide: `{:graph :compiled :t}` per frame at `fps`.

   `t` is local to the slide and clamped to the scene's end, so the hold after
   the animation repeats its final frame. The slide's :background-color becomes
   the graph's :background, the one colour `plato.snapshot` paints behind a
   scene, so the video shows the slide the deck declared."
  [slide fps]
  (when-let [graph (scene-graph slide)]
    (let [compiled (timeline/compile-timeline graph)
          end (double (:duration compiled))
          bg (:background-color slide)
          graph (cond-> graph bg (assoc :background bg))
          n (frame-count (slide-seconds slide compiled) fps)]
      (mapv (fn [i] {:graph graph
                     :compiled compiled
                     :t (min end (/ (double i) (double fps)))})
            (range n)))))

(defn unplayable
  "Ids of the slides in `deck` that are not a single scene, and so cannot be
   rendered by this path. Empty means the whole deck is video-able."
  [deck]
  (vec (keep (fn [s] (when-not (scene-graph s) (:id s))) (:slides deck))))

(defn deck-frames
  "The whole deck as one frame plan, slides in order."
  ([deck] (deck-frames deck default-fps))
  ([deck fps]
   (into [] (mapcat #(slide-frames % fps)) (:slides deck))))

(defn frame-svg
  "Standalone SVG string for one planned frame."
  [{:keys [graph t compiled]}]
  (snap/scene->svg graph t compiled))

(defn frame-size
  "Output pixel size for `graph` at `width`, keeping the scene's aspect. The
   height is rounded to an even number because yuv420p, what every player
   reads, cannot encode an odd dimension."
  [graph width]
  (let [[fw fh] (sc/frame graph)
        h (long (+ 0.5 (* (double width) (/ (double fh) (double fw)))))]
    [(long width) (+ h (mod h 2))]))
