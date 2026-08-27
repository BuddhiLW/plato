(ns plato.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.hiccup :as h]
            [plato.markdown :as md]))

(defn- sections [text] (:sections (md/parse text)))
(defn- blocks [text] (:blocks (first (sections text))))
(defn- opts [text] (:opts (first (sections text))))

(deftest front-matter-lifts-title
  (let [document (md/parse "---\ntitle: My Deck\nauthor: Ada\ntheme: \"black\"\n---\n\n# One\n")]
    (is (= "My Deck" (:title document)))
    (is (= {:author "Ada" :theme "black"} (:meta document)))
    (is (= [:one] (mapv :id (:sections document))))))

(deftest front-matter-must-be-terminated
  (let [document (md/parse "---\ntitle: x\n\n# H\n")]
    (is (nil? (:title document)))
    (is (= {} (:meta document)))
    (is (= [[:p "title: x"]] (:blocks (first (:sections document)))))))

(deftest headings-nest-into-vertical-sections
  (let [ss (sections "# One\n\n## A\n\n## B\n\n# Two\n\n### Deep\n#### Deeper\n")]
    (is (= [:one :two] (mapv :id ss)))
    (is (= [1 1] (mapv :level ss)))
    (is (= [:a :b] (mapv :id (:children (first ss)))))
    (is (= [2 2] (mapv :level (:children (first ss)))))
    (is (= [[:h3 "Deep"] [:h4 "Deeper"]] (:blocks (second ss))))))

(deftest separators-open-sections
  (let [ss (sections "# One\n\ntext\n\n---\n\nsecond\n\n--\n\nvertical\n")]
    (is (= 2 (count ss)))
    (is (= "One" (:title (first ss))))
    (is (nil? (:title (second ss))))
    (is (= [[:p "second"]] (:blocks (second ss))))
    (is (= [[:p "vertical"]] (:blocks (first (:children (second ss))))))))

(deftest note-becomes-slide-notes
  (let [ss (sections "# One\n\nbody\n\nNote: say this\nand that\n\n# Two\n")]
    (is (= [:p "say this and that"] (:notes (:opts (first ss)))))
    (is (= [[:p "body"]] (:blocks (first ss))))
    (is (= {} (:opts (second ss)))))
  (testing "note lines are parsed, not captured as raw text"
    (let [ss (sections "# One\n\nNote: lead\n\n- a\n- b\n")]
      (is (= {:plato/type :group
              :items [[:p "lead"] {:plato/type :bullets :items ["a" "b"]}]}
             (:notes (:opts (first ss))))))))

(deftest slide-comment-becomes-slide-opts
  (is (= {:background-color "#111" :transition "fade"}
         (opts "# One\n\n<!-- .slide: data-background-color=\"#111\" data-transition=\"fade\" -->\n\nbody\n"))))

(deftest inline-formatting
  (is (= [[:p "Plain "
           [:strong "strong"] " "
           [:em "star"] " "
           [:em "under"] " "
           [:code "code"] " "
           [:del "gone"] " "
           [:a {:href "http://x.y"} "link"] " "
           [:img {:src "i.png" :alt "alt"}] "."]]
         (blocks "# H\n\nPlain **strong** *star* _under_ `code` ~~gone~~ [link](http://x.y) ![alt](i.png).\n")))
  (is (= [[:p "a snake_case_word and *escaped* stars"]]
         (blocks "# H\n\na snake_case_word and \\*escaped\\* stars\n"))))

(deftest paragraphs-join-soft-line-breaks
  (is (= [[:p "one two"] [:p "three"]]
         (blocks "# H\n\none\ntwo\n\nthree\n"))))

(deftest lists
  (let [bs (blocks "# H\n\n- one\n* two\n+ three\n\n1. first\n2) second\n")]
    (is (= {:plato/type :bullets :items ["one" "two" "three"]} (nth bs 0)))
    (is (= {:plato/type :bullets :items ["first" "second"] :ordered? true} (nth bs 1)))))

