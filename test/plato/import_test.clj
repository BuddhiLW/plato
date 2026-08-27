(ns plato.import-test
  "docs/acme.md and docs/acme.org are the same deck authored twice. Every
   assertion here holds for both front ends."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.html :as html]
            [plato.markdown :as md]
            [plato.org :as org]))

;; ── fixtures ────────────────────────────────────────────────────────────────

(def sources
  "Front-end key -> the authored document, read from the repo root."
  {:markdown (slurp "docs/acme.md")
   :org (slurp "docs/acme.org")})

(def documents
  {:markdown (md/parse (:markdown sources))
   :org (org/parse (:org sources))})

(def decks
  {:markdown (md/->deck (:markdown sources))
   :org (org/->deck (:org sources))})

(def pages (update-vals decks html/deck->html))

(def front-ends [:markdown :org])

(def slide-ids
  "Leaf slides of both documents, in document order."
  [:q3-product-review :agenda :the-numbers :revenue-chart :growth-loop
   :the-product :replay-console :the-new-chime :the-deck-is-data
   :voice-of-customer :closing])

(def stack-ids [:the-numbers-stack :the-product-stack])

(defn- flatten-sections [sections]
  (mapcat (fn [sec] (cons sec (flatten-sections (:children sec)))) sections))

(defn- sections
  "Every section of `front-end`'s document IR, parents before children."
  [front-end]
  (flatten-sections (:sections (documents front-end))))

(defn- leaves [front-end] (deck/leaf-slides (decks front-end)))

