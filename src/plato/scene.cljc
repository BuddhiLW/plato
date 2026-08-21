(ns plato.scene
  "L1 scene model: pure accessors over a desargues scene-graph
   {:scene :nodes {id->node} :steps [...] :node-count n}.")

(defn scene-name [g] (:scene g))
(defn nodes [g] (:nodes g))
(defn node [g id] (get (:nodes g) id))
(defn steps [g] (:steps g))

(declare resolve-at)

(defn resolve-at
  "World position [x y z] of a node, resolving :next-to against its ref node.
   Approximate for :next-to (offset the ref by direction * (1.2 + buff))."
  [g nd]
  (or (:at nd)
      (when-let [nt (:next-to nd)]
        (let [ref (node g (:ref nt))
              [rx ry rz] (resolve-at g ref)
              buff (get-in nt [:opts :buff] 0.18)
              off (+ 1.2 buff)]
          (case (:direction nt)
            :right [(+ rx off) ry rz]
            :left  [(- rx off) ry rz]
            :up    [rx (+ ry off) rz]
            :down  [rx (- ry off) rz]
            [rx ry rz])))
      [0.0 0.0 0.0]))
