(ns plato.hyperframes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.desargues :as desargues]
            [plato.hyperframes :as hf]
            [plato.json :as json]
            [plato.markdown]))

(def graph
  {:scene :demo
   :nodes {1 {:id 1 :node :circle :at [0 0] :opts {:radius 0.5 :color :gold}}}
   :steps [{:step :play :anims [{:anim :appear :target 1 :opts {:run-time 6.0}}]}
           {:step :hold :seconds 0.5}]})

(def model
  (deck/deck
   {:title "Acme"
    :description "Quarterly review"
    :slides
    [(deck/slide :cover [:h1 "Acme"]
                 {:background-color "#123456" :notes "Welcome"})
     (deck/slide :points
                 [:div [:h2 "Three points"]
                  (content/bullets ["one" "two" "three"] {:fragments? true})]
                 {:notes (content/group [[:p "Say " [:strong "this"]] [:p "then that"]])})
     (deck/slide :demo (content/video "assets/demo.mp4"
                                      {:poster "assets/poster.jpg"
                                       :autoplay? true}))
     (deck/slide :md "## Markdown\n\n- one\n- two")
     (deck/slide :scene (desargues/scene graph))
     (deck/stack :chapter
                 [(deck/slide :chapter-intro [:h2 "Chapter"] {:seconds 2})
                  (deck/slide :chapter-detail [:p.fragment "Detail"])])]}))

