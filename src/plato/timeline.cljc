(ns plato.timeline
  "Compile Desargues scene steps into absolute-time spans and sample frames."
  (:require [plato.protocols :as p]
            [plato.scene :as sc]
            [plato.geometry :as geo]
            [plato.color :as color]
            [plato.tween :as tw]))

;; ── predicates & readers (L0) ───────────────────────────────────────────────
(def DEFAULT-RUN-TIME 1.0)                                  ; manim Animation.run_time default
(defn- run-time  [anim] (double (tw/pget anim :run-time DEFAULT-RUN-TIME)))
(defn- lag-ratio [anim] (double (get-in anim [:opts :lag-ratio] 0.0)))
(defn- composite? [anim] (boolean (#{:group :stagger} (:anim anim))))
(defn- reveals?   [span] (boolean (#{:appear :draw} (:kind span))))

;; ── running node-state, seeded from the static defs (L1) ────────────────────
(defn- rest-fill [nd]
  (color/hex (or (get-in nd [:fill :color]) (get-in nd [:opts :color]) :white)))

(defn- initial-node-state [g nd]
  (if (= :line (:node nd))
    (let [f (geo/point (:from nd)) t (geo/point (:to nd))]          ; a line has endpoints, not an :at
      {:value   nil
       :fill    (rest-fill nd)
       :from-px [(:x f) (:y f)]
       :to-px   [(:x t) (:y t)]
       :pos-px  [(/ (+ (:x f) (:x t)) 2.0) (/ (+ (:y f) (:y t)) 2.0)]
       :base-px [(/ (+ (:x f) (:x t)) 2.0) (/ (+ (:y f) (:y t)) 2.0)]})
    (let [{:keys [x y]} (geo/point (sc/resolve-at g nd))]    ; world -> SVG px, y-flip once
      {:value   (:value nd)                                  ; 23->1000.0, 25->0.0, else nil
       :fill    (rest-fill nd)
       :pos-px  [x y]
       :base-px [x y]})))                                       ; immutable rest anchor

(defn init-state
  "id -> {:value :fill :pos-px :base-px}. Only these are read by tween constructors."
  [g]
  (into {} (map (fn [[id nd]] [id (initial-node-state g nd)])) (sc/nodes g)))

;; ── timing model (L2): pure durations & lag offsets, independent of spans ───
(defn- lag-offsets
  "Manim lag schedule: start offset of child i = offset_{i-1} + r * dur_{i-1}."
  [r durs]
  (->> durs butlast (map #(* r %)) (reductions + 0.0)))

(defn- group-dur
  "Duration of a composite = max child END time (offset + dur)."
  [r durs]
  (reduce max 0.0 (map + (lag-offsets r durs) durs)))

(defn total-dur
  "Wall-clock length of one anim; a composite recurses (each child counts as an
   anim of run_time = its own total-dur)."
  [anim]
  (if (composite? anim)
    (group-dur (lag-ratio anim) (map total-dur (:children anim)))
    (run-time anim)))

;; ── scheduling (L3): anim + absolute start -> {:spans :dur :writes} ─────────
(declare schedule-anim)

(defn- leaf-span [t0 anim tween]
  {:t0 t0 :t1 (+ t0 (run-time anim)) :target (:target anim) :kind (:anim anim)
   :channels (get tw/anim-channels (:anim anim) #{}) :tween tween})

(defn- schedule-leaf [g t0 state anim]
  (let [id (:target anim)
        {:keys [tween writes]} (tw/build-span {:g g :state state :node (sc/node g id)} anim)]
    {:spans  [(leaf-span t0 anim tween)]
     :dur    (run-time anim)
     :writes (if writes {id writes} {})}))

(defn- merge-writes  [ws]         (reduce (partial merge-with merge) {} ws))
(defn- commit-writes [state ws]   (reduce-kv (fn [st id w] (update st id merge w)) state ws))
(defn- all-spans     [schedules]  (vec (mapcat :spans schedules)))

(defn- schedule-group
  "Children read the SAME step-entry snapshot (parallel semantics); their writes
   merge child-order-last-wins."
  [g t0 state anim]
  (let [children  (:children anim)
        durs      (map total-dur children)
        offsets   (lag-offsets (lag-ratio anim) durs)
        schedules (map #(schedule-anim g (+ t0 %1) state %2) offsets children)]
    {:spans  (all-spans schedules)
     :dur    (reduce max 0.0 (map + offsets durs))
     :writes (merge-writes (map :writes schedules))}))

(defn- schedule-anim [g t0 state anim]
  (if (composite? anim)
    (schedule-group g t0 state anim)
    (schedule-leaf  g t0 state anim)))

(defn- combine-schedules
  "Fold one :play step's per-anim schedules into {:dur :spans :state}. All anims
   start together, so step-dur = max child dur; writes commit at step exit."
  [state schedules]
  {:dur   (transduce (map :dur) max 0.0 schedules)
   :spans (all-spans schedules)
   :state (commit-writes state (merge-writes (map :writes schedules)))})

(defn- expand-scene-target [anim]
  (if (and (= :scene (:target anim)) (seq (:ids anim)))
    (map #(-> anim (assoc :target %) (dissoc :ids)) (:ids anim))
    [anim]))

(defn- compile-play [g t state anims]
  (combine-schedules state
                     (map #(schedule-anim g t state %)
                          (mapcat expand-scene-target anims))))

;; ── step fold ───────────────────────────────────────────────────────────────
;; cursor = {:t seconds :state running-node-state :spans emitted-so-far}.
(defmulti advance-step
  "Advance the compile cursor past one step. Add a step kind = add a defmethod."
  (fn [_cursor _g step] (:step step)))

(defmethod advance-step :hold [cursor _g step]
  (update cursor :t + (double (:seconds step 0))))

(defmethod advance-step :play [cursor g step]
  (let [{:keys [dur spans state]} (compile-play g (:t cursor) (:state cursor) (:anims step))]
    (-> cursor (update :t + dur) (assoc :state state) (update :spans into spans))))

(defmethod advance-step :default [cursor _g _step] cursor)   ; unknown step: no-op

(defn- fold-steps [g]
  (let [start {:t 0.0 :state (init-state g) :spans []}]
    (reduce #(advance-step %1 g %2) start (sc/steps g))))

;; ── assembly (L5) ───────────────────────────────────────────────────────────
(defn- index-spans   [spans] (vec (map-indexed (fn [i s] (assoc s :idx i)) spans)))
(defn- revealable-ids [spans] (into #{} (comp (filter reveals?) (map :target)) spans))
(defn- draw-order    [g]     (vec (sort (keys (sc/nodes g)))))

(defn compile-timeline [g]
  (let [{:keys [spans t]} (fold-steps g)
        spans (index-spans spans)]
    {:duration   t
     :spans      spans
     :revealable (revealable-ids spans)
     :node-ids   (draw-order g)}))

;; ── sampling (L6): compose a node's active spans into merged attrs ──────────
(defn- prog
  "Normalized progress of a span at `now`, clamp-held at 1 after t1."
  [{:keys [t0 t1]} now]
  (if (<= t1 t0) 1.0 (tw/clamp01 (/ (- now t0) (- t1 t0)))))

(defn- base-attrs
  "Seed attrs for a node: revealable nodes start hidden, others start visible."
  [revealable id]
  {:opacity (if (contains? revealable id) 0.0 1.0)})

(defn- active-spans
  "A node's spans that have started by `now`, ordered so last-writer-wins."
  [node-spans now]
  (->> node-spans (filter #(<= (:t0 %) now)) (sort-by (juxt :t0 :idx))))

(defn- sample-onto [attrs now span]
  (merge attrs (p/-sample (:tween span) (prog span now))))

(defn- node-attrs [revealable node-spans now id]
  (reduce #(sample-onto %1 now %2)
          (base-attrs revealable id)
          (active-spans node-spans now)))

(defn frame
  "timeline + wall-clock now (seconds) -> {node-id -> merged attrs}.
   No per-kind and no per-channel case — -sample and last-writer-wins do it all."
  [{:keys [spans revealable node-ids]} now]
  (let [spans-by-node (group-by :target spans)]
    (into {}
          (map (fn [id] [id (node-attrs revealable (spans-by-node id) now id)]))
          node-ids)))
