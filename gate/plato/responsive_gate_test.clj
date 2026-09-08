(ns plato.responsive-gate-test
  "plato's responsive rules, actually run.

   `plato.responsive` is rules and projection; the engine that drives them is
   `hive-cljs.derive`, which plato does not carry. So this is where the rules
   are exercised end to end, alongside the fit gate that supplies their binding
   constraint."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.derive :as derive]
            [plato.responsive :as responsive]))

(defn run
  "The derivation for a box and a worst-slide demand."
  [box demand]
  (derive/run-strata responsive/strata (responsive/facts box demand)))

(def box {:w 960.0 :h 700.0})

(def roomy
  "A deck whose tallest slide leaves plenty of room: the type scale may rise."
  (run box 350.0))

(def full
  "A deck whose tallest slide already fills its box: nothing may rise."
  (run box 700.0))

(defn facts-of [fx kind]
  (into #{} (filter #(= kind (first %))) (:facts fx)))

;; ── the physics the rules encode ────────────────────────────────────────────

(deftest the-render-scale-is-the-tighter-of-the-two-axes
  (testing "Reveal fits the whole box on screen, so one axis binds"
    (is (contains? (facts-of roomy :render-scale) [:render-scale :phone 0.375])
        "360/960 binds before 640/700")
    (is (contains? (facts-of roomy :render-scale) [:render-scale :room (/ 1080.0 700.0)])
        "1920/960 is 2.0, so the height binds on a 16:9 display")))

(deftest a-token-paints-at-its-declared-size-times-that-scale
  (is (contains? (facts-of roomy :painted) [:painted :phone :body (* 40.0 0.375)])))

(deftest a-large-screen-needs-no-remedy-at-all
  (testing "which is what keeps the generated file short"
    (is (contains? (facts-of roomy :legible) [:legible :room :body]))
    (is (not-any? #(= :room (second %)) (facts-of roomy :illegible)))))

(deftest a-phone-is-where-the-declared-scale-stops-being-legible
  (let [illegible (facts-of roomy :illegible)]
    (is (seq (filter #(= :phone (second %)) illegible)))
    (is (contains? (set (map #(nth % 2) illegible)) :body))))

;; ── the two constraints meeting ─────────────────────────────────────────────

(deftest a-deck-with-headroom-gets-the-remedy-it-asked-for
  (let [scales (facts-of roomy :type-scale)
        phone (first (filter #(= :phone (second %)) scales))]
    (is (some? phone))
    (is (> (nth phone 2) 1.0) "the type scale rose")
    (is (empty? (facts-of roomy :unsatisfiable))
        "nothing was capped, because the tallest slide had room")))

(deftest a-deck-with-no-headroom-is-told-so-rather-than-fudged
  (testing "the honest answer when legibility and fit cannot both hold"
    (let [unsat (facts-of full :unsatisfiable)]
      (is (seq unsat))
      (is (= :phone (second (first unsat))))
      (is (contains? (facts-of full :type-scale) [:type-scale :phone 1.0])
          "and the scale stays where it was, rather than overflowing the deck"))))

(deftest a-capped-target-emits-no-media-block-and-says-why-in-the-header
  (let [css (responsive/stylesheet full)]
    (is (not (str/includes? css "@media")))
    (is (str/includes? css "UNSATISFIABLE"))
    (is (str/includes? css "cut content"))))

(deftest a-remedied-target-emits-the-custom-properties-it-derived
  (let [css (responsive/stylesheet roomy)]
    (is (str/includes? css "@media (max-width: 480px)"))
    (is (str/includes? css "--plato-body-size"))
    (is (str/includes? css "--plato-code-size"))))

(deftest the-remedy-never-exceeds-the-declared-ceiling
  (testing "past it the box holds so little the deck loses its shape"
    (doseq [[_ _ f] (facts-of (run box 10.0) :type-scale)]
      (is (<= f responsive/max-type-scale)))))

;; ── provenance: the point of writing it as rules ────────────────────────────

(deftest every-derived-token-value-can-say-where-it-came-from
  (let [value (first (facts-of roomy :token-value))
        {:rule/keys [id] :keys [premises]} (derive/why roomy value)]
    (is (= :token-value id))
    (is (some #(= :type-scale (first %)) premises))
    (is (some #(= :token (first %)) premises))))

(deftest a-type-scale-traces-back-to-the-floor-that-forced-it
  (let [scale (first (filter #(= :phone (second %)) (facts-of roomy :type-scale)))
        steps (derive/trace roomy scale)]
    (is (seq steps))
    (is (contains? (set (map :rule/id steps)) :within-headroom))
    (testing "and the walk reaches the render scale the whole chain rests on"
      (is (contains? (set (map :rule/id steps)) :render-scale)))))

(deftest an-asserted-fact-reports-itself-as-asserted
  (testing "not as a derivation nobody could find"
    (is (nil? (derive/why roomy [:box :h 700.0])))
    (is (str/includes? (derive/explain roomy [:box :h 700.0]) "asserted"))))

;; ── the loop the two halves close ───────────────────────────────────────────

(deftest the-fit-estimate-is-what-bounds-the-responsive-remedy
  (testing "a taller worst slide leaves less room for a larger type scale, and
            that is the whole coupling between the two halves"
    (let [scale-of (fn [fx] (nth (first (filter #(= :phone (second %))
                                                (facts-of fx :type-scale)))
                                 2))]
      (is (> (scale-of (run box 350.0)) (scale-of (run box 600.0))))
      (is (>= (scale-of (run box 600.0)) (scale-of (run box 700.0)))))))
