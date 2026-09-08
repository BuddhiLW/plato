(ns plato.snapshot-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.desargues-test :as fixture]
            [plato.snapshot :as snapshot]))

(deftest final-state-svg
  (let [svg (snapshot/scene->svg fixture/graph)]
    (is (.startsWith svg "<svg"))
    (is (.contains svg "<circle"))
    (is (.contains svg "opacity=\"1.0\""))
    (is (.contains svg "</svg>"))))

;; ── Frames at arbitrary times ────────────────────────────────────────────────
;;
;; `timeline/frame` was always a function of wall-clock time; only this
;; namespace pinned it to the end of the scene. Reaching any frame is what a
;; video export needs.

(deftest scene-has-a-duration
  (is (number? (snapshot/scene-duration fixture/graph)))
  (is (pos? (snapshot/scene-duration fixture/graph))))

(deftest a-time-selects-a-frame
  (let [dur (snapshot/scene-duration fixture/graph)
        first-frame (snapshot/scene->svg fixture/graph 0.0)
        last-frame (snapshot/scene->svg fixture/graph dur)]
    (is (.startsWith first-frame "<svg"))
    (is (.endsWith (str/trim last-frame) "</svg>"))
    (testing "the scene animates, so its first and last frames differ"
      (is (not= first-frame last-frame)))
    (testing "and the last frame is what the no-argument arity renders"
      (is (= (snapshot/scene->svg fixture/graph) last-frame)))))

(deftest nil-means-the-final-frame
  (is (= (snapshot/scene->svg fixture/graph)
         (snapshot/scene->svg fixture/graph nil))))

(deftest frame-times-cover-both-endpoints
  (let [dur (snapshot/scene-duration fixture/graph)
        ts (snapshot/frame-times fixture/graph 10)]
    (is (= 0.0 (first ts)))
    (is (< (Math/abs (- dur (last ts))) 1.0e-9)
        "the last sample is the final frame, not one frame short")
    (testing "the times are increasing"
      (is (= ts (vec (sort ts)))))
    (testing "and there are about fps*duration of them, plus the endpoint"
      (is (= (inc (long (Math/round (* dur 10.0)))) (count ts))))))

(deftest frame-times-of-a-still-is-a-single-time
  (with-redefs [snapshot/scene-duration (constantly 0.0)]
    (is (= [0.0] (snapshot/frame-times fixture/graph 30)))))

(deftest scene-frames-pairs-times-with-svg
  (let [frames (vec (snapshot/scene->frames fixture/graph 4))]
    (is (seq frames))
    (is (= (snapshot/frame-times fixture/graph 4) (mapv first frames)))
    (is (every? (fn [[_ svg]] (.startsWith svg "<svg")) frames))))

(deftest write-frames-names-them-in-temporal-order
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/plato-frames-" (System/nanoTime))
        paths (snapshot/write-frames! dir fixture/graph 4)]
    (try
      (is (seq paths))
      (is (every? (fn [p] (.exists (java.io.File. ^String p))) paths))
      (testing "zero-padded, so a lexical sort is a temporal one"
        (is (= paths (vec (sort paths)))))
      (is (.startsWith (slurp (first paths)) "<svg"))
      (finally
        (doseq [p paths] (.delete (java.io.File. ^String p)))
        (.delete (java.io.File. ^String dir))))))

(deftest serializer-escapes-content
  (is (= "<text>A &amp; B</text>"
         (snapshot/hiccup->str [:text "A & B"]))))

;; ── Portability ──────────────────────────────────────────────────────────────
;;
;; This namespace is .cljc: the same sampling runs on the JVM and on clojurust,
;; where `Math/round` and `java.io.File` do not exist. These pin the two
;; behaviours that had to be rewritten to get there.

(deftest frame-names-are-zero-padded-to-five-digits
  (is (= "frame-00000.svg" (snapshot/frame-name 0)))
  (is (= "frame-00042.svg" (snapshot/frame-name 42)))
  (is (= "frame-99999.svg" (snapshot/frame-name 99999)))
  (testing "a frame past the pad width still names a file, just a wider one"
    (is (= "frame-100000.svg" (snapshot/frame-name 100000))))
  (testing "lexical order is temporal order within the pad width"
    (let [names (mapv snapshot/frame-name (range 12))]
      (is (= names (vec (sort names)))))))

(deftest frame-count-rounds-half-up
  ;; `(long (+ 0.5 x))` replaced `Math/round`; they agree on non-negative
  ;; values, which duration x fps always is. Both endpoints are sampled, so
  ;; the count is one more than the number of intervals.
  (let [dur (snapshot/scene-duration fixture/graph)]
    (doseq [fps [1 4 10 30]]
      (let [expected (inc (long (+ 0.5 (* (double dur) (double fps)))))]
        (is (= expected (count (snapshot/frame-times fixture/graph fps)))
            (str "frame count at " fps " fps"))))))
