(ns plato.board.view
  "Pure layer of the board seam: the camera of a 3D board.

   A board with :board/view names its points by three output keys,
   [kx ky kz], world coordinates the kernel computed. `project-board` turns
   every such point into a screen pair of fresh output keys at the current
   camera, so every 2D layer draws a 3D figure unchanged, and gives each
   drawn layer :depth, the outputs of its points' depths, for `paint-order`.

     :board/view {:yaw :yaw :pitch :pitch :scale 1 :perspective 9}

   yaw and pitch are numbers or param ids; z is up, yaw turns about it and
   pitch tilts the world toward the viewer. Without :perspective the
   projection is orthographic.

   Host-free: runs under clj, cljw and cljs alike.")

(defn- cos* [x] #?(:cljs (js/Math.cos x) :default (Math/cos x)))
(defn- sin* [x] #?(:cljs (js/Math.sin x) :default (Math/sin x)))

(defn- new-array [n] #?(:cljs (js/Float64Array. n) :default (double-array n)))

(defn camera
  "view, param values {id value} -> (fn [x y z] -> [sx sy depth]), depth
   larger farther."
  [{:keys [yaw pitch scale perspective] :or {yaw 0.6 pitch 0.35 scale 1}} params]
  (let [value (fn [v] (if (keyword? v) (get params v 0) v))
        yw (value yaw) pt (value pitch)
        cy (cos* yw) sy (sin* yw) cp (cos* pt) sp (sin* pt)]
    (fn [x y z]
      (let [x1 (- (* cy x) (* sy y))
            y1 (+ (* sy x) (* cy y))
            d (- (* cp y1) (* sp z))
            z2 (+ (* sp y1) (* cp z))
            k (if perspective (* scale (/ perspective (+ perspective d))) scale)]
        [(* k x1) (* k z2) d]))))

(def ^:private point-fields
  "Layer fields that name one point."
  [:at :a :b :of])

(def ^:private points-fields
  "Layer fields that name a sequence of points."
  [:pts :along :around])

(defn- triple? [v] (and (vector? v) (= 3 (count v)) (every? keyword? v)))

(defn- screen-keys
  "The output keys a projected point [kx ky kz] is drawn by: [sx sy depth]."
  [[kx]]
  (mapv #(keyword (str (name kx) %)) ["-sx" "-sy" "-d"]))

(defn- named-points
  "Every 3D point layer l names, through the fields given."
  [l fields]
  (for [f fields
        :let [v (get l f)]
        p (if (some #{f} points-fields) v [v])
        :when (triple? p)]
    p))

(defn project-board
  "layers, arrays {output array}, view, param values -> {:layers :arrays}
   with every 3D point projected: a screen pair of new output keys whose
   arrays are added, and :depth on each drawn layer (not handles)."
  [layers arrays view params]
  (let [cam (camera view params)
        triples (distinct (mapcat #(named-points % (concat point-fields points-fields)) layers))
        arrays' (reduce
                 (fn [acc [kx ky kz :as t]]
                   (let [xs (get arrays kx) ys (get arrays ky) zs (get arrays kz)
                         n (alength xs)
                         [ksx ksy kd] (screen-keys t)
                         sx (new-array n) sy (new-array n) d (new-array n)]
                     (dotimes [i n]
                       (let [[a b c] (cam (aget xs i) (aget ys i) (aget zs i))]
                         (aset sx i a) (aset sy i b) (aset d i c)))
                     (assoc acc ksx sx ksy sy kd d)))
                 arrays triples)
        pair (fn [p] (if (triple? p) (subvec (screen-keys p) 0 2) p))
        flat (fn [l]
               (let [ds (map #(nth (screen-keys %) 2) (named-points l [:at :a :b :pts]))
                     l' (reduce (fn [l f]
                                  (if (contains? l f)
                                    (update l f #(if (some #{f} points-fields) (mapv pair %) (pair %)))
                                    l))
                                l (concat point-fields points-fields))]
                 (cond-> l' (and (seq ds) (not= :handle (:layer l))) (assoc :depth (vec ds)))))]
    {:layers (mapv flat layers) :arrays arrays'}))

(defn- depth-of [arrays {ks :depth}]
  (/ (reduce + (map #(aget (get arrays %) 0) ks)) (count ks)))

(defn paint-order
  "layers in painter's order: the layers with :depth sorted far to near,
   after any that precede the first of them; the rest (handles, readouts)
   after, in their own order."
  [layers arrays]
  (let [[pre post] (split-with (complement :depth) layers)]
    (if (empty? post)
      layers
      (concat pre
              (sort-by #(- (depth-of arrays %)) (filter :depth post))
              (remove :depth post)))))
