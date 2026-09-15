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

(def portrait-graph
  "The same two nodes in a 9:16 world: a scene declares the frame it was
   authored in, and everything else follows it."
  {:scene :portrait
   :frame [8 14.222]
   :nodes {1 {:id 1 :node :text :text "centre" :at [0 0] :opts {:font-size 40}}
           2 {:id 2 :node :text :text "top" :at [0 7.111] :opts {:font-size 40}}}
   :steps [{:step :play :anims [{:anim :appear :target 1}]}
           {:step :play :anims [{:anim :appear :target 2}]}]})

(deftest a-declared-frame-turns-the-viewbox-portrait
  (let [[_ attrs] (final-svg portrait-graph)]
    (is (= (str "0 0 " (geometry/->len 8) " " (geometry/->len 14.222)) (:viewBox attrs)))
    (is (= (str "0 0 " geometry/view-w " " geometry/view-h)
           (:viewBox (second (final-svg (dissoc portrait-graph :frame)))))
        "a scene that declares no frame is the 16:9 world it always was")))

(deftest text-anchors-where-the-node-says
  (let [anchored (assoc-in portrait-graph [:nodes 1 :opts :anchor] :start)
        text-of (fn [g] (->> (final-svg g)
                             (tree-seq coll? seq)
                             (filter #(and (vector? %) (= :text (first %))))
                             first
                             second))]
    (is (= "start" (:text-anchor (text-of anchored)))
        "a left-aligned line is what a subtitle row needs, and SVG has no other way to say it")
    (is (= "middle" (:text-anchor (text-of portrait-graph)))
        "centred stays the default")))

(deftest positions-are-read-against-the-declared-frame
  (let [texts (->> (final-svg portrait-graph)
                   (tree-seq coll? seq)
                   (filter #(and (vector? %) (= :text (first %)))))
        by-text (into {} (map (fn [[_ a & ch]] [(first ch) [(:x a) (:y a)]])) texts)]
    (is (= [240.0 (/ (geometry/->len 14.222) 2)] (get by-text "centre"))
        "the centre of a 480x853 world, not of a 853x480 one")
    (is (= [240.0 0.0] (get by-text "top"))
        "the top of the portrait frame is y 0, so nothing is authored off-frame")))
