(ns plato.estimate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.estimate :as est]
            [plato.metrics :as metrics]
            [plato.tokens :as tokens]))

(def theme (tokens/parse "theme/plato.tokens.edn"
                               (slurp "theme/plato.tokens.edn")))

(def m (metrics/metrics theme))
(def ctx (est/context m))

(defn h [node] (:h (est/node-box m ctx node)))
(defn w [node] (:w (est/node-box m ctx node)))
(defn clipped [node] (:clipped (est/node-box m ctx node)))
(defn modelled? [node] (:modelled? (est/node-box m ctx node)))

;; ── taking hiccup apart ─────────────────────────────────────────────────────

(deftest a-head-keyword-yields-its-tag-and-classes
  (is (= [:figure #{"plato-figure"}] (est/parse-head :figure.plato-figure)))
  (is (= [:div #{"a" "b"}] (est/parse-head :div#id.a.b)))
  (is (= [:p #{}] (est/parse-head :p))))

(deftest a-class-attribute-joins-the-classes-in-the-head
  (is (= #{"plato-figure" "wide"}
         (:classes (est/element [:figure.plato-figure {:class "wide"} "x"])))))

(deftest attributes-are-optional
  (is (= ["a" "b"] (:children (est/element [:p "a" "b"]))))
  (is (= ["a"] (:children (est/element [:p {:id "x"} "a"])))))

(deftest a-component-call-is-not-an-element
  (is (nil? (est/element [(fn [] [:p "hi"]) 1 2]))))

;; ── stacking ────────────────────────────────────────────────────────────────

(deftest stacked-boxes-add-heights-and-take-the-widest
  (let [b (est/stack [(assoc est/empty-box :h 10.0 :w 100.0)
                      (assoc est/empty-box :h 20.0 :w 50.0)])]
    (is (= 30.0 (:h b)))
    (is (= 100.0 (:w b)))))

(deftest a-gap-is-counted-between-siblings-not-around-them
  (is (= 34.0 (:h (est/stack [(assoc est/empty-box :h 10.0)
                              (assoc est/empty-box :h 20.0)] 4.0)))))

(deftest stacking-nothing-is-an-empty-box-not-an-error
  (is (= est/empty-box (est/stack [])))
  (is (= est/empty-box (est/stack [nil nil]))))

(deftest one-unmodelled-child-makes-the-parent-unmodelled
  (testing "the doubt propagates upward, it does not get averaged away"
    (is (false? (:modelled? (est/stack [est/empty-box
                                        (assoc est/empty-box :modelled? false)]))))))

;; ── arms ────────────────────────────────────────────────────────────────────

(deftest a-heading-is-sized-by-the-reveal-scale-and-carries-its-margin
  (let [one-line (* 3.77 40.0 1.2)]
    (is (= (+ one-line 20.0) (h [:h1 "Short"])))
    (is (< (h [:h2 "Short"]) (h [:h1 "Short"])))))

(deftest a-paragraph-carries-one-block-margin-not-two
  (testing "adjacent block margins collapse, so the gap between siblings is one"
    (is (= (+ (* 40.0 1.3) 20.0) (h [:p "Short"])))))

(deftest a-list-indents-its-items-and-narrows-them
  (let [wide (h [:ul.plato-list [:li (apply str (repeat 60 "x"))]])
        narrow (h [:ul.plato-list [:li (apply str (repeat 200 "x"))]])]
    (is (< wide narrow))))

(deftest a-code-listing-taller-than-its-cap-is-CLIPPED-not-taller
  (testing ".plato-code code has max-height and overflow:auto, so the slide
            stays the same height and the listing loses its tail"
    (let [long-source (str/join "\n" (repeat 60 "(defn f [x] (inc x))"))
          node [:pre.plato-code [:code {} long-source]]
          b (est/node-box m ctx node)]
      (is (= (get-in m [:limits :code-max]) (:h b))
          "the box is the cap, however long the listing")
      (is (= 1 (count (:clipped b))))
      (is (pos? (:fit/over-h (first (:clipped b)))))
      (is (= "plato-code" (:fit/class (first (:clipped b))))))))

(deftest a-short-code-listing-is-not-clipped
  (is (empty? (clipped [:pre.plato-code [:code {} "(inc 1)"]]))))

(deftest a-table-wider-than-the-slide-is-CLIPPED-not-wider
  (testing "cells are nowrap and the table scrolls horizontally"
    (let [cell (apply str (repeat 40 "x"))
          node [:table.plato-table
                [:tbody [:tr [:td cell] [:td cell] [:td cell] [:td cell]]]]
          b (est/node-box m ctx node)]
      (is (<= (:w b) (:avail ctx)) "it never makes the slide wider")
      (is (= 1 (count (:clipped b))))
      (is (pos? (:fit/over-w (first (:clipped b))))))))

(deftest a-narrow-table-is-not-clipped
  (is (empty? (clipped [:table.plato-table [:tbody [:tr [:td "1"] [:td "2"]]]]))))

(deftest an-image-budgets-the-cap-the-stylesheet-applies
  (is (= (get-in m [:limits :media-max]) (h [:img {:src "a.png"}]))))

(deftest a-declared-height-is-believed-but-still-capped
  (is (= 120.0 (h [:img {:src "a.png" :height 120}])))
  (is (= (get-in m [:limits :media-max]) (h [:img {:src "a.png" :height 5000}]))))

(deftest a-scene-budgets-its-svg-cap-plus-its-transport
  (is (> (h [:div.plato-scene [:svg]]) (get-in m [:limits :scene-max]))))

(deftest cards-lay-out-in-three-columns-and-round-up-the-rows
  (let [card (fn [] [:div.plato-card [:h3 "t"] [:p "b"]])
        three [:div.plato-grid (card) (card) (card)]
        four [:div.plato-grid (card) (card) (card) (card)]]
    (is (< (h three) (h four)) "a fourth card starts a second row")))

(deftest a-card-is-never-shorter-than-its-declared-floor
  (is (= (:card-min-h (:layout m)) (h [:div.plato-card [:p "x"]]))))

(deftest columns-fit-as-many-tracks-as-the-minmax-floor-allows
  (testing "minmax(16rem, 1fr) is a 256px floor, so 960px takes three tracks"
    (let [col (fn [] [:div.plato-column [:p "x"]])
          three [:div.plato-columns (col) (col) (col)]
          four [:div.plato-columns (col) (col) (col) (col)]]
      (is (= (h three) (h [:div.plato-columns (col) (col)]))
          "two and three columns are both one row")
      (is (< (h three) (h four)) "a fourth column wraps"))))

;; ── what the model cannot see ───────────────────────────────────────────────

(deftest a-component-call-is-unmodelled-not-empty
  (testing "invoking author code inside a build gate is not something this does"
    (is (false? (modelled? [(fn [] [:p "hi"])])))))

(deftest raw-html-is-unmodelled
  (is (false? (modelled? [:div.plato-html {:dangerouslySetInnerHTML {:__html "<p>x</p>"}}]))))

(deftest a-reveal-markdown-template-is-unmodelled
  (testing "what it becomes is decided by a plugin at runtime"
    (is (false? (modelled? (content/expand (content/render (content/markdown "# hi"))))))))

(deftest ordinary-content-is-modelled
  (is (true? (modelled? [:div [:h1 "a"] [:p "b"]]))))

;; ── the content model's universe ────────────────────────────────────────────

(deftest the-universe-is-read-off-the-content-multimethod
  (let [u (est/model-kinds)]
    (is (seq u) "a universe that is empty would make every coverage check vacuous")
    (is (contains? u :code))
    (is (contains? u :table))
    (testing "shapes of a value are not kinds of content"
      (is (not-any? u [:nil :string :hiccup :seq :map :component :value :default])))))

(deftest content-kinds-finds-tagged-maps-nested-in-hiccup
  (is (= #{:image} (est/content-kinds [:div [:span (content/image "a.png")]])))
  (is (= #{:group :code} (est/content-kinds (content/group [(content/code :clojure "x")]))))
  (is (= #{} (est/content-kinds [:div "plain hiccup"]))))

;; ── measuring a deck ────────────────────────────────────────────────────────

(def sample
  (deck/deck
   {:title "sample"
    :slides [(deck/slide :intro [:div [:h1 "Hello"] [:p "A line of body text."]])
             (deck/slide :tall (content/bullets (repeat 40 "a bullet")))
             (deck/slide :waived [:div [:h1 "Tall"] (content/bullets (repeat 40 "b"))]
                         {:overflow :allow})
             (deck/stack :group [(deck/slide :nested [:p "in a stack"])])]}))

(def measured (est/measure-deck sample theme))

(deftest every-leaf-slide-is-measured-stacks-are-not
  (is (= ["intro" "tall" "waived" "nested"] (mapv :fit/id measured))))

(deftest a-measurement-speaks-the-fit-vocabulary
  (let [x (first measured)]
    (is (= :estimated (:fit/rung x)))
    (is (= {:w 960.0 :h 700.0} (:fit/box x)))
    (is (number? (:h (:fit/extent x))))
    (is (pos? (:fit/margin x)))
    (is (set? (:fit/kinds x)))
    (is (true? (:fit/modelled? x)))))

(deftest a-declared-overflow-reaches-the-measurement
  (is (= :allow (:fit/policy (nth measured 2))))
  (is (nil? (:fit/policy (first measured)))))

(deftest a-slide-of-forty-bullets-is-taller-than-its-box
  (is (> (:h (:fit/extent (second measured))) 700.0)))

(deftest an-ordinary-title-slide-fits
  (is (< (:h (:fit/extent (first measured))) 700.0)))

(deftest the-margin-grows-with-the-estimate-it-qualifies
  (is (< (:fit/margin (first measured)) (:fit/margin (second measured)))))

(deftest measuring-is-pure
  (testing "so a native build and a JVM build gate on one answer"
    (is (= measured (est/measure-deck sample theme)))))

(deftest the-caps-in-force-come-from-the-tokens-passed-in
  (testing "a theme with a smaller code cap clips a listing the default allows"
    (let [source (str/join "\n" (repeat 8 "(inc 1)"))
          slide (deck/slide :c (content/code :clojure source))
          d (deck/deck {:title "t" :slides [slide]})
          tight (assoc-in theme [:scale :code-max] "80px")]
      (is (empty? (:fit/clipped (first (est/measure-deck d theme)))))
      (is (seq (:fit/clipped (first (est/measure-deck d tight))))))))
