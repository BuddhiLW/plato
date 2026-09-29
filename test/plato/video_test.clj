(ns plato.video-test
  "The browserless video plan: frames per slide, the hold, the slide's own
   background, and the images a rasteriser reading from memory can see."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.deck :as deck]
            [plato.desargues :as desargues]
            [plato.desargues-test :as fixture]
            [plato.video :as video]
            [plato.video.encode :as encode]))

;; fixture/graph animates for 1.0 s and holds 0.5 s: 1.5 s of scene.

(defn- scene-slide [id seconds]
  (deck/slide id (desargues/scene fixture/graph)
              (cond-> {:background-color "#0B0E0D"} seconds (assoc :seconds seconds))))

(deftest a-slide-lasts-its-declared-seconds-and-holds-its-last-frame
  (let [frames (video/slide-frames (scene-slide :a 3.0) 10)]
    (is (= 30 (count frames)))
    (is (= 0.0 (:t (first frames))))
    (testing "past the scene's end the time is clamped, so the final frame holds"
      (is (= 1.5 (:t (last frames))))
      (is (= 15 (count (filter #(= 1.5 (:t %)) frames)))))))

(deftest a-slide-without-seconds-lasts-its-scene
  (is (= 15 (count (video/slide-frames (scene-slide :a nil) 10)))))

(deftest the-slide-background-is-the-one-painted
  (let [svg (video/frame-svg (first (video/slide-frames (scene-slide :a 1.0) 10)))]
    (is (str/includes? svg "fill=\"#0B0E0D\""))))

(deftest a-deck-is-its-slides-in-order
  (let [d (deck/deck {:title "t" :slides [(scene-slide :a 1.0) (scene-slide :b 2.0)]})]
    (is (= 30 (count (video/deck-frames d 10))))
    (is (= [] (video/unplayable d)))))

(deftest a-slide-that-is-not-a-scene-is-named-not-dropped
  (let [d (deck/deck {:title "t" :slides [(scene-slide :a 1.0)
                                          (deck/slide :html [:p "hi"])]})]
    (is (= [:html] (video/unplayable d)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hyperframes"
                          (encode/render! d {:out "never.mp4"})))))

(deftest output-height-is-even
  (is (= [1080 1920] (video/frame-size (assoc fixture/graph :frame [480 853.32]) 1080)))
  (is (even? (second (video/frame-size (assoc fixture/graph :frame [100 33]) 101)))))

(deftest relative-images-are-inlined-and-urls-are-not
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "plato-video-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (spit (io/file dir "mark.png") "not really a png")
        g {:nodes {1 {:id 1 :node :image :content "mark.png"}
                   2 {:id 2 :node :image :content "https://example.org/a.png"}
                   3 {:id 3 :node :image :content "missing.png"}
                   4 {:id 4 :node :text :content "mark.png"}}}
        out (:nodes (encode/inline-images g (str dir)))]
    (is (str/starts-with? (get-in out [1 :content]) "data:image/png;base64,"))
    (is (= "https://example.org/a.png" (get-in out [2 :content])))
    (is (= "missing.png" (get-in out [3 :content])) "left for the rasteriser to report")
    (is (= "mark.png" (get-in out [4 :content])) "only :image nodes are assets")))
