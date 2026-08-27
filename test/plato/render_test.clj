(ns plato.render-test
  (:require [clojure.test :refer [deftest is]]
            [plato.desargues-test :as fixture]
            [plato.render :as render]
            [plato.geometry :as geometry]
            [plato.timeline :as timeline]))

(deftest layout-node-uses-box-content-and-style
  (let [[tag attrs content]
        (render/node->hiccup fixture/layout-graph
                             (get-in fixture/layout-graph [:nodes 1]))]
    (is (= :text tag))
    (is (= "Plato" content))
    (is (= "#F0AC5F" (:fill attrs)))
    (is (= 40 (:font-size attrs)))
    (is (< 426.0 (:x attrs) 427.0))
    (is (< 80.0 (:y attrs) 81.0))))

(defn- final-svg [graph]
  (let [compiled (timeline/compile-timeline graph)
        frame (timeline/frame compiled (:duration compiled))]
    (render/scene-svg (render/svg-target) graph frame (:node-ids compiled))))

(deftest scene-svg-frames-the-scene
  (let [[tag attrs] (final-svg fixture/graph)]
    (is (= :svg tag))
    (is (= (str "0 0 " geometry/view-w " " geometry/view-h) (:viewBox attrs)))
    (is (= "xMidYMid meet" (:preserveAspectRatio attrs)))
    (is (= "img" (:role attrs)))
    (is (= ":demo" (:aria-label attrs)))))

(deftest scene-svg-draws-one-keyed-group-per-node
  (let [groups (drop 2 (final-svg fixture/graph))
        [g-tag g-attrs element] (first groups)]
    (is (= 1 (count groups)))
    (is (= :g g-tag))
    (is (= "1" (:key g-attrs)))
    (is (= :circle (first element)))
    (is (= 1.0 (:opacity (second element))))))

(deftest scene-svg-honours-node-id-order
  (let [graph (assoc-in fixture/graph [:nodes 2]
                        {:id 2 :node :circle :at [1 0] :opts {:radius 0.25}})
        keys-in-order (map #(:key (second %)) (drop 2 (final-svg graph)))]
    (is (= ["1" "2"] keys-in-order))))
