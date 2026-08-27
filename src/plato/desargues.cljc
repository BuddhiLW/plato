(ns plato.desargues
  "Desargues scene-graph contract, the :desargues content constructor, and the
   STATIC projection of that content kind."
  (:require [plato.content :as content]
            [plato.render :as render]
            [plato.timeline :as timeline]))

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

(defmethod content/render :desargues [{:keys [graph]}]
  (let [compiled (timeline/compile-timeline graph)
        frame (timeline/frame compiled (:duration compiled))]
    [:div.plato-scene
     (render/scene-svg (render/svg-target) graph frame (:node-ids compiled))]))
