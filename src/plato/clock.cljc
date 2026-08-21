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

(defn play [state now]
  (if (:playing? state)
    state
    (assoc state
           :playing? true
           :epoch (- (double now) (:t state)))))

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