(defn- leaf [front-end id]
  (first (filter #(= id (:id %)) (leaves front-end))))

(defn- body
  "Content values of a leaf slide, the section heading dropped."
  [front-end id]
  (rest (get-in (leaf front-end id) [:content :items])))

(def media-keys #{:src :poster :background-image :background-video})

(defn- media-paths [value]
  (into #{}
        (comp (filter map?)
              (mapcat (fn [m]
                        (concat (keep #(get m %) media-keys)
                                (keep :src (:sources m))))))
        (tree-seq coll? seq value)))

;; ── agreement ───────────────────────────────────────────────────────────────

(deftest slide-inventory-is-identical
  (doseq [front-end front-ends]
    (is (= slide-ids (mapv :id (leaves front-end))) (str front-end))
    (is (= slide-ids (mapv :id (sections front-end))) (str front-end)))
  (is (= (mapv :id (leaves :markdown)) (mapv :id (leaves :org)))))

(deftest front-ends-compile-to-the-same-deck
  (testing "the IR, the deck and the rendered page all agree"
    (is (= (documents :markdown) (documents :org)))
    (is (= (decks :markdown) (decks :org)))
    (is (= (pages :markdown) (pages :org)))))

(deftest document-metadata-agrees
  (doseq [front-end front-ends]
    (let [document (documents front-end)]
      (is (= "Acme Corp — Q3 Product Review" (:title document)) (str front-end))
      (is (= {:author "Ada Lovelace" :date "26 August 2026" :theme "night"}
             (:meta document))
          (str front-end)))))

(deftest inline-formatting-agrees
  (doseq [front-end front-ends]
    (is (= [[:p "Revenue, " [:strong "delivery"] ", and the " [:em "three bets"]
             " that carry us into " [:a {:href "https://acme.example/q4"} "Q4"] "."]]
           (body front-end :q3-product-review))
        (str front-end))
    (is (= [[:p "A slide is a map; " [:code "deck/deck"] " validates the whole tree."]]
           (rest (body front-end :the-deck-is-data)))
        (str front-end))
    (is (= [[:p "Ship the replay lane. " [:del "Halve"] " Quarter the cost per event."]]
           (body front-end :closing))
        (str front-end))))

;; ── deck shape ──────────────────────────────────────────────────────────────

(deftest decks-validate
  (doseq [front-end front-ends]
    (let [model (decks front-end)]
      (is (= :deck (:plato/type model)) (str front-end))
      (is (= model (deck/deck model)) (str front-end))
      (is (= (count slide-ids) (count (set (mapv :id (leaves front-end)))))
          (str front-end))
      (is (true? (get-in model [:config :hash])) (str front-end)))))

(deftest vertical-sections-become-stacks
  (doseq [front-end front-ends]
    (let [model (decks front-end)
          stacks (filter #(= :stack (:plato/type %)) (:slides model))]
      (is (= stack-ids (mapv :id stacks)) (str front-end))
      (is (= [:the-numbers :revenue-chart :growth-loop]
             (mapv :id (:slides (first stacks))))
          (str front-end))
      (is (= [:the-product :replay-console :the-new-chime]
             (mapv :id (:slides (second stacks))))
          (str front-end)))))

(deftest speaker-notes-survive
  ;; Notes are parsed like any other body, so they arrive as content values —
  ;; identically from both front ends.
  (doseq [front-end front-ends]
    (is (= {:q3-product-review [:p "Ninety seconds of framing, then straight into the agenda."]
            :agenda [:p "Five beats. Hold questions until the risks slide."]
            :growth-loop [:p "Two seconds of the live ticker, on a loop."]
            :the-deck-is-data [:p "Two files, one deck. The markup is a front end, not the model."]
            :closing [:p "End here. Do not advance into the appendix unless asked."]}
           (into {} (keep (fn [s] (when (:notes s) [(:id s) (:notes s)])))
                 (leaves front-end)))
        (str front-end))))

(deftest slide-options-survive
  (doseq [front-end front-ends]
    (testing "HTML comment / PROPERTIES drawer reach the <section> attributes"
      (is (= {:id "q3-product-review"
              :data-background-image "assets/acme/hero.jpg"
              :data-background-opacity "0.35"
              :data-transition "fade"}
             (deck/section-attrs (leaf front-end :q3-product-review)))
          (str front-end))
      (is (= {:id "the-new-chime" :data-transition "zoom"}
             (deck/section-attrs (leaf front-end :the-new-chime)))
          (str front-end))
      (is (= {:id "closing" :data-background-color "#12100e"}
             (deck/section-attrs (leaf front-end :closing)))
          (str front-end)))))

;; ── content ─────────────────────────────────────────────────────────────────

(deftest blocks-resolve-to-the-expected-content-kinds
  (doseq [front-end front-ends]
    (is (= {:q3-product-review [:hiccup]
            :agenda [:bullets]
            :the-numbers [:table :hiccup :bullets]
            :revenue-chart [:image]
            :growth-loop [:image]
            :the-product [:hiccup]
            :replay-console [:video]
            :the-new-chime [:audio]
            :the-deck-is-data [:code :hiccup]
            :voice-of-customer [:quote]
            :closing [:hiccup]}
           (into {} (map (fn [id] [id (mapv content/kind (body front-end id))]))
                 slide-ids))
        (str front-end))))

(deftest media-blocks-carry-their-sources
  (doseq [front-end front-ends]
    (is (= {:plato/type :image
            :src "assets/acme/chart.png"
            :alt "Bar chart of booked revenue"
            :caption "Booked revenue, FY26 Q1–Q3"}
           (first (body front-end :revenue-chart)))
        (str front-end))
    (is (= {:plato/type :image
            :src "assets/acme/loop.gif"
            :alt "Throughput ticker loop"}
           (first (body front-end :growth-loop)))
        (str front-end))
    (is (= {:plato/type :video :src "assets/acme/demo.mp4" :controls? true}
           (first (body front-end :replay-console)))
        (str front-end))
    (is (= {:plato/type :audio :src "assets/acme/chime.mp3" :controls? true}
           (first (body front-end :the-new-chime)))
        (str front-end))))

(deftest code-quote-table-and-lists-agree
  (doseq [front-end front-ends]
    (is (= {:plato/type :code
            :lang :clojure
            :source (str "(deck/slide :metrics\n"
                         "  (content/table [\"Metric\" \"Q2\" \"Q3\"]\n"
                         "                 [[\"ARR\" \"$96.0M\" \"$118.4M\"]]))")}
           (first (body front-end :the-deck-is-data)))
        (str front-end))
    (is (= {:plato/type :quote
            :text [:p "We replaced four internal dashboards with one Acme replay link."]}
           (first (body front-end :voice-of-customer)))
        (str front-end))
    (let [[table heading items] (body front-end :the-numbers)]
      (is (= ["Metric" "Q2 FY26" "Q3 FY26"] (:head table)) (str front-end))
      (is (= [["ARR" "$96.0M" "$118.4M"]
              ["Net revenue retention" "112%" "119%"]
              ["Gross margin" "61.0%" "65.2%"]]
             (:rows table))
          (str front-end))
      (is (= [:h3 "Follow-ups"] heading) (str front-end))
      (is (true? (:ordered? items)) (str front-end))
      (is (= 3 (count (:items items))) (str front-end)))
    (let [bullets (first (body front-end :agenda))]
      (is (nil? (:ordered? bullets)) (str front-end))
      (is (= ["Where the numbers landed"
              "What shipped, and what it looks like"
              "Three bets for Q4"
              "Risks we are carrying"]
             (:items bullets))
          (str front-end)))))

;; ── assets ──────────────────────────────────────────────────────────────────

(deftest every-media-source-exists
  (doseq [front-end front-ends]
    (let [paths (media-paths (decks front-end))]
      (is (= #{"assets/acme/hero.jpg" "assets/acme/chart.png" "assets/acme/loop.gif"
               "assets/acme/demo.mp4" "assets/acme/chime.mp3"}
             paths)
          (str front-end))
      (doseq [path (sort paths)]
        (is (str/starts-with? path "assets/acme/") path)
        (is (.exists (io/file "public" path))
            (str "missing fixture: public/" path)))))
  (is (= (media-paths (decks :markdown)) (media-paths (decks :org)))))

;; ── html ────────────────────────────────────────────────────────────────────

(deftest html-carries-every-section-id
  (doseq [front-end front-ends]
    (let [page (pages front-end)
          ids (mapv second (re-seq #"<section id=\"([^\"]+)\"" page))]
      (is (str/starts-with? page "<!doctype html>") (str front-end))
      (is (str/includes? page "<title>Acme Corp — Q3 Product Review</title>")
          (str front-end))
      (testing "one <section> per leaf slide plus one per stack"
        (is (= (+ (count slide-ids) (count stack-ids))
               (count (re-seq #"<section" page)))
            (str front-end)))
      (doseq [id slide-ids]
        (is (str/includes? page (str "id=\"" (name id) "\"")) (str front-end " " id)))
      (testing "a stack wraps its vertical slides under its own distinct id"
        (is (str/includes? page "<section id=\"the-numbers-stack\"><section id=\"the-numbers\"")
            (str front-end))
        (is (str/includes? page "<section id=\"the-product-stack\"><section id=\"the-product\"")
            (str front-end)))
      (testing "no id appears twice in the document"
        (is (= (count ids) (count (set ids))) (str front-end " " (pr-str ids)))))))

(deftest html-carries-the-slide-attributes-and-notes
  (doseq [front-end front-ends]
    (let [page (pages front-end)]
      (is (str/includes? page "data-background-image=\"assets/acme/hero.jpg\"") (str front-end))
      (is (str/includes? page "data-background-opacity=\"0.35\"") (str front-end))
      (is (str/includes? page "data-transition=\"zoom\"") (str front-end))
      (is (str/includes? page "data-background-color=\"#12100e\"") (str front-end))
      (is (= 5 (count (re-seq #"<aside class=\"notes\">" page))) (str front-end))
      (is (str/includes? page "<aside class=\"notes\"><p>Five beats.") (str front-end)))))

(deftest html-carries-the-media-elements
  (doseq [front-end front-ends]
    (let [page (pages front-end)]
      (is (str/includes? page "<img alt=\"Bar chart of booked revenue\" src=\"assets/acme/chart.png\">")
          (str front-end))
      (is (str/includes? page "<img alt=\"Throughput ticker loop\" src=\"assets/acme/loop.gif\">")
          (str front-end))
      (is (str/includes? page "<video class=\"plato-video\" playsInline=\"\" src=\"assets/acme/demo.mp4\" controls=\"\">")
          (str front-end))
      (is (str/includes? page "<audio src=\"assets/acme/chime.mp3\" class=\"plato-audio\" controls=\"\">")
          (str front-end))
      (is (str/includes? page "<figcaption class=\"plato-caption\">Booked revenue, FY26 Q1–Q3</figcaption>")
          (str front-end)))))

(deftest html-carries-the-prose-blocks
  (doseq [front-end front-ends]
    (let [page (pages front-end)]
      (is (str/includes? page "<h1>Q3 Product Review</h1>") (str front-end))
      (is (str/includes? page "<h2>Revenue Chart</h2>") (str front-end))
      (is (str/includes? page "<h3>Follow-ups</h3>") (str front-end))
      (is (str/includes? page "<code class=\"language-clojure\" data-trim=\"\">") (str front-end))
      (is (str/includes? page "<blockquote class=\"plato-quote\">") (str front-end))
      (is (str/includes? page "<table class=\"plato-table\">") (str front-end))
      (is (str/includes? page "<th>Metric</th>") (str front-end))
      (is (str/includes? page "<ul class=\"plato-list\">") (str front-end))
      (is (str/includes? page "<ol class=\"plato-list\">") (str front-end))
      (is (str/includes? page "<strong>delivery</strong>") (str front-end))
      (is (str/includes? page "<em>three bets</em>") (str front-end))
      (is (str/includes? page "<del>Halve</del>") (str front-end))
      (is (str/includes? page "<a href=\"https://acme.example/q4\">Q4</a>") (str front-end)))))
