(ns plato.board
  "Board contract, the :board content constructor, and the STATIC projection
   of that content kind: the seam where an interactive calculus board, as
   desargues.board compiles it, becomes a slide.

   plato knows no calculus. A board value arrives already solved: a
   WebAssembly kernel (a URL and an export name), the window, the slider
   params, where the probes start, and :board/frame, the kernel's own samples
   at the params' initial values. From the frame plato draws a static plot,
   so a page without JavaScript still shows the right curve; the value itself
   rides in a data attribute, and a page that carries the board bundle
   (plato.board-island) hydrates it into a live board calling the kernel on
   every drag. Same shape as plato.desargues and the scene island.

   Kernel ABI, from desargues.board:
     (export xs ys dys iys n x0 h p1 p2 ...) -> n
   four f64 arrays of length n at byte offsets in the module's `memory`;
   params in the order of :board/params."
  (:require [plato.board.geom :as geom]
            [plato.content :as content]))

(defn board?
  "Is value a board as desargues.board produces it?"
  [value]
  (let [{:board/keys [kernel window params frame]} value]
    (boolean
     (and (map? value)
          (string? (:wasm kernel))
          (string? (:export kernel))
          (let [{[x0 x1] :x [y0 y1] :y n :n} window]
            (and (number? x0) (number? x1) (< x0 x1)
                 (number? y0) (number? y1) (< y0 y1)
                 (integer? n) (> n 1)))
          (vector? params)
          (every? (fn [{:keys [id min max init]}]
                    (and (keyword? id) (number? min) (number? max) (number? init)))
                  params)
          (every? #(= (:n window) (count (get frame %))) [:xs :ys :dys :iys])))))

(defn assert-board! [value]
  (if (board? value)
    value
    (throw (ex-info "Invalid board: expected what desargues.board/compile-board! returns"
                    {:value (dissoc value :board/frame)
                     :required [:board/kernel :board/window :board/params :board/frame]}))))

(defn board
  "The :board content for `value`. opts: :height (px of the plot, inside
   Reveal's 960x700 box; default 330)."
  ([value] (board value {}))
  ([value opts]
   (merge {:plato/type :board
           :board (assert-board! value)
           :height 330}
          opts)))

;; ---------------------------------------------------------------------------
;; The static projection

(def ^:private plot-width 900)

(defn- grid [{:board/keys [window frame]}]
  (let [{[x0 x1] :x n :n} window]
    {:x0 x0 :h (/ (- x1 x0) (dec n)) :xs (double-array (:xs frame))}))

(defn- projector
  "World (x, y) -> SVG pixel coordinates, y up."
  [{:board/keys [window]} height]
  (let [{[x0 x1] :x [y0 y1] :y} window]
    (fn [[x y]]
      [(* plot-width (/ (- x x0) (- x1 x0)))
       (* height (/ (- y1 y) (- y1 y0)))])))

(defn- points-attr [project pts]
  (apply str (interpose " " (map (fn [p] (let [[sx sy] (project p)] (str sx "," sy))) pts))))

(defn- clamp-y [{:board/keys [window]} y]
  (let [[y0 y1] (:y window) pad (- y1 y0)]
    (max (- y0 pad) (min (+ y1 pad) y))))

(defn static-svg
  "The board at its initial state, as an SVG: axes, the area over the
   initial bounds, f' dashed, f, and the tangent at the initial probe. What a
   page without JavaScript shows, and what the island replaces."
  [{:keys [board height]}]
  (let [{:board/keys [window frame probes]} board
        project (projector board height)
        g (grid board)
        ys (double-array (:ys frame))
        dys (double-array (:dys frame))
        xs (:xs frame)
        [lo hi] (:area probes)
        p (:tangent probes)
        fp (geom/at g ys p) dfp (geom/at g dys p)
        [x0 x1] (:x window)
        tangent [[x0 (+ fp (* dfp (- x0 p)))] [x1 (+ fp (* dfp (- x1 p)))]]
        line (fn [pts] (points-attr project (map (fn [[x y]] [x (clamp-y board y)]) pts)))]
    [:svg.plato-board-static {:viewBox (str "0 0 " plot-width " " height)
                              :width "100%" :role "img"
                              :aria-label (str "Plot of " (:board/label board))}
     [:line {:x1 0 :x2 plot-width :y1 (second (project [0 0])) :y2 (second (project [0 0]))
             :class "axis"}]
     [:line {:x1 (first (project [0 0])) :x2 (first (project [0 0])) :y1 0 :y2 height
             :class "axis"}]
     [:polygon {:class "area" :points (points-attr project (geom/area-points g ys lo hi))}]
     [:polyline {:class "df" :points (line (map vector xs (:dys frame)))}]
     [:polyline {:class "f" :points (line (map vector xs (:ys frame)))}]
     [:polyline {:class "tangent" :points (line tangent)}]
     (let [[sx sy] (project [p fp])] [:circle {:class "probe" :cx sx :cy sy :r 7}])]))

(defmethod content/render :board [value]
  ;; The static plot, plus the board value itself as EDN in a data attribute,
  ;; minus the frame: the island recomputes every sample from the kernel, so
  ;; shipping them twice would only weigh the page down.
  [:div.plato-board {:data-plato-board (pr-str (update value :board dissoc :board/frame))}
   (static-svg value)
   [:p.plato-board-label (:board/label (:board value))]])
