(ns plato.board-island
  "Boundary of the board seam: every `[data-plato-board]` element becomes a
   live Mafs board over its WebAssembly kernel.

   The element carries a :board content value (plato.board) as EDN. hydrate
   reads it, instantiates the kernel once per URL, and mounts a board. Each
   render calls the kernel once, filling every :board/outputs array over the
   window in linear memory, then draws :board/layers through `live-layer` and
   writes their `readout`s. Both are OPEN on :layer, mirroring
   plato.board.layer/static-layer: a new kind of layer is a defmethod on each
   side, never an edit here. A layer kind with no method draws nothing.

   Probes (the draggable points a layer names with :probe) and slider params
   are the only state. Same lifecycle as plato.scene-island: the attribute is
   removed on hydrate so a second call is a no-op, and the bundle hydrates
   itself on load when the document is already parsed."
  (:require ["mafs" :refer [Mafs Coordinates Polyline Polygon MovablePoint Line Theme]]
            [cljs.reader :as reader]
            [plato.board.geom :as geom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

;; ---------------------------------------------------------------------------
;; Kernels: one instance per wasm URL, shared by every board that names it.

(defonce ^:private kernels (atom {}))

(defn- load-kernel!
  "Promise of {:call fn :memory WebAssembly.Memory} for a KernelRef. Exports
   are read with ^js so :advanced leaves their names alone."
  [{:keys [wasm export]}]
  (or (get @kernels wasm)
      (let [p (-> (js/fetch wasm)
                  (.then (fn [^js r] (.arrayBuffer r)))
                  (.then #(js/WebAssembly.instantiate % #js {}))
                  (.then (fn [^js res]
                           (let [^js exports (.. res -instance -exports)]
                             {:call (aget exports export)
                              :memory (.-memory exports)}))))]
        (swap! kernels assoc wasm p)
        p)))

(defn- sample
  "Call the kernel over the window and copy every output out:
   {:grid {:x0 :h :xs} :arrays {output Float64Array}}. The board owns the
   memory slice from `base`, so boards sharing a kernel never share scratch."
  [{:keys [call ^js memory]} base outputs {[x0 x1] :x n :n} param-values]
  (let [stride (* 8 n)
        ptrs (map #(+ base (* % stride)) (range (count outputs)))
        h (/ (- x1 x0) (dec n))]
    (apply call (concat ptrs [n x0 h] param-values))
    (let [m (js/Float64Array. (.-buffer memory))
          arrays (zipmap outputs (map (fn [p] (.slice m (/ p 8) (+ (/ p 8) n))) ptrs))]
      {:grid {:x0 x0 :h h :xs (get arrays :xs (get arrays (first outputs)))}
       :arrays arrays})))

(defn- polyline [xs ys]
  (let [out (array)]
    (dotimes [i (alength xs)] (.push out #js [(aget xs i) (aget ys i)]))
    out))

(defn- theme [k default] (or (aget Theme (name (or k default))) (aget Theme (name default))))

(defn- fmt [x] (.toFixed (js/Number. x) 3))

;; ---------------------------------------------------------------------------
;; The open set of layers

(defmulti live-layer
  "layer, ctx -> a Mafs element, a seq of them, or nil. ctx:
     :grid :arrays   the kernel's samples this render
     :probes         {probe-keyword value}
     :move!          (fn [probe value]) to move a probe"
  (fn [layer _ctx] (:layer layer)))

(defmethod live-layer :default [_ _] nil)

(defmulti readout
  "layer, ctx -> a readout element or nil. Same ctx as live-layer."
  (fn [layer _ctx] (:layer layer)))

(defmethod readout :default [_ _] nil)

(defmethod live-layer :curve [{:keys [of style] :as layer} {:keys [grid arrays]}]
  [:> Polyline {:points (polyline (:xs grid) (get arrays of))
                :color (theme (:color layer) :blue)
                :weight (if (= style :dashed) 2 3)
                :strokeStyle (if (= style :dashed) "dashed" "solid")}])

(defmethod live-layer :area [{:keys [of probe] :as layer} {:keys [grid arrays probes move!]}]
  (let [[lo hi] (sort (get probes probe))
        c (theme (:color layer) :green)]
    (list
     [:> Polygon {:points (clj->js (geom/area-points grid (get arrays of) lo hi))
                  :color c :fillOpacity 0.25 :weight 0}]
     [:> MovablePoint {:point #js [lo 0] :color c
                       :constrain (fn [pt] #js [(aget pt 0) 0])
                       :onMove (fn [pt] (move! probe [(aget pt 0) hi]))}]
     [:> MovablePoint {:point #js [hi 0] :color c
                       :constrain (fn [pt] #js [(aget pt 0) 0])
                       :onMove (fn [pt] (move! probe [lo (aget pt 0)]))}])))

(defmethod live-layer :tangent [{:keys [of slope probe] :as layer} {:keys [grid arrays probes move!]}]
  (let [ys (get arrays of)
        p (get probes probe)
        fp (geom/at grid ys p)
        c (theme (:color layer) :orange)]
    (list
     [:> (.-PointSlope ^js Line) {:point #js [p fp] :slope (geom/at grid (get arrays slope) p)
                                  :color c :weight 2}]
     [:> MovablePoint {:point #js [p fp] :color c
                       :constrain (fn [pt] #js [(aget pt 0) (geom/at grid ys (aget pt 0))])
                       :onMove (fn [pt] (move! probe (aget pt 0)))}])))

(defmethod readout :tangent [{:keys [slope probe] :as layer} {:keys [grid arrays probes]}]
  (let [p (get probes probe)]
    [:span {:style {:color (theme (:color layer) :orange)}}
     (or (:name layer) "slope") " at " (fmt p) " = " (fmt (geom/at grid (get arrays slope) p))]))

(defmethod readout :integral [{:keys [of probe] :as layer} {:keys [grid arrays probes]}]
  (let [[lo hi] (sort (get probes probe))]
    [:span {:style {:color (theme (:color layer) :green)}}
     "∫ f dx over [" (fmt lo) ", " (fmt hi) "] = " (fmt (geom/integral grid (get arrays of) lo hi))]))

;; ---------------------------------------------------------------------------
;; The board

(defn- slider [state {:keys [id label min max step]}]
  [:label.plato-board-slider
   [:span (or label (name id)) " = " (fmt (get-in @state [:params id]))]
   [:input {:type "range" :min min :max max :step (or step (/ (- max min) 200))
            :value (get-in @state [:params id])
            ;; Arrow keys on a focused slider move the slider, not the deck.
            :on-key-down #(.stopPropagation %)
            :on-change #(swap! state assoc-in [:params id]
                               (js/parseFloat (.. % -target -value)))}]])

(defn- board-view [k base {:keys [board height]}]
  (let [{:board/keys [window params probes outputs layers label]} board
        {[x0 x1] :x [y0 y1] :y} window
        state (r/atom {:params (into {} (map (juxt :id :init)) params)
                       :probes probes})
        move! (fn [probe v] (swap! state assoc-in [:probes probe] v))]
    (fn []
      (let [st @state
            ctx (assoc (sample k base outputs window (map #(get-in st [:params (:id %)]) params))
                       :probes (:probes st) :move! move!)]
        [:div.plato-board-live
         (into [:> Mafs {:height height :pan false :zoom false
                         :viewBox #js {:x #js [x0 x1] :y #js [y0 y1]}
                         :preserveAspectRatio false}
                [:> (.-Cartesian ^js Coordinates)]]
               (mapcat (fn [l] (let [e (live-layer l ctx)] (if (seq? e) e (when e [e])))))
               layers)
         (into [:div.plato-board-readout [:span label]] (keep #(readout % ctx)) layers)
         (into [:div.plato-board-controls] (map #(slider state %)) params)]))))

;; ---------------------------------------------------------------------------
;; Hydration

(defonce ^:private next-base (atom 0))

(defn- claim-memory!
  "Byte offset of a fresh slice for k arrays of n doubles."
  [k n]
  (let [bytes (* k 8 n)]
    (- (swap! next-base + bytes) bytes)))

(defn- mount! [^js el]
  (let [value (reader/read-string (.getAttribute el "data-plato-board"))
        {:board/keys [kernel window outputs]} (:board value)
        base (claim-memory! (count outputs) (:n window))]
    (.removeAttribute el "data-plato-board")
    (.setAttribute el "data-prevent-swipe" "")
    (-> (load-kernel! kernel)
        (.then (fn [k]
                 (set! (.-innerHTML el) "")
                 (rdc/render (rdc/create-root el) [board-view k base value]))))))

(defn ^:export hydrate []
  (doseq [el (array-seq (.querySelectorAll js/document "[data-plato-board]"))]
    (mount! el)))

(defonce ^:private _boot
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" hydrate)
    (hydrate)))
