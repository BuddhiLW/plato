(ns plato.desargues-test
  (:require [clojure.test :refer [deftest is]]
            [plato.desargues :as desargues]))

(def graph
  {:scene :demo
   :nodes {1 {:id 1 :node :circle :at [0 0]
              :opts {:radius 0.5 :color :gold}}}
   :steps [{:step :play
            :anims [{:anim :appear :target 1 :opts {:run-time 1.0}}]}
           {:step :hold :seconds 0.5}]})

(def layout-graph
  {:scene :layout-demo
   :kind :layout
   :nodes {1 {:id 1
              :node :text
              :content "Plato"
              :box {:x 5.851911 :y 0.5 :w 1.5184 :h 0.6716}
              :style {:font-size 40 :color :gold}}}
   :steps [{:step :play
            :anims [{:anim :appear :target :scene :ids [1] :opts {}}]}
           {:step :hold :seconds 1.0}]
   :layout {:desargues.layout/box {:x 0 :y 0 :w 13.222222 :h 7.0}}})

(deftest recording-graph-contract
  (is (desargues/graph? graph))
  (is (desargues/graph? layout-graph))
  (is (= graph (desargues/assert-graph! graph)))
  (is (false? (desargues/graph? {:nodes [] :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scene graph"
                        (desargues/assert-graph! {:nodes {}}))))

(deftest scene-content-descriptor
  (let [content (desargues/scene graph {:autoplay? true :controls? false})]
    (is (= :desargues (:plato/type content)))
    (is (= graph (:graph content)))
    (is (true? (:autoplay? content)))
    (is (false? (:controls? content)))))
