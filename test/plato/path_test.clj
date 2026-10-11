(ns plato.path-test
  "A :path scene node renders as an SVG polyline (or polygon when closed),
   draws against its real length, and :reshape tweens its points."
  (:require [clojure.test :refer [deftest is testing]]
            [plato.geometry :as geo]
            [plato.render :as render]
            [plato.timeline :as timeline]))

(def graph
  {:scene :trail
   :nodes {1 {:id 1 :node :path :points [[0 0] [1 0] [1 1]] :opts {:color :gold :width 2}}
           2 {:id 2 :node :path :points [[0 0] [1 0] [0 1]] :opts {:color :teal :closed? true}}}
   :steps [{:step :play :anims [{:anim :draw :target 1 :opts {:run-time 1.0}}]}
           {:step :play :anims [{:anim :reshape :target 1 :points [[0 0] [1 0] [1 1] [2 1]]
                                 :opts {:run-time 1.0}}]}
           {:step :play :anims [{:anim :reshape :target 1 :points [[0 0] [1 0] [1 1] [2 1] [2 2]]
                                 :opts {:run-time 1.0}}]}]})

(defn- px [pt] (let [{:keys [x y]} (geo/point pt)] [x y]))

(deftest path-renders-as-polyline-or-polygon
  (let [[tag a] (render/node->hiccup graph (get-in graph [:nodes 1]))]
    (is (= :polyline tag))
    (is (= (render/points-str (map px [[0 0] [1 0] [1 1]])) (:points a)))
    (is (= 2 (:stroke-width a)))
    (is (= "none" (:fill a))))
  (testing "closed? draws a polygon"
    (is (= :polygon (first (render/node->hiccup graph (get-in graph [:nodes 2])))))))

(deftest draw-dashes-against-the-path-length
  (let [el (render/node->hiccup graph (get-in graph [:nodes 1]))
        unit (- (first (px [1 0])) (first (px [0 0])))
        open (second (render/apply-attrs el {:draw 0.5}))
        closed (second (render/apply-attrs (render/node->hiccup graph (get-in graph [:nodes 2])) {:draw 0.5}))]
    (testing "an open polyline: two unit segments"
      (is (< (Math/abs (- (:stroke-dasharray open) (* 2 unit))) 1e-6)))
    (testing "a polygon adds its closing segment"
      (is (< (Math/abs (- (:stroke-dasharray closed) (* (+ 2 (Math/sqrt 2)) unit))) 1e-6)))))

(deftest reshape-threads-the-points
  (let [compiled (timeline/compile-timeline graph)
        at (fn [s] (get-in (timeline/frame compiled s) [1 :points]))]
    (testing "duration is draw + two reshapes"
      (is (= 3.0 (:duration compiled))))
    (testing "before a reshape there is no points channel"
      (is (nil? (at 0.5))))
    (testing "each reshape lands on its target, the second starting where the first ended"
      (is (= (mapv px [[0 0] [1 0] [1 1] [2 1]]) (at 2.0)))
      (is (= (mapv px [[0 0] [1 0] [1 1] [2 1] [2 2]]) (at 3.0))))
    (testing "midway, the new point grows out of the old last point"
      (let [mid (at 2.5)
            [lx ly] (px [2 1]) [tx ty] (px [2 2])]
        (is (= 5 (count mid)))
        (is (= (px [2 1]) (nth mid 3)))
        (let [[x y] (nth mid 4)]
          (is (= lx x tx))
          (is (< (min ly ty) y (max ly ty))))))
    (testing "apply-attrs writes the points onto the element"
      (let [el (render/apply-attrs (render/node->hiccup graph (get-in graph [:nodes 1]))
                                   {:points [[1 2] [3 4]]})]
        (is (= "1,2 3,4" (:points (second el))))))))
