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

(deftest play-on-a-finished-clock-starts-over
  (let [ended (-> (clock/initial-state 4.0) (clock/play 10.0) (clock/tick 20.0))
        again (clock/play ended 30.0)]
    (is (= 4.0 (:t ended)))
    (is (false? (:playing? ended)))
    (is (true? (:playing? again)))
    (is (= 0.0 (:t again)))
    (is (= 1.5 (:t (clock/tick again 31.5)))))
  (let [paused (-> (clock/initial-state 4.0) (clock/play 10.0) (clock/tick 12.0) clock/pause)]
    (is (= 2.0 (:t (clock/play paused 50.0))) "a paused clock resumes where it stands")))

(deftest stepping-moves-between-marks
  (let [marks [0.0 1.0 2.5 4.0]]
    (is (= 1.0 (clock/next-mark marks 0.0)))
    (is (= 2.5 (clock/next-mark marks 1.0)) "on a mark, forward is the next one")
    (is (= 2.5 (clock/next-mark marks 1.7)))
    (is (nil? (clock/next-mark marks 4.0)))
    (is (= 1.0 (clock/prev-mark marks 1.7)) "mid-step, back is that step's start")
    (is (= 0.0 (clock/prev-mark marks 1.0)) "on a mark, back is the one before")
    (is (nil? (clock/prev-mark marks 0.0)))
    (is (= [0 0 1 1 2 3] (mapv #(clock/mark-index marks %) [0.0 0.5 1.0 2.0 2.5 4.0])))))
