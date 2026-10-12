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

(defn- with-steps
  "The fixture graph with its steps replaced."
  [steps]
  (let [k (some #(when (contains? fixture/graph %) %) [:steps :scene/steps])
        play (first (filter #(= :play (:step %)) (get fixture/graph k)))]
    (assoc fixture/graph k (vec (for [s steps] (if (= :play s) play s))))))

(deftest marks-are-the-ends-of-the-steps
  (let [{:keys [marks duration]} (timeline/compile-timeline fixture/graph)]
    (is (= 0.0 (first marks)))
    (is (= duration (peek marks)))
    (is (apply < marks)))
  (let [hold (fn [s] {:step :hold :seconds s})
        marks-of (fn [steps] (:marks (timeline/compile-timeline (with-steps steps))))]
    (is (= [0.0 1.0 2.0] (marks-of [:play :play])) "a mark at the end of each step")
    (is (= [0.0 1.5 2.5] (marks-of [:play (hold 0.5) :play]))
        "a hold travels with the step it follows")
    (is (= [0.0 1.75] (marks-of [:play (hold 0.5) (hold 0.25)])))
    (is (= [0.0 0.5 1.5] (marks-of [(hold 0.5) :play])) "an opening hold is its own mark")
    (is (= [0.0] (marks-of [])))))
