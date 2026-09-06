(ns plato.line-test
  "A :line scene node renders as an SVG line and :connect tweens its endpoints."
  (:require [clojure.test :refer [deftest is testing]]
            [plato.geometry :as geo]
            [plato.render :as render]
            [plato.timeline :as timeline]))

(def graph
  {:scene :rod
   :nodes {1 {:id 1 :node :dot :at [0 2] :opts {:color :grey}}
           2 {:id 2 :node :line :from [0 2] :to [1 0] :opts {:color :gold :width 4}}
           3 {:id 3 :node :circle :at [1 0] :opts {:radius 0.3 :color :gold}}}
   :steps [{:step :play :anims [{:anim :draw :target 2 :opts {:run-time 1.0}}]}
           {:step :play :anims [{:anim :connect :target 2 :from [0 2] :to [-1 0] :opts {:run-time 2.0}}
                                {:anim :glide :target 3 :to [-1 0] :opts {:run-time 2.0}}]}
           {:step :hold :seconds 0.5}]})

(deftest line-renders-as-svg-line
  (let [[tag a] (render/node->hiccup graph (get-in graph [:nodes 2]))
        f (geo/point [0 2]) t (geo/point [1 0])]
    (is (= :line tag))
    (is (= [(:x f) (:y f) (:x t) (:y t)] [(:x1 a) (:y1 a) (:x2 a) (:y2 a)]))
    (is (= 4 (:stroke-width a)))
    (is (= "none" (:fill a)))))

(deftest connect-moves-the-endpoints
  (let [compiled (timeline/compile-timeline graph)
        at (fn [s] (get-in (timeline/frame compiled s) [2 :endpoints]))
        [x1 y1 x2 y2] (at 3.5)
        {ex :x ey :y} (geo/point [-1 0])
        {fx :x fy :y} (geo/point [0 2])]
    (testing "duration is draw + connect + hold"
      (is (= 3.5 (:duration compiled))))
    (testing "before the connect there is no endpoint channel, the static line stands"
      (is (nil? (at 0.5))))
    (testing "after the connect the far endpoint has arrived and the pivot stayed"
      (is (< (Math/abs (- x2 ex)) 1e-9))
      (is (< (Math/abs (- y2 ey)) 1e-9))
      (is (< (Math/abs (- x1 fx)) 1e-9))
      (is (< (Math/abs (- y1 fy)) 1e-9)))
    (testing "halfway through, the endpoint is between start and target"
      (let [[_ _ mx _] (at 2.0)
            {sx :x} (geo/point [1 0])]
        (is (< (min sx ex) mx (max sx ex)))))
    (testing "apply-attrs writes the endpoints onto the element"
      (let [el (render/apply-attrs (render/node->hiccup graph (get-in graph [:nodes 2]))
                                   {:endpoints [1 2 3 4]})]
        (is (= [1 2 3 4] (mapv (second el) [:x1 :y1 :x2 :y2])))))))
