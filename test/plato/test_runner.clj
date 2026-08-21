(ns plato.test-runner
  (:require [clojure.test :as test]
            [plato.clock-test]
            [plato.deck-test]
            [plato.desargues-test]
            [plato.render-test]
            [plato.snapshot-test]
            [plato.timeline-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests
                              'plato.clock-test
                              'plato.deck-test
                              'plato.desargues-test
                              'plato.render-test
                              'plato.snapshot-test
                              'plato.timeline-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
