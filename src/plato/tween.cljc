(ns plato.tween
  "Tween records and Desargues animation-descriptor compilation."
  (:require [plato.protocols :as p]
            [plato.color :as color]
            [plato.geometry :as geo]
            [plato.scene :as sc]
            [plato.fmt :as fmt]
            [clojure.string :as str]))

;; ── rate functions ──────────────────────────────────────────────────────────
(defn clamp01 [t] (-> (double t) (max 0.0) (min 1.0)))
(defn smooth  [t] (let [t (clamp01 t)] (* t t (- 3.0 (* 2.0 t)))))   ; 3t²−2t³, smooth(0.5)=0.5
(defn there-and-back                                                 ; 0 -> 1 -> 0 (pulse)
  [t] (let [t (clamp01 t)
            u (if (< t 0.5) (* 2.0 t) (* 2.0 (- 1.0 t)))]
        (smooth u)))
(defn- lerp [a b t] (+ a (* (- b a) t)))

;; ── param resolver ──────────────────────────────────────────────────────────
;; The desargues vocab is inconsistent: :to/:value/:color may sit at the anim
;; level, while :run-time/:color may sit in :opts. Prefer anim-level, then :opts.
(defn pget
  ([anim k]   (pget anim k nil))
  ([anim k d] (let [v (get anim k)] (if (some? v) v (get-in anim [:opts k] d)))))

;; ── pure sRGB hex lerp (reader-conditional ONLY for the parse) ───────────────
(defn- hex->rgb [h]
  (let [h (if (str/starts-with? h "#") (subs h 1) h)
        p (fn [i] #?(:cljs    (js/parseInt      (subs h i (+ i 2)) 16)
                     :default (Integer/parseInt (subs h i (+ i 2)) 16)))]
    [(p 0) (p 2) (p 4)]))

(defn- byte->hex [n]
  (let [n (int (max 0 (min 255 (long (+ 0.5 (double n))))))
        d "0123456789abcdef"]
    (str (nth d (quot n 16)) (nth d (mod n 16)))))

(defn lerp-hex
  "Linear sRGB interpolation between two css hex strings at t in [0,1]."
  [from to t]
  (let [[r1 g1 b1] (hex->rgb from)
        [r2 g2 b2] (hex->rgb to)]
    (str "#" (byte->hex (lerp r1 r2 t))
             (byte->hex (lerp g1 g2 t))
             (byte->hex (lerp b1 b2 t)))))

;; ── the tween records (each bakes its own rate func) ────────────────────────
(defrecord Appear []
  p/ITween (-sample [_ t] {:opacity (smooth t)}))

(defrecord Vanish []
  p/ITween (-sample [_ t] {:opacity (- 1.0 (smooth t))}))

(defrecord Draw []
  p/ITween (-sample [_ t] {:opacity 1.0 :draw (smooth t)}))

(defrecord Recolor [from to]                      ; from/to = resolved css hex
  p/ITween (-sample [_ t] {:fill (lerp-hex from to (smooth t))}))

(defrecord CountTo [from to dp]                   ; from/to = doubles, dp = decimals
  p/ITween (-sample [_ t] {:text (fmt/fixed (lerp from to (smooth t)) dp)}))

(defrecord Glide [fx fy tx ty bx by]              ; SVG px; y-flip pre-baked via geo/point.
  p/ITween (-sample [_ t]                         ; translate is RELATIVE TO REST anchor (bx,by),
             (let [p (smooth t)]                  ; so last-writer-wins yields absolute position.
               {:translate [(- (lerp fx tx p) bx) (- (lerp fy ty p) by)]})))

(defrecord Emphasize [base pulse max-scale]       ; base/pulse = hex; pivot derived in -apply
  p/ITween (-sample [_ t]
             (let [a (there-and-back t)]
               {:fill  (lerp-hex base pulse a)
                :scale (+ 1.0 (* (- max-scale 1.0) a))})))

(defrecord Noop []                                ; :morph / unknown: graceful no-op
  p/ITween (-sample [_ _] {}))

;; ── animation channel registry ──────────────────────────────────────────────
;; kind -> the channels its span writes. Diagnostic on spans; :appear/:draw
;; targets seed the pre-appear-hidden set (see plato.timeline/compile-timeline).
(def anim-channels
  {:appear    #{:opacity}
   :vanish    #{:opacity}
   :draw      #{:opacity :draw}
   :recolor   #{:fill}
   :emphasize #{:fill :scale}
   :count-to  #{:text}
   :glide     #{:translate}
   :morph     #{}})

;; ── constructor seam: descriptor + threaded start-state -> {:tween :writes} ──
;; ctx = {:g graph :state running-state-snapshot :node node-def}.
;; :writes is the end-state this anim commits (nil = threads nothing). Opacity is
;; NOT threaded — the seed + the active span fully determine it.
(defmulti build-span (fn [_ctx anim] (:anim anim)))

(defmethod build-span :appear [_ _] {:tween (->Appear) :writes nil})
(defmethod build-span :vanish [_ _] {:tween (->Vanish) :writes nil})
(defmethod build-span :draw   [_ _] {:tween (->Draw)   :writes nil})
(defmethod build-span :morph  [_ _] {:tween (->Noop)   :writes nil})
(defmethod build-span :default [_ _] {:tween (->Noop)  :writes nil})   ; unknown kind: no crash

(defmethod build-span :recolor [{:keys [state]} anim]
  (let [id   (:target anim)
        from (get-in state [id :fill])
        to   (color/hex (pget anim :color))]
    {:tween (->Recolor from to) :writes {:fill to}}))

(defmethod build-span :count-to [{:keys [g state]} anim]
  (let [id   (:target anim)
        from (double (or (get-in state [id :value]) 0.0))   ; THREADED running value
        to   (double (pget anim :value))
        dp   (get-in (sc/node g id) [:opts :num-decimal-places] 0)]
    {:tween (->CountTo from to dp) :writes {:value to}}))

(defmethod build-span :glide [{:keys [state]} anim]
  (let [id      (:target anim)
        [fx fy] (get-in state [id :pos-px])                 ; current position (threaded)
        [bx by] (get-in state [id :base-px])                ; immutable rest anchor
        {:keys [x y]} (geo/point (pget anim :to))]          ; y-flip applied ONCE, in geo
    {:tween (->Glide fx fy x y bx by) :writes {:pos-px [x y]}}))

(defmethod build-span :emphasize [{:keys [state]} anim]
  (let [id    (:target anim)
        base  (get-in state [id :fill])
        pulse (color/hex (pget anim :color))]
    ;; symmetric pulse returns to base -> commits NO state.
    {:tween (->Emphasize base pulse 1.15) :writes nil}))
