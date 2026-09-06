(ns plato.acme-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.acme.deck :as acme]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.desargues :as desargues]
            [plato.hiccup :as hiccup]
            [plato.html :as html]
            [plato.timeline :as timeline]))

(def leaves (deck/leaf-slides acme/model))

(def page (html/deck->html acme/model {:math? true}))

;; ── deck shape ──────────────────────────────────────────────────────────────

(deftest deck-is-validated-and-unique
  (is (= :deck (:plato/type acme/model)))
  (is (= "Acme Corp — Q3 Product Review" (:title acme/model)))
  (testing "deck/deck accepted every entry"
    (is (= acme/model (deck/deck acme/model))))
  (testing "ids are explicit keywords and unique"
    (let [ids (mapv :id leaves)]
      (is (every? keyword? ids))
      (is (= (count ids) (count (set ids))))
      (is (< 20 (count ids)))))
  (testing "the stack nests three deep-dive slides"
    (let [stack (first (filter #(= :stack (:plato/type %)) (:slides acme/model)))]
      (is (= :deep-dive (:id stack)))
      (is (= [:deep-dive-code :deep-dive-architecture :deep-dive-actions]
             (mapv :id (:slides stack)))))))

(deftest speaker-notes-are-present
  (is (<= 6 (count (filter :notes leaves)))))

;; ── assets ──────────────────────────────────────────────────────────────────

(def media-keys #{:src :poster :background-image :background-video})

(defn media-paths
  "Every media path reachable in `value`: the `media-keys` of any nested map,
   plus the :src of each entry of a :sources vector."
  [value]
  (into #{}
        (comp (filter map?)
              (mapcat (fn [m]
                        (concat (keep #(get m %) media-keys)
                                (keep :src (:sources m))))))
        (tree-seq coll? seq value)))

(deftest every-referenced-asset-exists
  (let [paths (media-paths acme/model)]
    (is (= (set (vals acme/assets)) paths)
        "the deck references exactly the assets it declares")
    (is (= 11 (count paths)))
    (doseq [path (sort paths)]
      (is (string? path) path)
      (is (str/starts-with? path "assets/acme/") path)
      (is (.exists (io/file "public" path))
          (str "missing fixture: public/" path)))))

;; ── desargues ───────────────────────────────────────────────────────────────

(deftest scene-graph-compiles
  (is (desargues/graph? acme/arr-graph))
  (let [{:keys [duration node-ids spans revealable]}
        (timeline/compile-timeline acme/arr-graph)]
    (is (pos? duration))
    (is (= (vec (sort (keys (:nodes acme/arr-graph)))) node-ids))
    (is (= #{1 2 3 4 5 6 7 8 9 10 11} (set node-ids)))
    (is (<= 6 (count node-ids)))
    (is (<= 5 (count (:steps acme/arr-graph))))
    (is (seq spans))
    (is (= #{1 2 3 4 5 6 7 8 9 10 11} revealable)))
  (testing "the scene is mounted on a slide as :desargues content"
    (let [slide (first (filter #(= :arr-scene (:id %)) leaves))]
      (is (= :desargues (content/kind (:content slide))))
      (is (identical? acme/arr-graph (get-in slide [:content :graph]))))))

(deftest scene-renders-to-svg
  (let [svg (content/render (desargues/scene acme/arr-graph))]
    (is (= :div.plato-scene (first svg)))
    (is (str/includes? (hiccup/->html svg) "<svg "))))

;; ── content rendering ───────────────────────────────────────────────────────

(deftest every-slide-renders-to-hiccup
  (doseq [{:keys [id content]} leaves]
    (let [rendered (content/render content)]
      (is (or (string? rendered) (vector? rendered)) (str id))
      (is (string? (hiccup/->html rendered)) (str id)))))

(deftest sections-cover-every-entry
  (testing "one <section> per leaf slide plus one per stack"
    (is (= (inc (count leaves)) (count (re-seq #"<section" page)))))
  (doseq [{:keys [id]} leaves]
    (is (str/includes? page (str "id=\"" (name id) "\"")) (str id)))
  (is (str/includes? page "<section id=\"deep-dive\"><section id=\"deep-dive-code\"")))

(deftest document-shell
  (is (str/starts-with? page "<!doctype html>"))
  (is (str/includes? page "<title>Acme Corp — Q3 Product Review</title>"))
  (is (str/includes? page "<div class=\"reveal\"><div class=\"slides\">"))
  (is (str/includes? page "\"slideNumber\":\"c/t\"")))

(deftest media-slides-carry-their-fixtures
  (testing "title background image"
    (is (str/includes? page "data-background-image=\"assets/acme/hero.jpg\""))
    (is (str/includes? page "data-background-opacity=\"0.35\"")))
  (testing "reveal background video"
    (is (str/includes? page "data-background-video=\"assets/acme/ambient.mp4\""))
    (is (str/includes? page "data-background-video-loop=\"\""))
    (is (str/includes? page "data-background-video-muted=\"\"")))
  (testing "images, gif and svg"
    (is (str/includes? page "src=\"assets/acme/logo.svg\""))
    (is (str/includes? page "src=\"assets/acme/chart.png\""))
    (is (str/includes? page "src=\"assets/acme/pipeline.svg\""))
    (is (str/includes? page "src=\"assets/acme/loop.gif\"")))
  (testing "inline video with both sources and a poster"
    (is (str/includes? page "poster=\"assets/acme/demo-poster.jpg\""))
    (is (str/includes? page "<source src=\"assets/acme/demo.webm\" type=\"video/webm\">"))
    (is (str/includes? page "<source src=\"assets/acme/demo.mp4\" type=\"video/mp4\">")))
  (testing "audio and iframe"
    (is (str/includes? page "<audio class=\"plato-audio\" controls=\"\" src=\"assets/acme/chime.mp3\">"))
    (is (str/includes? page "<iframe "))
    (is (str/includes? page "src=\"assets/acme/embed.html\""))))

(deftest content-kinds-are-all-exercised
  (testing "stepped code highlight"
    (is (str/includes? page "class=\"language-clojure\""))
    (is (str/includes? page "data-line-numbers=\"1|3-5|\"")))
  (testing "native markdown section and a markdown block"
    (is (str/includes? page "<section id=\"release-notes\"><div data-markdown=\"\">"))
    (is (str/includes? page "<textarea data-template=\"\">## Shipped in Q3"))
    (is (str/includes? page "<div data-markdown=\"\">")))
  (testing "math delimiters survive serialization"
    (is (str/includes? page "\\[ m = \\frac{R - C_v}{R}")))
  (testing "table"
    (is (str/includes? page "<table class=\"plato-table\">"))
    (is (str/includes? page "<th>Metric</th>"))
    (is (= 4 (count (re-seq #"<tr><td>" page)))))
  (testing "cards, columns, quote, note, kicker, fragments"
    (is (str/includes? page "<div class=\"plato-grid\""))
    (is (str/includes? page "<div class=\"plato-columns\""))
    (is (str/includes? page "<blockquote class=\"plato-quote\">"))
    (is (str/includes? page "<footer class=\"plato-cite\">"))
    (is (str/includes? page "plato-note-warn"))
    (is (str/includes? page "<div class=\"plato-kicker\">"))
    (is (str/includes? page "class=\"fragment fade-up\"")))
  (testing "ordered list in the vertical stack"
    (is (str/includes? page "<ol class=\"plato-list\">")))
  (testing "auto-animate pair"
    (is (= 2 (count (re-seq #"data-auto-animate=\"\"" page))))
    (is (= 2 (count (re-seq #"data-id=\"focus-value\"" page))))
    (is (str/includes? page "data-auto-animate-duration=\"1.2\"")))
  (testing "closing gradient"
    (is (str/includes? page "data-background-gradient=\"linear-gradient(135deg,")))
  (testing "speaker notes become asides"
    (is (str/includes? page "<aside class=\"notes\">"))))
