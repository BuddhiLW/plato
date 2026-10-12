(ns plato.board.geom
  "Reading a board's sampled grid: interpolation, the definite integral, the
   region under the curve.

   Host-free on purpose: the board island (cljs) calls it on the typed arrays
   the wasm kernel fills, and plato.board (clj or cljw) calls it on the frame
   the kernel produced at build time to draw the static plot. One reading of
   the grid, whichever host draws it.

   A grid is {:x0 first-x :h step :xs xs}; arrays are anything `aget` reads.")

(defn- floor* [x]
  #?(:cljs (js/Math.floor x) :default (Math/floor x)))

(defn at
  "Linear interpolation of arr at x over the grid; x outside the window
   extrapolates from the nearest end segment."
  [{:keys [x0 h xs]} arr x]
  (let [n (alength xs)
        t (/ (- x x0) h)
        i (long (max 0 (min (- n 2) (floor* t))))
        u (- t i)]
    (+ (* (- 1 u) (aget arr i)) (* u (aget arr (inc i))))))

(defn integral
  "The definite integral of f over [lo, hi], from the running integral iys:
   I(hi) - I(lo)."
  [grid iys lo hi]
  (- (at grid iys hi) (at grid iys lo)))

(defn area-points
  "The region between f and the x-axis over [lo, hi], as a closed polygon
   [[x y] ...] that starts and ends on the axis."
  [{:keys [xs] :as grid} ys lo hi]
  (let [inner (for [i (range (alength xs))
                    :let [x (aget xs i)]
                    :when (< lo x hi)]
                [x (aget ys i)])]
    (vec (concat [[lo 0] [lo (at grid ys lo)]]
                 inner
                 [[hi (at grid ys hi)] [hi 0]]))))

;; ---------------------------------------------------------------------------
;; Figures: a board of one configuration (n = 1), where every output is one
;; value and a point is a pair of output keys [kx ky].

(defn value
  "Output k's value in a one-configuration board (its first sample)."
  [arrays k]
  (aget (get arrays k) 0))

(defn point
  "The point named by output keys [kx ky]."
  [arrays [kx ky]]
  [(value arrays kx) (value arrays ky)])

(defn- finite-num? [x]
  #?(:cljs (js/isFinite x) :default (Double/isFinite (double x))))

(defn finite?
  "Is p a drawable point? A meet of parallel lines lies at infinity, and
   comes out of the kernel as a huge, infinite or NaN coordinate."
  [[x y]]
  (and (finite-num? x) (finite-num? y) (< (abs x) 1e6) (< (abs y) 1e6)))

(defn line-ends
  "Two points on the line through a and b, far enough apart to cross the
   whole window: what the static plot draws for an infinite line."
  [[ax ay :as a] [bx by] {[x0 x1] :x [y0 y1] :y}]
  (let [dx (- bx ax) dy (- by ay)
        len (#?(:cljs js/Math.sqrt :default Math/sqrt) (+ (* dx dx) (* dy dy)))
        reach (* 2 (+ (- x1 x0) (- y1 y0)))]
    (if (zero? len)
      [a a]
      (let [ux (/ dx len) uy (/ dy len)]
        [[(- ax (* reach ux)) (- ay (* reach uy))]
         [(+ ax (* reach ux)) (+ ay (* reach uy))]]))))

(defn trace
  "Every sample of the point [kx ky] as [[x y] ...], a curve when the point
   depends on the swept parameter, dropping samples at infinity."
  [arrays [kx ky]]
  (let [xs (get arrays kx) ys (get arrays ky)]
    (into [] (comp (map (fn [i] [(aget xs i) (aget ys i)])) (filter finite?))
          (range (alength xs)))))
