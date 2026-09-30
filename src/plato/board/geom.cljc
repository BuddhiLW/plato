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