(deftest list-items-carry-inline-markup
  (let [item (first (:items (first (blocks "# H\n\n- a **b** c\n"))))]
    (is (= '("a " [:strong "b"] " c") item))
    (is (= "<li>a <strong>b</strong> c</li>"
           (h/->html [:li (content/expand item)])))))

(deftest fenced-code-block
  (is (= {:plato/type :code :lang :clojure :source "(+ 1 2)\n(dec 3)"}
         (first (blocks "# H\n\n```clojure\n(+ 1 2)\n(dec 3)\n```\n"))))
  (is (= {:plato/type :code :lang nil :source "plain"}
         (first (blocks "# H\n\n~~~\nplain\n~~~\n")))))

(deftest blockquote
  (is (= {:plato/type :quote :text [:p "one two " [:strong "three"]]}
         (first (blocks "# H\n\n> one\n> two **three**\n")))))

(deftest gfm-table
  (is (= {:plato/type :table :head ["a" "b"] :rows [["1" "2"] ["3" "4"]]}
         (first (blocks "# H\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n")))))

(deftest table-with-no-body-row
  (is (= {:plato/type :table :head ["a" "b"] :rows []}
         (first (blocks "# H\n\n| a | b |\n| --- | --- |\n")))))

(deftest table-without-a-separator-is-all-body
  (is (= {:plato/type :table :head [] :rows [["1" "2"]]}
         (first (blocks "# H\n\n| 1 | 2 |\n")))))

(deftest thematic-break
  (is (= [[:p "a"] [:hr] [:p "b"]] (blocks "# H\n\na\n\n***\n\nb\n"))))

(deftest standalone-media-lines
  (let [bs (blocks "# H\n\n![A cat](cat.png \"Caption\")\n\n![](clip.mp4)\n\n![](tune.MP3?v=2)\n")]
    (is (= {:plato/type :image :src "cat.png" :alt "A cat" :caption "Caption"} (nth bs 0)))
    (is (= :video (:plato/type (nth bs 1))))
    (is (= "clip.mp4" (:src (nth bs 1))))
    (is (= :audio (:plato/type (nth bs 2))))))

;; ── adversarial ─────────────────────────────────────────────────────────────

(deftest empty-input-has-no-sections
  (is (= {:title nil :meta {} :config {} :sections []} (md/parse "")))
  (is (= [] (:sections (md/parse nil))))
  (is (= [] (:sections (md/parse "\n\n   \n"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one slide" (md/->deck ""))))

(deftest a-document-with-no-headings-is-one-untitled-section
  (let [ss (sections "just a paragraph\n\nand another\n")]
    (is (= 1 (count ss)))
    (is (nil? (:title (first ss))))
    (is (= :section (:id (first ss))))
    (is (= [[:p "just a paragraph"] [:p "and another"]] (:blocks (first ss))))))

(deftest crlf-line-endings
  (let [ss (sections "# One\r\n\r\ntext\r\n\r\n## Sub\r\n\r\nmore\r\n")]
    (is (= [:one] (mapv :id ss)))
    (is (= [[:p "text"]] (:blocks (first ss))))
    (is (= [[:p "more"]] (:blocks (first (:children (first ss))))))))

(deftest unterminated-fence-runs-to-the-end-of-the-section
  (is (= {:plato/type :code :lang :clj :source "(inc 1)"}
         (first (blocks "# F\n\n```clj\n(inc 1)\n")))))

(deftest separators-inside-a-fence-do-not-split-the-slide
  (let [ss (sections "# F\n\n```\n---\n--\n# nope\nNote: not a note\n```\n\ntail\n")]
    (is (= 1 (count ss)))
    (is (= [] (:children (first ss))))
    (is (= {} (:opts (first ss))))
    (is (= "---\n--\n# nope\nNote: not a note" (:source (first (:blocks (first ss))))))
    (is (= [[:p "tail"]] (rest (:blocks (first ss)))))))

;; ── end to end ──────────────────────────────────────────────────────────────

(deftest markdown-compiles-to-a-validated-deck
  (let [model (md/->deck "---\ntitle: Demo\n---\n\n# Same\n\nbody\n\n## Same\n\nsub\n\n# Same\n"
                         {:config {:transition :none}})]
    (is (= "Demo" (:title model)))
    (is (= [:stack :slide] (mapv :plato/type (:slides model))))
    (is (= [:same :same-2 :same-3] (mapv :id (deck/leaf-slides model))))
    (is (= :none (get-in model [:config :transition])))))

(deftest rendered-slide-html
  (let [model (md/->deck "# Title\n\nHello **world**\n\n- one\n- two\n")
        slide (first (deck/leaf-slides model))]
    (is (= (str "<div class=\"plato-group\"><h1>Title</h1>"
                "<p>Hello <strong>world</strong></p>"
                "<ul class=\"plato-list\"><li>one</li><li>two</li></ul></div>")
           (h/->html (content/render (:content slide)))))
    (is (str/starts-with? (:id (deck/section-attrs slide)) "title"))))
