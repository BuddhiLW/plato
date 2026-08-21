(ns plato.timeline-test
  (:require [clojure.test :refer [deftest is]]
            [plato.desargues-test :as fixture]
            [plato.timeline :as timeline]))

(deftest compile-and-sample-recording-graph
  (let [compiled (timeline/compile-timeline fixture/graph)
        start (timeline/frame compiled 0.0)
        end (timeline/frame compiled (:duration compiled))]
    (is (= 1.5 (:duration compiled)))
    (is (= 1 (count (:spans compiled))))
    (is (= 0.0 (get-in start [1 :opacity])))
    (is (= 1.0 (get-in end [1 :opacity])))))

(deftest scene-target-expands-to-layout-node-ids
  (let [compiled (timeline/compile-timeline fixture/layout-graph)]
    (is (= #{1} (:revealable compiled)))
    (is (= [1] (mapv :target (:spans compiled))))
    (is (= 0.0 (get-in (timeline/frame compiled 0.0) [1 :opacity])))
    (is (= 1.0 (get-in (timeline/frame compiled 1.0) [1 :opacity])))))
