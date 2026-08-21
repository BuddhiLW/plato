(ns plato.render
  "L3-but-pure: scene nodes -> SVG hiccup (plain data, no reagent dep -> cljc,
   runs in the browser AND server-side for snapshots). An SvgTarget record
   implements the IRenderTarget seam by delegating to the node->hiccup
   multimethod (OCP: a new node kind is a new defmethod)."
  (:require [plato.protocols :as p]
            [plato.color :as color]
            [plato.geometry :as geo]
            [plato.scene :as sc]
            [plato.fmt :as fmt]
            [clojure.string :as str]))

(defn- fill-of [nd]
  (or (:fill nd)
      (when-let [c (get-in nd [:opts :color])] {:color c :opacity 1})))

(defn- paint
  "SVG fill/stroke attrs from a node's :fill / :stroke / :opts :color."
  [nd]
  (let [f (fill-of nd) s (:stroke nd)]
    (cond-> {}
      f        (assoc :fill (color/hex (:color f)) :fill-opacity (:opacity f 1))
      (nil? f) (assoc :fill "none")
      s        (assoc :stroke (color/hex (:color s))
                      :stroke-width (:width s 1)
                      :stroke-opacity (:opacity s 1)))))

(defmulti node->hiccup
  "Static SVG hiccup for a scene node (dispatch on :node)."
  (fn [_g nd] (:node nd)))

(defn- circle-el [g nd]
  (let [{:keys [x y]} (geo/point (sc/resolve-at g nd))
        r (geo/->len (get-in nd [:opts :radius] 0.1))]
    [:circle (merge {:cx x :cy y :r r} (paint nd))]))

(defmethod node->hiccup :circle [g nd] (circle-el g nd))
(defmethod node->hiccup :dot    [g nd] (circle-el g nd))

(defn- rect-el [g nd]
  (let [{:keys [x y]} (geo/point (sc/resolve-at g nd))
        w  (geo/->len (get-in nd [:opts :width] 1))
        h  (geo/->len (get-in nd [:opts :height] 1))
        rx (geo/->len (get-in nd [:opts :corner-radius] 0))]
    [:rect (merge {:x (- x (/ w 2)) :y (- y (/ h 2)) :width w :height h :rx rx}
                  (paint nd))]))

(defmethod node->hiccup :rectangle         [g nd] (rect-el g nd))
(defmethod node->hiccup :rounded-rectangle [g nd] (rect-el g nd))

(defn- text-el [g nd content]
  (let [{:keys [x y]} (geo/point (sc/resolve-at g nd))
        fs    (get-in nd [:opts :font-size] 24)
        col   (color/hex (get-in nd [:opts :color] :white))
        bold? (= "BOLD" (get-in nd [:opts :weight]))]
    [:text {:x x :y y :text-anchor "middle" :dominant-baseline "central"
            :font-size fs :fill col
            :font-weight (if bold? "700" "400")
            :font-family "system-ui, sans-serif"}
     content]))

(defmethod node->hiccup :text [g nd] (text-el g nd (:text nd)))
(defmethod node->hiccup :decimal [g nd]
  (let [dp (get-in nd [:opts :num-decimal-places] 0)]
    (text-el g nd (fmt/fixed (:value nd 0) dp))))

(defmethod node->hiccup :default [_g nd]
  [:g {:data-unknown (str (:node nd))}])

;; ── animated-attrs -> SVG (the IRenderTarget/-apply seam made real) ─────────
;; Stratified low -> high: pivots & string builders -> channel mergers ->
;; apply-attrs. -sample speaks in semantic channels; this layer is the ONLY
;; place that knows their SVG spelling.

(defn- center-of
  "Element pivot [cx cy] in SVG px, for scale-about-center."
  [tag a]
  (case tag
    :circle [(:cx a) (:cy a)]
    :rect   [(+ (:x a) (/ (:width a) 2.0)) (+ (:y a) (/ (:height a) 2.0))]
    :text   [(:x a) (:y a)]
    [0 0]))

(defn- translate-str [[dx dy]] (str "translate(" dx "," dy ")"))
(defn- scale-str     [s cx cy] (str "translate(" cx "," cy ") scale(" s
                                    ") translate(" (- cx) "," (- cy) ")"))

(defn- transform-str
  "Assemble the SVG transform= value from the :translate and :scale channels."
  [{:keys [translate scale]} [cx cy]]
  (->> [(when translate (translate-str translate))
        (when scale (scale-str scale cx cy))]
       (remove nil?)
       (str/join " ")))

(defn- draw-attrs
  "Normalized stroke reveal (pathLength=1 so it is shape-perimeter independent)."
  [reveal]
  {:pathLength 1 :stroke-dasharray 1 :stroke-dashoffset (- 1.0 reveal)})

(defn- merge-paint
  "Direct-value channels: opacity/fill/stroke, plus the draw reveal. `contains?`
   (not truthiness) so opacity 0.0 still applies."
  [a attrs]
  (cond-> a
    (contains? attrs :opacity) (assoc :opacity (:opacity attrs))
    (contains? attrs :fill)    (assoc :fill    (:fill attrs))
    (contains? attrs :stroke)  (assoc :stroke  (:stroke attrs))
    (contains? attrs :draw)    (merge (draw-attrs (:draw attrs)))))

(defn- merge-transform [a attrs center]
  (let [tf (transform-str attrs center)]
    (cond-> a (seq tf) (assoc :transform tf))))

(defn apply-attrs
  "Return hiccup element `el` with animated `attrs` (a channel map) merged in.
   :text swaps the text child (count-to); everything else merges attributes."
  [[tag a & children :as el] attrs]
  (if (empty? attrs)
    el
    (let [a' (-> a (merge-paint attrs) (merge-transform attrs (center-of tag a)))]
      (if (contains? attrs :text)
        [tag a' (:text attrs)]
        (into [tag a'] children)))))

(defrecord SvgTarget []
  p/IRenderTarget
  (-element [_ g nd] (node->hiccup g nd))
  (-apply  [_ el attrs] (apply-attrs el attrs)))

(defn svg-target [] (->SvgTarget))

(defn element
  "Hiccup element for a node via a render target (the IRenderTarget seam)."
  [target g nd]
  (p/-element target g nd))
