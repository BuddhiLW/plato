(ns plato.board-island
  "The board island: every `[data-plato-board]` element becomes a live Mafs
   board over its WebAssembly kernel.

   The element carries a :board content value (plato.board) as EDN. hydrate
   reads it, instantiates the kernel once per URL, and mounts a board: drag
   the probe along f and its tangent follows, drag the two bounds on the axis
   and the shaded integral follows, move a slider and the kernel refills f,
   f' and the running integral over the whole window in linear memory. The
   board only reads those arrays back, through plato.board.geom, the same
   reading the static plot was drawn with.

   Same lifecycle as plato.scene-island: the attribute is removed on hydrate
   so a second call is a no-op, and the bundle hydrates itself on load when
   the document is already parsed."
  (:require ["mafs" :refer [Mafs Coordinates Polyline Polygon MovablePoint Line Theme]]
            [cljs.reader :as reader]
            [plato.board.geom :as geom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

;; ---------------------------------------------------------------------------
;; Kernels: one instance per wasm URL, shared by every board that names it.

(defonce ^:private kernels (atom {}))

(defn- load-kernel!
  "Promise of {:call fn :memory WebAssembly.Memory} for the board's kernel.
   Exports are read with ^js so :advanced leaves their names alone."
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
  "Run the kernel over the window and copy its four arrays out. Each board
   owns a slice of linear memory from `base` (bytes), so boards sharing a
   kernel never share scratch."
  [{:keys [call ^js memory]} base {[x0 x1] :x n :n} param-values]
  (let [stride (* 8 n)
        [px py pd pi] (map #(+ base (* % stride)) (range 4))
        h (/ (- x1 x0) (dec n))]
    (apply call px py pd pi n x0 h param-values)
    (let [m (js/Float64Array. (.-buffer memory))
          view (fn [p] (.slice m (/ p 8) (+ (/ p 8) n)))]
      {:xs (view px) :ys (view py) :dys (view pd) :iys (view pi) :h h :x0 x0})))

(defn- polyline [xs ys]
  (let [out (array)]
    (dotimes [i (alength xs)] (.push out #js [(aget xs i) (aget ys i)]))
    out))

;; ---------------------------------------------------------------------------
;; The board

(defn- fmt [x] (.toFixed (js/Number. x) 3))

(defn- color [k] (aget Theme (name k)))

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
  (let [{:board/keys [window params probes label]} board
        {[x0 x1] :x [y0 y1] :y} window
        [lo0 hi0] (:area probes)
        state (r/atom {:params (into {} (map (juxt :id :init)) params)
                       :p (:tangent probes) :lo lo0 :hi hi0})]
    (fn []
      (let [st @state
            {:keys [xs ys dys iys] :as g} (sample k base window (map #(get-in st [:params (:id %)]) params))
            p (:p st) fp (geom/at g ys p) dfp (geom/at g dys p)
            lo (min (:lo st) (:hi st)) hi (max (:lo st) (:hi st))]
        [:div.plato-board-live
         [:> Mafs {:height height :pan false :zoom false
                   :viewBox #js {:x #js [x0 x1] :y #js [y0 y1]}
                   :preserveAspectRatio false}
          [:> (.-Cartesian ^js Coordinates)]
          [:> Polygon {:points (clj->js (geom/area-points g ys lo hi))
                       :color (color :green) :fillOpacity 0.25 :weight 0}]
          [:> Polyline {:points (polyline xs dys) :color (color :pink) :weight 2
                        :strokeStyle "dashed"}]
          [:> Polyline {:points (polyline xs ys) :color (color :blue) :weight 3}]
          [:> (.-PointSlope ^js Line) {:point #js [p fp] :slope dfp
                                       :color (color :orange) :weight 2}]
          [:> MovablePoint {:point #js [p fp] :color (color :orange)
                            :constrain (fn [pt] #js [(aget pt 0) (geom/at g ys (aget pt 0))])
                            :onMove (fn [pt] (swap! state assoc :p (aget pt 0)))}]
          [:> MovablePoint {:point #js [lo 0] :color (color :green)
                            :constrain (fn [pt] #js [(aget pt 0) 0])
                            :onMove (fn [pt] (swap! state assoc :lo (aget pt 0)))}]
          [:> MovablePoint {:point #js [hi 0] :color (color :green)
                            :constrain (fn [pt] #js [(aget pt 0) 0])
                            :onMove (fn [pt] (swap! state assoc :hi (aget pt 0)))}]]
         [:div.plato-board-readout
          [:span {:style {:color (color :blue)}} label]
          [:span {:style {:color (color :orange)}} "f′(" (fmt p) ") = " (fmt dfp)]
          [:span {:style {:color (color :green)}}
           "∫ f dx over [" (fmt lo) ", " (fmt hi) "] = " (fmt (geom/integral g iys lo hi))]]
         (into [:div.plato-board-controls] (map #(slider state %)) params)]))))

;; ---------------------------------------------------------------------------
;; Hydration

(defonce ^:private next-base (atom 0))

(defn- claim-memory!
  "Byte offset of a fresh slice for a board of n samples (4 f64 arrays)."
  [n]
  (let [bytes (* 4 8 n)]
    (- (swap! next-base + bytes) bytes)))

(defn- mount! [^js el]
  (let [value (reader/read-string (.getAttribute el "data-plato-board"))
        base (claim-memory! (get-in value [:board :board/window :n]))]
    (.removeAttribute el "data-plato-board")
    (.setAttribute el "data-prevent-swipe" "")
    (-> (load-kernel! (get-in value [:board :board/kernel]))
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
