(ns plato.org-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.hiccup :as h]
            [plato.org :as org]))

(defn- sections [text] (:sections (org/parse text)))
(defn- blocks [text] (:blocks (first (sections text))))
(defn- opts [text] (:opts (first (sections text))))

(deftest file-keywords-lift-title
  (let [document (org/parse "#+TITLE: My Deck\n#+AUTHOR: Ada\n#+THEME: black\n\n* One\n")]
    (is (= "My Deck" (:title document)))
    (is (= {:author "Ada" :theme "black"} (:meta document)))
    (is (= [:one] (mapv :id (:sections document))))))

(deftest file-keywords-are-read-from-the-preamble-only
  (let [document (org/parse "* One\n#+AUTHOR: Ada\n\nbody\n")]
    (is (nil? (:title document)))
    (is (= {} (:meta document)))
    (is (= [[:p "body"]] (:blocks (first (:sections document))))))
  (let [document (org/parse "#+TITLE: T\n\nintro\n\n* One\n")]
    (is (= "T" (:title document)))
    (is (= [:section :one] (mapv :id (:sections document))))
    (is (= [[:p "intro"]] (:blocks (first (:sections document)))))))

(deftest headlines-nest-into-vertical-sections
  (let [ss (sections "* One\n\n** A\n\n** B\n\n* Two\n\n*** Deep\n**** Deeper\n")]
    (is (= [:one :two] (mapv :id ss)))
    (is (= [1 1] (mapv :level ss)))
    (is (= [:a :b] (mapv :id (:children (first ss)))))
    (is (= [2 2] (mapv :level (:children (first ss)))))
    (is (= [[:h3 "Deep"] [:h4 "Deeper"]] (:blocks (second ss))))))

(deftest notes-block-becomes-slide-notes
  (let [ss (sections "* One\n\nbody\n\n#+BEGIN_NOTES\nsay this\nand that\n#+END_NOTES\n\n* Two\n")]
    (is (= [:p "say this and that"] (:notes (:opts (first ss)))))
    (is (= [[:p "body"]] (:blocks (first ss))))
    (is (= {} (:opts (second ss))))))

(deftest notes-drawer-becomes-slide-notes
  (is (= {:notes [:p "drawer note"]} (opts "* One\n:NOTES:\ndrawer note\n:END:\n")))
  (testing "a * line inside the drawer is note content, not a headline"
    (let [ss (sections "* One\n:NOTES:\n- a\n- b\n:END:\n\nbody\n")]
      (is (= 1 (count ss)))
      (is (= {:plato/type :bullets :items ["a" "b"]} (:notes (:opts (first ss)))))
      (is (= [[:p "body"]] (:blocks (first ss))))))
  (testing "an unterminated drawer is ordinary text, not a black hole"
    (let [ss (sections "* One\n:NOTES:\nstray\n\n* Two\n\nbody\n")]
      (is (= ["One" "Two"] (mapv :title ss)))
      (is (nil? (:notes (:opts (first ss)))))
      (is (= [[:p "body"]] (:blocks (second ss)))))))

(deftest properties-drawer-becomes-slide-opts
  (let [section (first (sections (str "* One\n:PROPERTIES:\n:BACKGROUND_COLOR: #111\n"
                                      ":TRANSITION: fade\n:BACKGROUND_IMAGE: bg.png\n"
                                      ":AUTO_ANIMATE: t\n:CUSTOM_ID: intro\n:END:\n\nbody\n")))]
    (is (= :intro (:id section)))
    (is (= {:background-color "#111"
            :transition "fade"
            :background-image "bg.png"
            :auto-animate true}
           (:opts section)))
    (is (= [[:p "body"]] (:blocks section)))))

(deftest inline-formatting
  (is (= [[:p "Plain "
           [:strong "strong"] " "
           [:em "italic"] " "
           [:u "under"] " "
           [:code "verb"] " "
           [:code "code"] " "
           [:del "gone"] " "
           [:a {:href "http://x.y"} "link"] " "
           [:a {:href "http://z.w"} "http://z.w"] "."]]
         (blocks (str "* H\n\nPlain *strong* /italic/ _under_ =verb= ~code~ +gone+ "
                      "[[http://x.y][link]] [[http://z.w]].\n")))))

(deftest paragraphs-join-soft-line-breaks
  (is (= [[:p "one two"] [:p "three"]]
         (blocks "* H\n\none\ntwo\n\nthree\n"))))

(deftest lists
  (let [bs (blocks "* H\n\n- one\n+ two\n\n1. first\n2) second\n")]
    (is (= {:plato/type :bullets :items ["one" "two"]} (nth bs 0)))
    (is (= {:plato/type :bullets :items ["first" "second"] :ordered? true} (nth bs 1)))))

