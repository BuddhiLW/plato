(ns plato.board.layer
  "Pure layer of the board seam: how each layer of a board is drawn in the
   static plot.

   A board carries its layers as data, e.g.
     {:layer :curve :of :ys :color :blue}
     {:layer :tangent :of :ys :slope :dys :probe :tangent}
   and `static-layer` is OPEN on :layer: a new kind of layer is a defmethod
   (here for the static plot, and plato.board-island/live-layer for the live
   board), never an edit to plato.board. A layer kind nobody registered draws
   nothing, so an older plato renders a newer board without failing.

   Host-free: runs under clj, cljw and cljs alike."
  (:require [plato.board.geom :as geom]))

(def palette
  "Layer :color keywords as CSS colors, matched to Mafs' Theme so the static
   plot and the live board read as the same figure."
  {:blue "#58a6ff" :pink "#ff5cb6" :green "#1cd14b" :orange "#ff8a3d"
   :red "#f24f4f" :yellow "#ffe45c" :violet "#ae81ff" :indigo "#6f5cff"})

(defn color [k default] (get palette k (get palette default)))

(defn points-attr [project pts]
  (apply str (interpose " " (map (fn [p] (let [[sx sy] (project p)] (str sx "," sy))) pts))))

(defmulti static-layer
  "layer, ctx -> hiccup or nil. ctx:
     :grid     {:x0 :h :xs} over the frame (plato.board.geom)
     :arrays   {output-keyword double-array}
     :project  world [x y] -> SVG [px py]
     :clamp    y -> y kept near the window, so off-scale samples stay drawable
     :probes   {probe-keyword value} at their initial positions
     :window   the board's window"
  (fn [layer _ctx] (:layer layer)))

(defmethod static-layer :default [_ _] nil)

(defmethod static-layer :curve [{:keys [of style] :as layer} {:keys [grid arrays project clamp]}]
  (let [ys (get arrays of) xs (:xs grid)]
    [:polyline {:fill "none"
                :stroke (color (:color layer) :blue)
                :stroke-width (if (= style :dashed) 2 3)
                :stroke-dasharray (when (= style :dashed) "6 5")
                :points (points-attr project (map (fn [i] [(aget xs i) (clamp (aget ys i))])
                                                  (range (alength xs))))}]))

(defmethod static-layer :area [{:keys [of probe] :as layer} {:keys [grid arrays project probes]}]
  (let [[lo hi] (get probes probe)]
    [:polygon {:fill (color (:color layer) :green) :fill-opacity 0.25 :stroke "none"
               :points (points-attr project (geom/area-points grid (get arrays of) lo hi))}]))

(defmethod static-layer :tangent [{:keys [of slope probe] :as layer} {:keys [grid arrays project clamp probes window]}]
  (let [p (get probes probe)
        fp (geom/at grid (get arrays of) p)
        m (geom/at grid (get arrays slope) p)
        [x0 x1] (:x window)
        c (color (:color layer) :orange)
        [sx sy] (project [p fp])]
    [:g
     [:polyline {:fill "none" :stroke c :stroke-width 2
                 :points (points-attr project [[x0 (clamp (+ fp (* m (- x0 p))))]
                                               [x1 (clamp (+ fp (* m (- x1 p))))]])}]
     [:circle {:cx sx :cy sy :r 7 :fill c}]]))
