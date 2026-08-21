(ns plato.geometry
  "L0 primitive: Manim world coords (centered, y-up, ~14.222 x 8 units) -> SVG
   viewBox px (top-left origin, y-down). Pure.")

(def frame-w 14.222)
(def frame-h 8.0)
(def px-per-unit 60)

(defn ->x [mx] (* px-per-unit (+ (double mx) (/ frame-w 2.0))))
(defn ->y [my] (* px-per-unit (- (/ frame-h 2.0) (double my))))
(defn ->len [u] (* px-per-unit (double u)))

(def view-w (->len frame-w))
(def view-h (->len frame-h))

(defn point
  "[x y z] manim -> {:x px :y px} svg."
  [[x y _]]
  {:x (->x x) :y (->y y)})
