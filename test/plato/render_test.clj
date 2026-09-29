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

(deftest draw-dashes-against-the-real-perimeter
  ;; resvg ignores pathLength, so a pathLength=1 dash rasterised as a dotted
  ;; outline in every video frame. The dash must be the shape's own length.
  (let [rect [:rect {:x 0 :y 0 :width 40 :height 10 :rx 0}]
        [_ half] (render/apply-attrs rect {:draw 0.5})
        [_ done] (render/apply-attrs rect {:draw 1.0})
        [_ line] (render/apply-attrs [:line {:x1 0 :y1 0 :x2 3 :y2 4}] {:draw 0.0})
        [_ ring] (render/apply-attrs [:circle {:cx 0 :cy 0 :r 1}] {:draw 0.25})]
    (is (nil? (:pathLength half)))
    (is (= 100.0 (:stroke-dasharray half)))
    (is (= 50.0 (:stroke-dashoffset half)))
    (is (not (contains? done :stroke-dasharray))
        "a finished reveal is a plain stroke")
    (is (= 5.0 (:stroke-dasharray line)))
    (is (= 5.0 (:stroke-dashoffset line)))
    (is (< 6.283 (:stroke-dasharray ring) 6.284))))

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

(deftest text-sets-the-face-the-node-asks-for
  (let [branded (assoc-in portrait-graph [:nodes 1 :opts :font-family] "Lato, sans-serif")
        family-of (fn [g] (->> (final-svg g)
                               (tree-seq coll? seq)
                               (filter #(and (vector? %) (= :text (first %))))
                               first
                               second
                               :font-family))]
    (is (= "Lato, sans-serif" (family-of branded))
        "an exported SVG is read by a rasteriser, not by a page with a stylesheet, so the node is the only place left to name the face")
    (is (= "system-ui, sans-serif" (family-of portrait-graph))
        "the host's UI font stays the default, so no existing deck moves")))

(deftest text-fades-like-every-other-node
  (let [faded (assoc-in portrait-graph [:nodes 1 :fill :opacity] 0.4)
        alpha-of (fn [g] (->> (final-svg g)
                              (tree-seq coll? seq)
                              (filter #(and (vector? %) (= :text (first %))))
                              first
                              second
                              :fill-opacity))]
    (is (= 0.4 (alpha-of faded))
        "type set behind a subject has to recede, and a text node had no way to say so")
    (is (= 1 (alpha-of portrait-graph))
        "opaque stays the default")))

(deftest positions-are-read-against-the-declared-frame
  (let [texts (->> (final-svg portrait-graph)
                   (tree-seq coll? seq)
                   (filter #(and (vector? %) (= :text (first %)))))
        by-text (into {} (map (fn [[_ a & ch]] [(first ch) [(:x a) (:y a)]])) texts)]
    (is (= [240.0 (/ (geometry/->len 14.222) 2)] (get by-text "centre"))
        "the centre of a 480x853 world, not of a 853x480 one")
    (is (= [240.0 0.0] (get by-text "top"))
        "the top of the portrait frame is y 0, so nothing is authored off-frame")))
