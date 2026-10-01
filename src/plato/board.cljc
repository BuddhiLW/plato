(ns plato.board
  "Board contract, the :board content constructor, and the STATIC projection
   of that content kind: the seam where an interactive board, as
   desargues.board compiles it, becomes a slide.

     plato.board.geom    Pure      reading a sampled grid (host-free)
     plato.board.layer   Pure      static-layer, open on :layer (host-free)
     plato.board         Seam      contract, constructor, static projection
     plato.board-island  Boundary  the live board: fetch, wasm, DOM

   plato knows no calculus and no board kinds. A board arrives solved: a
   WebAssembly kernel (URL + export), the arrays it fills (:board/outputs),
   what to draw (:board/layers, as data), the window, the slider params, the
   probes, and :board/frame, the kernel's own samples at the initial params.
   From the frame plato draws a static plot, so a page without JavaScript
   still shows the right figure; the value rides in a data attribute, and a
   page that carries the board bundle hydrates it into a live board calling
   the kernel on every drag. Same shape as plato.desargues and the scenes.

   Kernel ABI, from desargues.board.kernel:
     (export out1 .. outk n x0 h p1 .. pm) -> n
   one f64 array of length n per output, at byte offsets in the module's
   `memory`, in :board/outputs order; params in :board/params order."
  (:require [plato.board.layer :as layer]
            [plato.board.view :as view]
            [plato.content :as content]))

;; ---------------------------------------------------------------------------
;; Contract: one named predicate per value object

(defn kernel-ref? [k]
  (and (map? k) (string? (:wasm k)) (string? (:export k))))

(defn window?
  "n = 1 is one configuration per call (a figure); a sweep has more."
  [{[x0 x1] :x [y0 y1] :y n :n}]
  (and (number? x0) (number? x1) (< x0 x1)
       (number? y0) (number? y1) (< y0 y1)
       (integer? n) (pos? n)))

(defn param? [{:keys [id min max init]}]
  (and (keyword? id) (number? min) (number? max) (number? init) (<= min init max)))

(defn layer? [l]
  (and (map? l) (keyword? (:layer l))))

(defn board?
  "Is value a Board as desargues.board produces it?"
  [value]
  (let [{:board/keys [kernel window params outputs layers frame]} value]
    (boolean
     (and (map? value)
          (kernel-ref? kernel)
          (window? window)
          (vector? params) (every? param? params)
          (vector? outputs) (seq outputs) (every? keyword? outputs)
          (vector? layers) (every? layer? layers)
          (every? #(= (:n window) (count (get frame %))) outputs)))))

(defn assert-board! [value]
  (if (board? value)
    value
    (throw (ex-info "Invalid board: expected what desargues.board/compile-board! returns"
                    {:value (dissoc value :board/frame)
                     :required [:board/kernel :board/window :board/params
                                :board/outputs :board/layers :board/frame]}))))

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

(defn- context
  "What every static layer reads: the grid, the frame as arrays, and the
   world -> SVG projection. A one-configuration board (n = 1, a figure) has
   no step: h is 0."
  [{:board/keys [window frame outputs probes]} height]
  (let [{[x0 x1] :x [y0 y1] :y n :n} window
        pad (- y1 y0)]
    {:grid {:x0 x0 :h (if (> n 1) (/ (- x1 x0) (dec n)) 0)
            :xs (double-array (:xs frame (get frame (first outputs))))}
     :arrays (into {} (map (fn [k] [k (double-array (get frame k))])) outputs)
     :project (fn [[x y]] [(* plot-width (/ (- x x0) (- x1 x0)))
                           (* height (/ (- y1 y) (- y1 y0)))])
     :clamp (fn [y] (max (- y0 pad) (min (+ y1 pad) y)))
     :probes probes
     :window window}))

(defn static-svg
  "The board at its initial state: axes, then every layer that has a
   static-layer method, in :board/layers order. What a page without
   JavaScript shows, and what the island replaces."
  [{:keys [board height]}]
  (let [{:keys [project] :as ctx} (context board height)
        {:board/keys [window params layers]} board
        ;; A 3D board's points are projected at the params' initial camera.
        {:keys [layers arrays]} (if-let [v (:board/view board)]
                                  (view/project-board layers (:arrays ctx) v
                                                      (into {} (map (juxt :id :init)) params))
                                  {:layers layers :arrays (:arrays ctx)})
        ctx (assoc ctx :arrays arrays)
        [ox oy] (project [0 0])]
    (-> [:svg.plato-board-static {:viewBox (str "0 0 " plot-width " " height)
                                  :width "100%" :role "img"
                                  :aria-label (str "Plot of " (:board/label board))}]
        (into (when-not (= :none (:axes window))
                [[:line {:class "axis" :x1 0 :x2 plot-width :y1 oy :y2 oy}]
                 [:line {:class "axis" :x1 ox :x2 ox :y1 0 :y2 height}]]))
        (into (keep #(layer/static-layer % ctx))
              (view/paint-order layers arrays)))))

(defn math-lines
  "The board's :board/math as display lines for the page's KaTeX: each TeX
   line wrapped in \\( \\), which Reveal's math plugin typesets. Kind-neutral:
   plato never asks which kind of board wrote them."
  [board]
  (when-let [lines (seq (:board/math board))]
    (into [:div.plato-board-math]
          (map (fn [tex] [:span.plato-board-eq (str "\\(" tex "\\)")]))
          lines)))

(defmethod content/render :board [value]
  ;; The static plot, plus the board value itself as EDN in a data attribute,
  ;; minus the frame: the island recomputes every sample from the kernel, so
  ;; shipping them twice would only weigh the page down.
  ;;
  ;; The math sits OUTSIDE [data-plato-board]: the island empties that element
  ;; when it mounts, and KaTeX typesets the page once, at Reveal's start.
  (cond-> [:div.plato-board-figure
           [:div.plato-board {:data-plato-board (pr-str (update value :board dissoc :board/frame))}
            (static-svg value)
            [:p.plato-board-label (:board/label (:board value))]]]
    (seq (:board/math (:board value))) (conj (math-lines (:board value)))))
