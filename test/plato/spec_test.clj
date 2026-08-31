(ns plato.spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.doc :as doc]
            [plato.markdown :as markdown]
            [plato.org :as org]
            [plato.spec :as spec]))

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn- ids
  "Every component id in the spec, in tree order."
  [x]
  (cond
    (map? x) (concat (when-let [id (:id x)] [id]) (mapcat ids (:children x)))
    (sequential? x) (mapcat ids x)
    :else nil))

(defn- kinds [x]
  (cond
    (map? x) (concat (when-let [k (:kind x)] [k]) (mapcat kinds (:children x)))
    (sequential? x) (mapcat kinds x)
    :else nil))

(defn- find-kind [x k]
  (cond
    (map? x) (if (= k (:kind x)) x (some #(find-kind % k) (:children x)))
    (sequential? x) (some #(find-kind % k) x)
    :else nil))

;; ── inline projection ───────────────────────────────────────────────────────

(deftest plain-text-becomes-one-span
  (is (= [{:kind "span" :variant "default" :props {:text "hello"}}]
         (spec/inlines "hello"))))

(deftest marks-accumulate-through-nesting
  (let [runs (spec/inlines [:p "plain " [:strong "bold " [:em "both"]]])]
    (is (= ["plain " "bold " "both"] (map (comp :text :props) runs)))
    (is (= [nil ["strong"] ["emph" "strong"]] (map (comp :marks :props) runs)))))

(deftest a-link-keeps-its-href
  (is (= {:kind "link" :variant "default"
          :props {:text "plato" :href "https://example.com"}}
         (first (spec/inlines [:a {:href "https://example.com"} "plato"])))))

(deftest empty-text-contributes-no-span
  (is (= [] (spec/inlines "")))
  (is (= [] (spec/inlines [:p ""]))))

;; ── block projection ────────────────────────────────────────────────────────

(deftest headings-carry-their-level
  (let [b (spec/block [:h3 "Revenue"])]
    (is (= "heading" (:kind b)))
    (is (= 3 (-> b :props :level)))
    (is (= "Revenue" (-> b :children first :props :text)))))

(deftest bullets-project-items-and-flags
  (let [b (spec/block (content/bullets ["a" "b"] {:ordered? true :fragments? true}))]
    (is (= "bullets" (:kind b)))
    (is (= ["a" "b"] (-> b :props :items)))
    (is (true? (-> b :props :ordered)))
    (is (true? (-> b :props :fragments)))))

(deftest code-carries-language-and-highlight
  (let [b (spec/block (content/code :clojure "(inc 1)" {:highlight "1|3-5"}))]
    (is (= "code" (:kind b)))
    (is (= "clojure" (-> b :props :language)))
    (is (= "(inc 1)" (-> b :props :source)))
    (is (= "1|3-5" (-> b :props :highlight)))))

(deftest a-table-flattens-its-cells-to-text
  (let [b (spec/block (content/table ["Q" "USD"] [["Q3" [:strong "86"]]]))]
    (is (= "table" (:kind b)))
    (is (= ["Q" "USD"] (-> b :props :head)))
    (is (= [["Q3" "86"]] (-> b :props :rows)))))

(deftest a-quote-keeps-inline-structure-and-citation
  (let [b (spec/block (content/quotation [:p "the " [:em "thing"]] {:cite "Plato"}))]
    (is (= "quote" (:kind b)))
    (is (= "Plato" (-> b :props :cite)))
    (is (= ["the " "thing"] (map (comp :text :props) (:children b))))))

(deftest a-callout-carries-its-tone
  (is (= "warn" (-> (content/note "careful" {:tone :warn}) spec/block :props :tone)))
  (is (= "info" (-> (content/note "fyi") spec/block :props :tone))
      "an untoned callout defaults rather than emitting nil"))

(deftest cards-project-structurally-not-as-content
  (let [b (spec/block (content/cards [{:title "One" :body "first"}
                                      {:title "Two" :body "second"}]))]
    (is (= "cards" (:kind b)))
    (is (= ["card" "card"] (map :kind (:children b))))
    (is (= ["One" "Two"] (map (comp :title :props) (:children b))))
    (is (= "first" (-> b :children first :children first :props :text)))))

(deftest a-column-projects-its-content
  (let [column (content/column "narrow" {:width {:px 240}})
        projected (spec/block (content/columns [column]))]
    (is (= "columns" (:kind projected)))
    (is (= (spec/block "narrow") (first (:children projected))))))

(deftest a-group-flattens-into-its-parent
  (let [section (doc/section "S" {:blocks [(content/group ["one" "two"]) "three"]})
        component (spec/section->component section)]
    (is (= ["text" "text" "text"] (map :kind (:children component)))
        "a group contributes its items as siblings, not a node of its own")))

;; ── the degradations ────────────────────────────────────────────────────────

(deftest video-degrades-visibly
  (let [b (spec/block (content/video "clip.mp4" {:poster "poster.jpg"
                                                 :caption "Five seconds"}))]
    (is (= "media-placeholder" (:kind b)))
    (is (= "video" (-> b :props :media-type)))
    (is (= "clip.mp4" (-> b :props :src)))
    (is (= "poster.jpg" (-> b :props :poster)))
    (is (= "Five seconds" (-> b :props :caption))
        "the caption survives, so a reader sees what the printed page is missing")))

(deftest video-with-only-sources-still-finds-a-src
  (is (= "a.webm" (-> (content/video nil {:sources [{:src "a.webm"} {:src "a.mp4"}]})
                      spec/block :props :src))))

(deftest audio-and-embed-degrade-the-same-way
  (is (= "audio" (-> (content/audio "a.mp3") spec/block :props :media-type)))
  (is (= "embed" (-> (content/embed "https://x") spec/block :props :media-type))))

(deftest a-fragment-annotates-rather-than-nesting
  (let [b (spec/block (content/fragment (content/bullets ["a"])))]
    (is (= "bullets" (:kind b)) "the fragment does not become a node of its own")
    (is (= "+-" (-> b :style :overlay))))
  (is (= "3-" (-> (content/fragment "x" {:index 3}) spec/block :style :overlay))
      "an explicit fragment index becomes an explicit overlay"))

;; ── sections ────────────────────────────────────────────────────────────────

(deftest a-section-carries-title-level-and-notes
  (let [c (spec/section->component
           (doc/section "Intro" {:level 1
                                 :blocks ["body"]
                                 :opts {:notes "say hello"
                                        :background-color "#111"}}))]
    (is (= "section" (:kind c)))
    (is (= "Intro" (-> c :props :title)))
    (is (= 1 (-> c :props :level)))
    (is (= "#111" (-> c :style :background-color)))
    (is (= "notes" (-> c :children last :kind)))
    (is (= "say hello" (-> c :children last :props :text)))))

(deftest reveal-only-slide-options-are-dropped
  (let [c (spec/section->component
           (doc/section "S" {:opts {:transition :fade :auto-animate true}}))]
    (is (nil? (:style c))
        "a transition has no Beamer meaning and must not be carried as dead weight")))

(deftest a-vertical-stack-flattens-to-consecutive-frames
  (let [cs (spec/sections->components
            [(doc/section "Parent" {:children [(doc/section "Child")]})])]
    (is (= 2 (count cs)) "Beamer has no second axis, so the stack flattens")
    (is (= ["Parent" "Child"] (map (comp :title :props) cs)))
    (is (= "Parent" (-> cs second :style :stack-of))
        "the stack's identity survives on the child")))

;; ── identity ────────────────────────────────────────────────────────────────

(deftest every-component-has-a-unique-id
  (let [document (markdown/parse "# One\n\nsome **text**\n\n- a\n- b\n\n## Two\n\nmore\n")
        s (spec/document->spec document)
        all (ids (:blocks s))]
    (is (seq all))
    (is (every? (comp seq str) all) "DocumentSpec requires a non-empty id on every component")
    (is (= (count all) (count (distinct all)))
        "ids must be unique across the WHOLE tree, not merely among siblings")))

(deftest ids-are-stable-across-projections
  (let [document (markdown/parse "# One\n\nbody\n")]
    (is (= (ids (:blocks (spec/document->spec document)))
           (ids (:blocks (spec/document->spec document))))
        "a churning id would defeat AutoPDF's fragment cache")))

(deftest every-component-has-a-variant
  (let [document (markdown/parse "# T\n\n- a\n\n> quote\n\n```clj\n(x)\n```\n")
        s (spec/document->spec document)
        variants (map :variant (mapcat #(tree-seq :children :children %) (:blocks s)))]
    (is (seq variants) "the walk must actually reach components")
    (is (every? #(= "default" %) variants))))

;; ── the whole projection ────────────────────────────────────────────────────

(deftest a-spec-declares-the-schema-version
  (is (= spec/schema-version
         (:schema-version (spec/document->spec (markdown/parse "# T\n"))))))

(deftest markdown-reaches-a-spec
  (let [s (spec/document->spec
           (markdown/parse "# Revenue\n\nUp **86%** year over year.\n\n- New logos: 42\n- Churn: 1.8%\n")
           {:theme "acme"})]
    (is (= "acme" (:theme s)))
    (is (= ["section"] (map :kind (:blocks s))))
    (is (= "Revenue" (-> s :blocks first :props :title)))
    (is (some #{"bullets"} (kinds (:blocks s))))
    (is (= ["New logos: 42" "Churn: 1.8%"]
           (-> (find-kind (:blocks s) "bullets") :props :items)))))

(deftest org-reaches-the-same-shape-as-markdown
  (let [from-md (spec/document->spec (markdown/parse "# Title\n\nbody text\n"))
        from-org (spec/document->spec (org/parse "* Title\n\nbody text\n"))]
    (is (= (kinds (:blocks from-md)) (kinds (:blocks from-org)))
        "both front ends parse to one IR, so both must project to one shape")))

(deftest the-projection-is-pure
  (let [document (markdown/parse "# T\n\nbody\n")]
    (is (= (spec/document->spec document) (spec/document->spec document)))))

;; ── the drift gate ──────────────────────────────────────────────────────────

(deftest deck-and-spec-agree-on-the-document
  (testing "one parse, two projections: they must not disagree about what is there"
    (let [source (str "# One\n\nalpha\n\n## Nested\n\nbeta\n\n# Two\n\ngamma\n")
          document (markdown/parse source)
          deck (doc/document->deck document)
          components (:blocks (spec/document->spec document))
          deck-slides (count (mapcat #(if (:slides %) (:slides %) [%]) (:slides deck)))]
      (is (= deck-slides (count components))
          "a slide in the deck is a frame in the PDF; a mismatch means one IR moved")
      (is (= (count (:sections document))
             (count (remove #(-> % :style :stack-of) components)))
          "top-level sections and top-level frames must correspond"))))
