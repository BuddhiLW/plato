(ns plato.render
  "Pure scene-graph to SVG rendering."
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

(defn- line-el
  "A :line node: endpoints in world units, stroke from :stroke or :opts."
  [_g nd]
  (let [a (geo/point (:from nd))
        b (geo/point (:to nd))
        s (or (:stroke nd) {:color (get-in nd [:opts :color] :white)
                             :width (get-in nd [:opts :width] 3)})]
    [:line {:x1 (:x a) :y1 (:y a) :x2 (:x b) :y2 (:y b)
            :stroke (color/hex (:color s :white))
            :stroke-width (:width s 3)
            :stroke-opacity (:opacity s 1)
            :stroke-linecap "round"
            :fill "none"}]))
(defmethod node->hiccup :line   [g nd] (line-el g nd))

(defn- layout-root-box [g]
  (some (fn [[k value]]
          (when (= "box" (name k)) value))
        (:layout g)))

(defn- layout-origin [g]
  (if-let [{:keys [w h]} (layout-root-box g)]
    [(/ (- geo/frame-w w) 2.0)
     (/ (- geo/frame-h h) 2.0)]
    [0.0 0.0]))

(defn- point-of [g nd]
  (if-let [{:keys [x y w h]} (:box nd)]
    (let [[ox oy] (layout-origin g)]
      {:x (geo/->len (+ ox x (/ w 2.0)))
       :y (geo/->len (+ oy y (/ h 2.0)))})
    (geo/point (sc/resolve-at g nd))))

(defn- box-length [nd key option fallback]
  (if-let [value (get-in nd [:box key])]
    (geo/->len value)
    (geo/->len (get-in nd [:opts option] fallback))))

(defn- style-value [nd key fallback]
  (or (get-in nd [:style key])
      (get-in nd [:opts key])
      fallback))

(defn- circle-el [g nd]
  (let [{:keys [x y]} (point-of g nd)
        r (geo/->len (get-in nd [:opts :radius] 0.1))]
    [:circle (merge {:cx x :cy y :r r} (paint nd))]))

(defmethod node->hiccup :circle [g nd] (circle-el g nd))
(defmethod node->hiccup :dot    [g nd] (circle-el g nd))

(defn- rect-el [g nd]
  (let [{:keys [x y]} (point-of g nd)
        w  (box-length nd :w :width 1)
        h  (box-length nd :h :height 1)
        rx (geo/->len (get-in nd [:opts :corner-radius] 0))]
    [:rect (merge {:x (- x (/ w 2)) :y (- y (/ h 2)) :width w :height h :rx rx}
                  (paint nd))]))

(defmethod node->hiccup :rectangle         [g nd] (rect-el g nd))
(defmethod node->hiccup :rounded-rectangle [g nd] (rect-el g nd))

(defn- text-el [g nd content]
  (let [{:keys [x y]} (point-of g nd)
        fs    (style-value nd :font-size 24)
        col   (color/hex (style-value nd :color :white))
        weight (style-value nd :weight "NORMAL")
        bold? (or (= "BOLD" weight) (= :bold weight))]
    [:text {:x x :y y :text-anchor "middle" :dominant-baseline "central"
            :font-size fs :fill col
            :font-weight (if bold? "700" "400")
            :font-family "system-ui, sans-serif"}
     content]))

(defmethod node->hiccup :text [g nd]
  (text-el g nd (or (:text nd) (:content nd) "")))

(defmethod node->hiccup :math [g nd]
  (text-el g nd (or (:content nd) (:text nd) "")))

(defmethod node->hiccup :decimal [g nd]
  (let [dp (get-in nd [:opts :num-decimal-places] 0)]
    (text-el g nd (fmt/fixed (:value nd 0) dp))))

(defmethod node->hiccup :image [g nd]
  (let [{:keys [x y]} (point-of g nd)
        w (box-length nd :w :width 1)
        h (box-length nd :h :height 1)]
    [:image {:href (:content nd)
             :x (- x (/ w 2.0))
             :y (- y (/ h 2.0))
             :width w
             :height h
             :preserveAspectRatio "xMidYMid meet"}]))

(defmethod node->hiccup :default [_g nd]
  [:g {:data-unknown (str (:node nd))}])

;; ── animated-attrs -> SVG (the IRenderTarget/-apply seam made real) ─────────
;; Animated channels are translated to SVG attributes here.

(defn- center-of
  "Element pivot [cx cy] in SVG px, for scale-about-center."
  [tag a]
  (case tag
    :circle [(:cx a) (:cy a)]
    :rect   [(+ (:x a) (/ (:width a) 2.0)) (+ (:y a) (/ (:height a) 2.0))]
    :text   [(:x a) (:y a)]
    :line   [(/ (+ (:x1 a) (:x2 a)) 2.0) (/ (+ (:y1 a) (:y2 a)) 2.0)]
    :image  [(+ (:x a) (/ (:width a) 2.0))
             (+ (:y a) (/ (:height a) 2.0))]
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
  "Direct-value channels: opacity/fill/stroke, plus the draw reveal and a
   line's endpoints. `contains?` (not truthiness) so opacity 0.0 still applies."
  [a attrs]
  (cond-> a
    (contains? attrs :opacity)   (assoc :opacity (:opacity attrs))
    (contains? attrs :fill)      (assoc :fill    (:fill attrs))
    (contains? attrs :stroke)    (assoc :stroke  (:stroke attrs))
    (contains? attrs :draw)      (merge (draw-attrs (:draw attrs)))
    (contains? attrs :endpoints) (merge (zipmap [:x1 :y1 :x2 :y2] (:endpoints attrs)))))

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

(defn- node-group
  [target g frame id]
  [:g {:key (str id)}
   (p/-apply target
             (p/-element target g (sc/node g id))
             (get frame id {}))])

(defn scene-svg
  "Hiccup <svg> for graph g at frame, drawing node-ids in order through target."
  [target g frame node-ids]
  (into
   [:svg {:viewBox (str "0 0 " geo/view-w " " geo/view-h)
          :preserveAspectRatio "xMidYMid meet"
          :role "img"
          :aria-label (str (sc/scene-name g))}]
   (map #(node-group target g frame %) node-ids)))
