(ns plato.desargues)

(defn graph? [value]
  (and (map? value)
       (contains? value :scene)
       (map? (:nodes value))
       (vector? (:steps value))
       (every? (fn [[id node]]
                 (and (some? id)
                      (map? node)
                      (keyword? (:node node))))
               (:nodes value))
       (every? (fn [step]
                 (and (map? step)
                      (#{:play :hold} (:step step))))
               (:steps value))))

(defn assert-graph! [graph]
  (if (graph? graph)
    graph
    (throw (ex-info "Invalid Desargues scene graph"
                    {:value graph
                     :required [:scene :nodes :steps]}))))

(defn scene
  ([graph] (scene graph {}))
  ([graph opts]
   (merge {:plato/type :desargues
           :graph (assert-graph! graph)
           :autoplay? false
           :controls? true}
          opts)))
