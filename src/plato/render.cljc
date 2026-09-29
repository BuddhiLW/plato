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
  [g nd]
  (let [fr (sc/frame g)
        a (geo/point fr (:from nd))
        b (geo/point fr (:to nd))
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
    (let [[fw fh] (sc/frame g)]
      [(/ (- fw w) 2.0)
       (/ (- fh h) 2.0)])
    [0.0 0.0]))

(defn- point-of [g nd]
  (if-let [{:keys [x y w h]} (:box nd)]
    (let [[ox oy] (layout-origin g)]
      {:x (geo/->len (+ ox x (/ w 2.0)))
       :y (geo/->len (+ oy y (/ h 2.0)))})
    (geo/point (sc/frame g) (sc/resolve-at g nd))))

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
        bold? (or (= "BOLD" weight) (= :bold weight))
        ;; A deck that renders to a file, rather than into a page that carries
        ;; its own stylesheet, has nowhere else to say which face to set: an
        ;; exported SVG is read by a rasteriser, not by a browser with a
        ;; :theme-css. The default is unchanged, so every existing deck still
        ;; resolves against the host's UI font.
        family (style-value nd :font-family "system-ui, sans-serif")
        ;; Every other node fades through its :fill opacity. A line of text
        ;; that cannot is a line that can only ever be foreground, which is
        ;; wrong the moment a scene sets type behind its subject.
        alpha (or (get-in nd [:fill :opacity]) (style-value nd :opacity 1))
        anchor (get {:start "start" :end "end" :middle "middle"
                     "start" "start" "end" "end" "middle" "middle"}
                    (style-value nd :anchor :middle)
                    "middle")]
    [:text {:x x :y y :text-anchor anchor :dominant-baseline "central"
            :font-size fs :fill col :fill-opacity alpha
            :font-weight (if bold? "700" "400")
            :font-family family}
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

(def ^:private tau 6.283185307179586)

(defn- sqrt
  "Newton's square root. Plain arithmetic, so every host plato runs on
   computes the same double: `Math/sqrt` is not a static on every reader
   this file is compiled under."
  [x]
  (let [x (double x)]
    (if (<= x 0.0)
      0.0
      (loop [g (max 1.0 x) i 0]
        (let [g' (* 0.5 (+ g (/ x g)))]
          (if (or (= g' g) (= i 64)) g' (recur g' (inc i))))))))

(defn- stroke-length
  "Perimeter of SVG element `a` in user units, or nil when `tag` has no closed
   form here. A rounded rect loses (8 - 2pi) rx over its four corners."
  [tag a]
  (case tag
    :rect   (let [w (double (:width a)) h (double (:height a))
                  rx (min (double (or (:rx a) 0)) (/ w 2.0) (/ h 2.0))]
              (- (* 2.0 (+ w h)) (* (- 8.0 tau) rx)))
    :circle (* tau (double (:r a)))
    :line   (let [dx (- (:x2 a) (:x1 a)) dy (- (:y2 a) (:y1 a))]
              (sqrt (+ (* dx dx) (* dy dy))))
    nil))

(defn- draw-attrs
  "Stroke reveal for element `a`, dashed against its own perimeter.

   The dash is the shape's real length rather than `pathLength=1`: browsers
   honour pathLength, but resvg and other rasterisers ignore it and draw a
   1-unit dash, so a revealed outline came out dotted in every video frame.
   A finished reveal carries no dash at all, which is the plain stroke both
   kinds of reader agree on. Tags with no closed-form length keep pathLength."
  [tag a reveal]
  (let [reveal (double reveal)]
    (if (>= reveal 1.0)
      {}
      (if-let [len (stroke-length tag a)]
        {:stroke-dasharray len :stroke-dashoffset (* len (- 1.0 reveal))}
        {:pathLength 1 :stroke-dasharray 1 :stroke-dashoffset (- 1.0 reveal)}))))

(defn- merge-paint
  "Direct-value channels: opacity/fill/stroke, plus the draw reveal and a
   line's endpoints. `contains?` (not truthiness) so opacity 0.0 still applies.
   The reveal is measured last, against the endpoints this frame moved to."
  [tag a attrs]
  (let [a (cond-> a
            (contains? attrs :opacity)   (assoc :opacity (:opacity attrs))
            (contains? attrs :fill)      (assoc :fill    (:fill attrs))
            (contains? attrs :stroke)    (assoc :stroke  (:stroke attrs))
            (contains? attrs :endpoints) (merge (zipmap [:x1 :y1 :x2 :y2] (:endpoints attrs))))]
    (cond-> a
      (contains? attrs :draw) (merge (draw-attrs tag a (:draw attrs))))))

(defn- merge-transform [a attrs center]
  (let [tf (transform-str attrs center)]
    (cond-> a (seq tf) (assoc :transform tf))))

(defn apply-attrs
  "Return hiccup element `el` with animated `attrs` (a channel map) merged in.
   :text swaps the text child (count-to); everything else merges attributes."
  [[tag a & children :as el] attrs]
  (if (empty? attrs)
    el
    (let [a' (-> (merge-paint tag a attrs) (merge-transform attrs (center-of tag a)))]
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
  (let [[vw vh] (geo/view-size (sc/frame g))]
    (into
     [:svg {:viewBox (str "0 0 " vw " " vh)
            :preserveAspectRatio "xMidYMid meet"
            :role "img"
            :aria-label (str (sc/scene-name g))}]
     (map #(node-group target g frame %) node-ids))))
