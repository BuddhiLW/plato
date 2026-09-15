(ns plato.geometry
  "L0 primitive: Manim world coords (centered, y-up, ~14.222 x 8 units) -> SVG
   viewBox px (top-left origin, y-down). Pure.")

(def frame-w 14.222)
(def frame-h 8.0)
(def px-per-unit 60)

(def default-frame
  "The 16:9 world every scene had before a scene could declare its own."
  [frame-w frame-h])

(defn frame-size
  "A declared frame as [w h] world units. A vector is taken as it is, a map
   spells the same thing with :width/:height (or :w/:h), and nil is the
   default 16:9 frame."
  [frame]
  (cond
    (nil? frame) default-frame
    (map? frame) [(double (or (:width frame) (:w frame) frame-w))
                  (double (or (:height frame) (:h frame) frame-h))]
    :else [(double (nth frame 0 frame-w)) (double (nth frame 1 frame-h))]))

(defn ->x
  ([mx] (->x default-frame mx))
  ([[w _] mx] (* px-per-unit (+ (double mx) (/ (double w) 2.0)))))
(defn ->y
  ([my] (->y default-frame my))
  ([[_ h] my] (* px-per-unit (- (/ (double h) 2.0) (double my)))))
(defn ->len [u] (* px-per-unit (double u)))

(def view-w (->len frame-w))
(def view-h (->len frame-h))

(defn view-size
  "[width height] of a frame's viewBox, in px."
  [frame]
  (let [[w h] (frame-size frame)] [(->len w) (->len h)]))

(defn point
  "[x y z] manim -> {:x px :y px} svg, against a frame (default 16:9)."
  ([at] (point default-frame at))
  ([frame [x y _]] {:x (->x frame x) :y (->y frame y)}))
