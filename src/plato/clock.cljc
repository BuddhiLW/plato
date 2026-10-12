(ns plato.clock)

(defn clamp-time [duration t]
  (-> (double t)
      (max 0.0)
      (min (double duration))))

(defn initial-state [duration]
  {:t 0.0
   :duration (double duration)
   :playing? false
   :epoch 0.0})

(defn play
  "Start playing from where the clock stands. A clock that already ran to
   its end starts over: Play on a finished animation plays it again."
  [state now]
  (if (:playing? state)
    state
    (let [t (if (>= (:t state) (:duration state)) 0.0 (:t state))]
      (assoc state
             :t t
             :playing? true
             :epoch (- (double now) t)))))

(defn pause [state]
  (assoc state :playing? false))

(defn seek [state t now]
  (let [t (clamp-time (:duration state) t)]
    (assoc state
           :t t
           :epoch (- (double now) t))))

(defn tick [state now]
  (if-not (:playing? state)
    state
    (let [t (- (double now) (:epoch state))
          duration (:duration state)]
      (if (>= t duration)
        (assoc state :t duration :playing? false)
        (assoc state :t (max 0.0 t))))))

;; ── marks: the times a transport steps between ──────────────────────────────
;; A timeline's marks are ascending times, its start and end among them
;; (plato.timeline/compile-timeline). Stepping is a seek to the neighbour.

(def ^:private mark-slack
  "How close to a mark still counts as on it, in seconds."
  1e-6)

(defn next-mark
  "The first mark after t, or nil when t is on or past the last."
  [marks t]
  (first (filter #(> % (+ t mark-slack)) marks)))

(defn prev-mark
  "The last mark before t, or nil when t is on or before the first. From the
   middle of a step this is that step's start."
  [marks t]
  (last (filter #(< % (- t mark-slack)) marks)))

(defn mark-index
  "How many steps are complete at t: the index of the last mark at or before
   it, 0 before any step has finished."
  [marks t]
  (max 0 (dec (count (filter #(<= % (+ t mark-slack)) marks)))))