(deftest list-items-carry-inline-markup
  (let [item (first (:items (first (blocks "* H\n\n- a *b* c\n"))))]
    (is (= '("a " [:strong "b"] " c") item))
    (is (= "<li>a <strong>b</strong> c</li>"
           (h/->html [:li (content/expand item)])))))

(deftest checkbox-items-render-as-glyphs
  (is (= ["☐ todo" "☑ done" "☐ partial"]
         (:items (first (blocks "* H\n\n- [ ] todo\n- [X] done\n- [-] partial\n"))))))

(deftest src-block
  (is (= {:plato/type :code :lang :clojure :source "(+ 1 2)\n(dec 3)"}
         (first (blocks "* H\n\n#+BEGIN_SRC clojure\n(+ 1 2)\n(dec 3)\n#+END_SRC\n"))))
  (is (= {:plato/type :code :lang :clj :source "(inc 1)"}
         (first (blocks "* H\n\n#+begin_src clj :results none\n(inc 1)\n#+end_src\n")))))

(deftest example-block
  (is (= {:plato/type :code :lang :text :source "plain"}
         (first (blocks "* H\n\n#+BEGIN_EXAMPLE\nplain\n#+END_EXAMPLE\n")))))

(deftest quote-block
  (is (= {:plato/type :quote :text [:p "one two " [:strong "three"]]}
         (first (blocks "* H\n\n#+BEGIN_QUOTE\none\ntwo *three*\n#+END_QUOTE\n")))))

(deftest export-block
  (is (= {:plato/type :html :html "<b>hi</b>"}
         (first (blocks "* H\n\n#+BEGIN_EXPORT html\n<b>hi</b>\n#+END_EXPORT\n")))))

(deftest org-table
  (is (= {:plato/type :table :head ["a" "b"] :rows [["1" "2"] ["3" "4"]]}
         (first (blocks "* H\n\n| a | b |\n|---+---|\n| 1 | 2 |\n| 3 | 4 |\n"))))
  (is (= {:plato/type :table :head ["a" "b"] :rows [["1" "2"]]}
         (first (blocks "* H\n\n| a | b |\n|-----|\n| 1 | 2 |\n|-----|\n")))))

(deftest table-with-no-body-row
  (is (= {:plato/type :table :head ["a" "b"] :rows []}
         (first (blocks "* H\n\n| a | b |\n|---|\n")))))

(deftest table-without-a-separator-is-all-body
  (is (= {:plato/type :table :head [] :rows [["1" "2"]]}
         (first (blocks "* H\n\n| 1 | 2 |\n")))))

(deftest links
  (is (= [[:p [:a {:href "https://x.y/a/b"} "a " [:strong "b"] " c"]]]
         (blocks "* H\n\n[[https://x.y/a/b][a *b* c]]\n")))
  (is (= [[:p "see " [:a {:href "notes.org"} "the notes"]]]
         (blocks "* H\n\nsee [[file:notes.org][the notes]]\n"))))

(deftest standalone-media-links
  (let [bs (blocks "* H\n\n[[file:cat.png][A cat]]\n\n[[clip.mp4]]\n\n[[tune.MP3?v=2]]\n")]
    (is (= {:plato/type :image :src "cat.png" :alt "A cat"} (nth bs 0)))
    (is (= :video (:plato/type (nth bs 1))))
    (is (= "clip.mp4" (:src (nth bs 1))))
    (is (= :audio (:plato/type (nth bs 2)))))
  (is (= {:plato/type :image :src "notes.org"}
         (first (blocks "* H\n\n[[file:notes.org]]\n")))))

(deftest caption-and-attr-lines-decorate-the-next-block
  (is (= {:plato/type :image :src "pic.png" :caption "A caption" :width "320" :lazy? true}
         (first (blocks "* H\n\n#+CAPTION: A caption\n#+ATTR_PLATO: :width 320 :lazy? t\n[[pic.png]]\n"))))
  (is (= {:plato/type :code :lang :clj :source "x" :caption "Snippet" :highlight "1-2"}
         (first (blocks "* H\n\n#+CAPTION: Snippet\n#+ATTR_PLATO: :highlight 1-2\n#+BEGIN_SRC clj\nx\n#+END_SRC\n"))))
  (is (= {:plato/type :image :src "pic.png"}
         (first (blocks "* H\n\n#+CAPTION: A caption\n\n[[pic.png]]\n")))))

;; ── adversarial ─────────────────────────────────────────────────────────────

(deftest empty-input-has-no-sections
  (is (= {:title nil :meta {} :config {} :sections []} (org/parse "")))
  (is (= [] (:sections (org/parse nil))))
  (is (= [] (:sections (org/parse "\n\n   \n"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one slide" (org/->deck ""))))

(deftest a-document-with-no-headlines-is-one-untitled-section
  (let [ss (sections "just a paragraph\n\nand another\n")]
    (is (= 1 (count ss)))
    (is (nil? (:title (first ss))))
    (is (= :section (:id (first ss))))
    (is (= [[:p "just a paragraph"] [:p "and another"]] (:blocks (first ss))))))

(deftest crlf-line-endings
  (let [ss (sections "* One\r\n\r\ntext\r\n\r\n** Sub\r\n\r\nmore\r\n")]
    (is (= [:one] (mapv :id ss)))
    (is (= [[:p "text"]] (:blocks (first ss))))
    (is (= [[:p "more"]] (:blocks (first (:children (first ss))))))))

(deftest todo-keyword-and-tags-are-stripped-from-the-title
  (let [section (first (sections "* TODO Build the thing :work:urgent:\n\nbody\n"))]
    (is (= "Build the thing" (:title section)))
    (is (= :build-the-thing (:id section)))
    (is (= ["work" "urgent"] (:tags (:opts section)))))
  (is (= "Ship it" (:title (first (sections "* DONE [#A] Ship it :ship:\n")))))
  (is (= "API design" (:title (first (sections "* API design\n"))))))

(deftest a-slash-inside-a-url-is-not-emphasis
  (is (= [[:p "see https://example.com/a/b now"]]
         (blocks "* H\n\nsee https://example.com/a/b now\n"))))

(deftest markers-bind-only-at-word-boundaries
  (is (= [[:p "a snake_case_word and 2*3*4 here"]]
         (blocks "* H\n\na snake_case_word and 2*3*4 here\n")))
  (is (= [[:p "spaced * out * markers"]]
         (blocks "* H\n\nspaced * out * markers\n"))))

(deftest unterminated-block-runs-to-the-end-of-the-section
  (is (= {:plato/type :code :lang :clj :source "(inc 1)"}
         (first (blocks "* F\n\n#+BEGIN_SRC clj\n(inc 1)\n")))))

(deftest headlines-inside-a-block-do-not-split-the-slide
  (let [ss (sections "* F\n\n#+BEGIN_SRC\n* nope\n** also nope\n#+END_SRC\n\ntail\n")]
    (is (= 1 (count ss)))
    (is (= [] (:children (first ss))))
    (is (= {} (:opts (first ss))))
    (is (= "* nope\n** also nope" (:source (first (:blocks (first ss))))))
    (is (= [[:p "tail"]] (rest (:blocks (first ss)))))))

(deftest comments-are-ignored
  (is (= [[:p "body"]] (blocks "* H\n\n# a comment\n\nbody\n")))
  (is (= [[:p "body"]] (blocks "* H\n\n#+OPTIONS: toc:nil\nbody\n"))))

;; ── end to end ──────────────────────────────────────────────────────────────

(deftest org-compiles-to-a-validated-deck
  (let [model (org/->deck "#+TITLE: Demo\n\n* Same\n\nbody\n\n** Same\n\nsub\n\n* Same\n"
                          {:config {:transition :none}})]
    (is (= "Demo" (:title model)))
    (is (= [:stack :slide] (mapv :plato/type (:slides model))))
    (is (= [:same :same-2 :same-3] (mapv :id (deck/leaf-slides model))))
    (is (= :none (get-in model [:config :transition])))))

(deftest custom-id-becomes-the-slide-id
  (let [model (org/->deck "* One\n:PROPERTIES:\n:CUSTOM_ID: intro\n:TRANSITION: fade\n:END:\n\nbody\n")
        slide (first (deck/leaf-slides model))]
    (is (= [:intro] (mapv :id (deck/leaf-slides model))))
    (is (= {:id "intro" :data-transition "fade"} (deck/section-attrs slide)))))

(deftest rendered-slide-html
  (let [model (org/->deck "* Title\n\nHello *world*\n\n- one\n- two\n")
        slide (first (deck/leaf-slides model))]
    (is (= (str "<div class=\"plato-group\"><h1>Title</h1>"
                "<p>Hello <strong>world</strong></p>"
                "<ul class=\"plato-list\"><li>one</li><li>two</li></ul></div>")
           (h/->html (content/render (:content slide)))))
    (is (str/starts-with? (:id (deck/section-attrs slide)) "title"))))
