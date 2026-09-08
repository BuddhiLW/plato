(ns plato.responsive-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.responsive :as responsive]))

;; ── facts ───────────────────────────────────────────────────────────────────

(def base* (responsive/facts {:w 960.0 :h 700.0} 600.0))

(deftest the-fact-base-states-the-box-the-demand-and-every-target
  (is (contains? base* [:box :w 960.0]))
  (is (contains? base* [:box :h 700.0]))
  (is (contains? base* [:demand :worst 600.0]))
  (is (contains? base* [:viewport :phone :w 360.0]))
  (is (contains? base* [:viewport :room :h 1080.0])))

(deftest every-token-role-and-every-floor-is-a-fact
  (doseq [[role {:keys [base]}] responsive/token-roles]
    (is (contains? base* [:token role :px (double base)])
        (str role " is a token role with no fact")))
  (is (contains? base* [:floor :phone :body 16.0]))
  (is (contains? base* [:floor :room :body 28.0])))

(deftest a-narrower-set-of-targets-yields-a-smaller-fact-base
  (let [one (responsive/facts {:w 960.0 :h 700.0} 600.0
                              [(first responsive/viewports)])]
    (is (< (count one) (count base*)))
    (is (not-any? #(= :room (second %)) (filter #(= :viewport (first %)) one)))))

;; ── the aggregation that cannot be a rule ───────────────────────────────────

(deftest the-wanted-scale-is-the-largest-any-token-demands
  (testing "one type scale serves a target: scaling tokens by different amounts
            is not a type scale, it is a redesign"
    (is (= [[:wanted-scale :phone 1.6]]
           (keys (responsive/wanted-scale [[:needs-scale :phone :body 1.2]
                                           [:needs-scale :phone :caption 1.6]
                                           [:needs-scale :phone :code 1.05]]))))))

(deftest the-aggregate-carries-the-facts-it-maximised-over
  (testing "so a trace walks through it instead of stopping at it"
    (is (= 2 (count (val (first (responsive/wanted-scale
                                 [[:needs-scale :phone :body 1.2]
                                  [:needs-scale :phone :caption 1.6]]))))))))

(deftest a-target-nothing-demands-never-reaches-the-aggregation
  (is (empty? (responsive/wanted-scale []))))

(deftest the-wanted-scale-never-shrinks-a-token
  (testing "legibility is a floor, so a remedy raises or does nothing"
    (is (= [[:wanted-scale :room 1.0]]
           (keys (responsive/wanted-scale [[:needs-scale :room :body 0.4]]))))))

;; ── projection ──────────────────────────────────────────────────────────────

(defn fixpoint [& facts] {:facts (set facts) :why {}})

(deftest declarations-name-the-generated-custom-properties
  (is (= {"--plato-body-size" "60px"}
         (responsive/declarations #{[:token-value :phone :body 60.0]} :phone))))

(deftest declarations-are-per-target
  (let [facts #{[:token-value :phone :body 60.0] [:token-value :room :body 80.0]}]
    (is (= {"--plato-body-size" "80px"} (responsive/declarations facts :room)))))

(deftest a-target-with-nothing-to-change-contributes-no-block
  (testing "which is what keeps the generated file short enough to read"
    (is (empty? (responsive/rules (fixpoint [:legible :phone :body]))))))

(deftest a-target-with-a-derived-value-gets-one-media-block
  (let [rs (responsive/rules (fixpoint [:token-value :phone :body 60.0]))]
    (is (= 1 (count rs)))
    (is (str/starts-with? (ffirst rs) "@media"))
    (is (str/includes? (ffirst rs) "max-width: 480px"))))

(deftest a-derived-layout-decision-is-reported-not-emitted
  (testing "plato.css already owns when a grid collapses; a generated rule for
            the same decision would be a second definition of it"
    (let [fx (fixpoint [:layout :phone :columns 1])]
      (is (empty? (responsive/rules fx)))
      (is (some #(str/includes? % "collapse") (responsive/report fx))))))

(deftest blocks-come-out-narrowest-first-so-the-cascade-resolves
  (let [rs (responsive/rules (fixpoint [:token-value :phone :body 60.0]
                                       [:token-value :room :body 30.0]))]
    (is (str/includes? (ffirst rs) "max-width: 480px"))
    (is (str/includes? (first (second rs)) "min-width: 1601px"))))

;; ── the stylesheet ──────────────────────────────────────────────────────────

(deftest the-header-states-the-rung-the-fit-half-stands-at
  (testing "a derived value that hides which rung it rests on is worse than none"
    (let [css (responsive/stylesheet (fixpoint [:type-scale :phone 1.2]
                                               [:token-value :phone :body 48.0]))]
      (is (str/includes? css "ESTIMATED rung"))
      (is (str/includes? css "GENERATED"))
      (is (str/includes? css "--plato-body-size: 48px")))))

(deftest the-header-carries-the-report-so-the-css-explains-itself
  (let [css (responsive/stylesheet (fixpoint [:type-scale :phone 1.0]
                                             [:unsatisfiable :phone :legibility 1.59]))]
    (is (str/includes? css "UNSATISFIABLE"))
    (is (str/includes? css "cut content"))))

(deftest an-empty-derivation-still-produces-a-file-that-says-so
  (testing "a stylesheet that silently is not there reads as one that passed"
    (let [css (responsive/stylesheet (fixpoint))]
      (is (str/includes? css "GENERATED"))
      (is (not (str/includes? css "@media"))))))

;; ── the constants are declared, not buried ──────────────────────────────────

(deftest every-target-declares-a-query-and-a-floor-set
  (doseq [{:keys [id query]} responsive/viewports]
    (is (string? query) (str id " has no media query"))
    (is (contains? responsive/legibility-floors id)
        (str id " has no legibility floors, so nothing would ever be derived for it"))))

(deftest every-floor-set-names-only-roles-that-project-to-a-property
  (doseq [[vp floors] responsive/legibility-floors
          role (keys floors)]
    (is (contains? responsive/token-roles role)
        (str vp "'s floor for " role " governs no custom property"))))
