(ns plato.render-test
  (:require [clojure.test :refer [deftest is]]
            [plato.desargues-test :as fixture]
            [plato.render :as render]))

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