(def plans (hf/plan model))
(def page (hf/deck->composition model))
(def manifest (json/parse (second (re-find #"(?s)<script type=\"application/hyperframes-slideshow\+json\">(.*?)</script>" page))))

(defn- plan-of [id] (first (filter #(= id (:id %)) plans)))

(deftest document-shell
  (is (str/starts-with? page "<!doctype html>"))
  (is (str/includes? page "<meta content=\"width=1920, height=1080\" name=\"viewport\">"))
  (is (str/includes? page "<title>Acme</title>"))
  (is (str/includes? page "<meta content=\"Quarterly review\" name=\"description\">"))
  (is (str/includes? page "href=\"./css/plato.css\""))
  (is (str/includes? page "name=\"viewport\"><script src=\"./hyperframes/hyperframe.runtime.iife.js\"></script>")
      "the HyperFrames runtime loads before anything else in the head")
  (is (str/includes? page "window.__timelines") "the page registers its own scene timelines")
  (is (str/includes? page "src=\"./vendor/plato-scene/main.js\"") "a deck with a scene loads the scene bundle")
  (is (not (str/includes? page "gsap"))))

(deftest the-deck-is-one-root-composition
  (is (str/includes? page "<div id=\"main\" class=\"plato-deck\" data-composition-id=\"main\" data-duration=\"34.5\" data-height=\"1080\" data-start=\"0\" data-width=\"1920\">")
      "the root spans the deck: where the last scene ends")
  (is (= 34.5 (hf/total-seconds plans)))
  (is (str/includes? page "registry['main'] = timeline(") "and registers the root timeline the player binds"))

(deftest main-line-is-the-flattened-deck
  (is (= ["cover" "points" "demo" "md" "scene" "chapter-intro" "chapter-detail"]
         (mapv :id plans)
         (mapv :sceneId (:slides manifest)))))

(deftest scenes-are-timed-end-to-end
  (testing "each slide starts where the previous one ends"
    (is (= [0.0 5.0 10.0 15.0 20.0 27.5 29.5] (mapv :start plans))))
  (testing "a slide with nothing to reveal holds the default"
    (is (= 5.0 (:seconds (plan-of "cover")))))
  (testing "an explicit :seconds wins"
    (is (= 2.0 (:seconds (plan-of "chapter-intro")))))
  (testing "the scene element carries the timing"
    (is (str/includes? page "<div id=\"points\" class=\"reveal plato-slide\" data-composition-id=\"points\" data-duration=\"5.0\" data-height=\"1080\" data-plato-steps=\"[1.0,2.0,3.0]\" data-start=\"5.0\" data-track-index=\"2\" data-width=\"1920\">") "one Studio track per scene")
    (is (str/includes? page "<section id=\"points-body\" class=\"clip\">") "the body box is not a second timed element")))

(deftest fragments-become-steps-and-hold-points
  (let [p (plan-of "points")]
    (is (= [1.0 2.0 3.0] (:steps p)) "one step per fragment, a second apart, local to the scene")
    (is (= [5.0 6.0 7.0 8.0] (:holds p)) "absolute hold-points: the slide's start, with nothing revealed, then each step")
    (is (= [5.0 6.0 7.0 8.0] (get-in manifest [:slides 1 :fragments])))
    (is (= 5.0 (:seconds p)) "three steps and a tail still fit the default hold"))
  (testing "fragments are stamped with their step, in document order"
    (is (str/includes? page "<li class=\"fragment fade-in\" data-plato-step=\"0\">one</li>"))
    (is (str/includes? page "<li class=\"fragment fade-in\" data-plato-step=\"2\">three</li>")))
  (testing "explicit fragment indices order the steps"
    (let [raw (hf/fragment-indices [:div [:p.fragment {:data-fragment-index 2} "b"]
                                    [:p.fragment "a"]
                                    [:p.fragment {:data-fragment-index "2"} "b2"]])]
      (is (= [2 0 2] raw)))))

(deftest a-scene-is-entered-on-its-final-frame
  (let [p (plan-of "scene")]
    (is (= 7.5 (:seconds p)) "the scene's 6.5s plus the tail")
    (is (= [26.5] (:holds p)) "one hold-point: the scene's end, no hold at the start")
    (is (= [] (:steps p)))
    (is (str/includes? page "data-plato-scene=\"") "the static scene is there to hydrate")))

(deftest notes-are-plain-text
  (is (= "Welcome" (get-in manifest [:slides 0 :notes])))
  (is (= "Say this then that" (get-in manifest [:slides 1 :notes])))
  (is (nil? (get-in manifest [:slides 2 :notes]))))

(deftest markdown-slides-are-expanded
  (is (str/includes? page "<h1>Markdown</h1>") "the first heading of a lone markdown string is its top level")
  (is (str/includes? page "<li>one</li>"))
  (is (not (str/includes? page "data-markdown")) "no markdown plugin runs in a HyperFrames page"))

(deftest media-is-placed-on-the-timeline
  (is (re-find #"<video id=\"demo-video-0\"[^>]*data-duration=\"5.0\" data-start=\"10.0\" muted=\"\" playsInline=\"\" poster=\"assets/poster.jpg\"[^>]*src=\"assets/demo.mp4\"" page)
      "timed to its slide, and named after it so the renderer finds it")
  (is (not (str/includes? page "data-autoplay"))))

(deftest backgrounds-are-inline-styles
  (is (str/includes? page "style=\"background-color:#123456\"")))

(deftest reserved-ids-are-hashed
  (let [ids (mapv :id (hf/plan (deck/deck {:slides [(deck/slide :main [:h1 "x"])
                                                    (deck/slide :captions-1 [:h1 "x"])
                                                    (deck/slide :ambient [:h1 "x"])
                                                    (deck/slide :intro [:h1 "x"])]})))]
    (is (= "intro" (last ids)))
    (is (every? hf/scene-id? ids) "every scene id survives HyperFrames' substring rule")
    (is (every? #(re-matches #"s-\d+" %) (butlast ids)))
    (is (= (hf/scene-id :ambient) (hf/scene-id "ambient")) "stable across builds and spellings"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collide"
                        (hf/plan (deck/deck {:slides [(deck/slide :main [:h1 "x"])
                                                      (deck/slide (keyword (hf/scene-id :main)) [:h1 "y"])]}))))
  (is (hf/scene-id? :intro)))

(deftest the-presenter-page-defines-its-elements-after-their-children
  (let [presenter (hf/deck->presenter model)
        island (str/index-of presenter "application/hyperframes-slideshow+json")
        player (str/index-of presenter "<hyperframes-player interactive=\"\" src=\"index.html\">")
        scripts (str/index-of presenter "<script src=\"./hyperframes/hyperframes-player.global.js\"></script><script src=\"./hyperframes/hyperframes-slideshow.global.js\"></script></body>")]
    (is (str/includes? presenter "<title>Acme — Presenter</title>"))
    (is (str/includes? presenter "<hyperframes-slideshow sound=\"\" tabindex=\"0\">"))
    (is (and player island scripts (< player island scripts))
        "player, then island, then the scripts that upgrade the elements")
    (is (str/includes? presenter (hf/island-json plans)) "the same island the composition carries")))

(deftest island-shape
  (is (= {:slides [{:scene-id "cover" :notes "Welcome"}
                   {:scene-id "points" :notes "Say this then that" :fragments [5.0 6.0 7.0 8.0]}]}
         (hf/island (take 2 plans)))))
