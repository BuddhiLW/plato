(ns plato.clock-test
  (:require [clojure.test :refer [deftest is]]
            [plato.clock :as clock]))

(deftest playback-state-machine
  (let [idle (clock/initial-state 10.0)
        playing (clock/play idle 100.0)
        advanced (clock/tick playing 102.5)
        sought (clock/seek advanced 7.0 103.0)
        ended (clock/tick sought 106.0)]
    (is (= {:t 0.0 :duration 10.0 :playing? false :epoch 0.0} idle))
    (is (true? (:playing? playing)))
    (is (= 2.5 (:t advanced)))
    (is (= 7.0 (:t sought)))
    (is (= 10.0 (:t ended)))
    (is (false? (:playing? ended)))))

(deftest seek-clamps
  (is (= 0.0 (:t (clock/seek (clock/initial-state 3.0) -1 0))))
  (is (= 3.0 (:t (clock/seek (clock/initial-state 3.0) 9 0)))))
