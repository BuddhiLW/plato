(ns plato.snapshot-test
  (:require [clojure.test :refer [deftest is]]
            [plato.desargues-test :as fixture]
            [plato.snapshot :as snapshot]))

(deftest final-state-svg
  (let [svg (snapshot/scene->svg fixture/graph)]
    (is (.startsWith svg "<svg"))
    (is (.contains svg "<circle"))
    (is (.contains svg "opacity=\"1.0\""))
    (is (.contains svg "</svg>"))))

(deftest serializer-escapes-content
  (is (= "<text>A &amp; B</text>"
         (snapshot/hiccup->str [:text "A & B"]))))
