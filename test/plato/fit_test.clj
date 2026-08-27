(ns plato.fit-test
  "The judging half of the fit check is pure, so it is tested here rather than
   in a browser. What a browser is needed for — measuring — is asserted by the
   :fit scenarios, which drive a real page.

   These tests are written against the contract, not against plato's own decks:
   they would still hold if every slide in the repo were rewritten."
  (:require [clojure.test :refer [deftest is testing]]
            [plato.deck :as deck]
            [plato.fit :as fit]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private box {:w 960 :h 700})

(defn- measured
  ([w h] (measured w h []))
  ([w h clipped] {:id "a-slide" :box box :slide {:w w :h h} :clipped clipped}))

;; ── the box ─────────────────────────────────────────────────────────────────

(deftest a-slide-inside-the-box-has-nothing-to-report
  (is (empty? (fit/findings (measured 960 700))))
  (is (empty? (fit/findings (measured 400 200)))))

(deftest overflow-is-reported-per-axis-with-the-amount
  (is (= [{:kind :taller-than-box :by 163}]
         (fit/findings (measured 960 863))))
  (is (= [{:kind :wider-than-box :by 91}]
         (fit/findings (measured 1051 700))))
  (testing "a slide can miss on both axes at once"
    (is (= #{:taller-than-box :wider-than-box}
           (set (map :kind (fit/findings (measured 1051 863))))))))

(deftest sub-pixel-rounding-is-not-a-defect
  (testing "scrollWidth/scrollHeight round up, so a fractional layout reports
            a pixel of overflow it does not have"
    (is (empty? (fit/findings (measured 962 702))))
    (is (seq (fit/findings (measured (+ 960 fit/tolerance 1) 700))))))

;; ── clipped descendants ─────────────────────────────────────────────────────

(deftest an-element-that-cuts-its-own-content-off-is-a-finding
  (is (= [{:kind :clipped :tag "pre" :class "plato-code" :over-w 0 :over-h 36}]
         (fit/findings
          (measured 960 600 [{:tag "pre" :class "plato-code" :over-w 0 :over-h 36}])))))

(deftest a-clip-within-tolerance-is-dropped
  (is (empty? (fit/findings
               (measured 960 600 [{:tag "span" :class "" :over-w 1 :over-h 2}])))))

;; ── the policy ──────────────────────────────────────────────────────────────

(defn- under [policy measurement] (assoc measurement :policy policy))

(deftest a-slide-that-fits-and-claims-nothing-is-ok
  (is (= :ok (:state (fit/verdict (under nil (measured 960 600)))))))

(deftest an-undeclared-overflow-is-the-failure
  (is (= :overflows (:state (fit/verdict (under nil (measured 960 863)))))))

(deftest a-declared-overflow-is-allowed-through
  (is (= :waived (:state (fit/verdict (under :allow (measured 960 863)))))))

(deftest a-policy-on-a-slide-that-now-fits-is-itself-a-finding
  (testing "it would otherwise hide the next real overflow on that slide"
    (is (= :stale-waiver (:state (fit/verdict (under :allow (measured 960 600))))))
    (is (= :stale-waiver (:state (fit/verdict (under :shrink (measured 960 600))))))))

;; ── shrinking ───────────────────────────────────────────────────────────────

(deftest the-scale-is-the-tightest-of-the-two-axes
  (is (= 1.0 (fit/fit-scale (measured 960 700))))
  (testing "too tall — the height decides"
    (is (= 0.8 (fit/fit-scale (measured 960 875)))))
  (testing "too wide — the width decides"
    (is (= 0.8 (fit/fit-scale (measured 1200 700)))))
  (testing "over on both axes, the smaller factor wins"
    (is (= 0.5 (fit/fit-scale (measured 1920 875))))))

(deftest a-shrinkable-overflow-carries-the-scale-that-repairs-it
  (let [{:keys [state scale]} (fit/verdict (under :shrink (measured 960 875)))]
    (is (= :shrunk state))
    (is (= 0.8 scale))))

(deftest shrinking-past-the-readable-floor-is-a-failure-not-a-remedy
  (testing "a slide needing 50% is not fixed by rendering it at 50%"
    (is (= :too-small (:state (fit/verdict (under :shrink (measured 960 1400)))))))
  (testing "the floor itself still passes"
    (is (= :shrunk (:state (fit/verdict (under :shrink (measured 960 (/ 700 fit/min-scale)))))))))

(deftest shrinking-cannot-repair-a-clipped-descendant
  (testing "the element and its content scale together, so the ratio that cut
            the content off survives at every scale"
    (is (= :overflows
           (:state (fit/verdict
                    (under :shrink
                           (measured 960 875
                                     [{:tag "pre" :class "plato-code"
                                       :over-w 0 :over-h 36}]))))))))

;; ── reporting ───────────────────────────────────────────────────────────────

(deftest explain-is-nil-when-there-is-nothing-to-say
  (is (nil? (fit/explain [(fit/verdict (under nil (measured 960 600)))
                          (fit/verdict (under :allow (measured 960 863)))
                          (fit/verdict (under :shrink (measured 960 875)))]))))

(deftest explain-names-the-slide-the-axis-and-the-amount
  (let [message (fit/explain [(fit/verdict (under nil (measured 960 863)))])]
    (is (re-find #"a-slide" message))
    (is (re-find #"163px taller" message))))

(deftest explain-tells-a-stale-waiver-apart-from-an-overflow
  (is (re-find #"now fits"
               (fit/explain [(fit/verdict (under :allow (measured 960 600)))]))))

(deftest explain-names-the-scale-a-too-small-slide-would-have-needed
  (let [message (fit/explain [(fit/verdict (under :shrink (measured 960 1400)))])]
    (is (re-find #"50%" message))
    (is (re-find #"60%" message))
    (is (re-find #"cut content" message))))

;; ── the seam the stylesheet reads ───────────────────────────────────────────

(deftest the-stylesheet-reads-the-property-the-checker-writes
  (testing "a measured scale reaches the page only if these two agree, and
            nothing else connects them"
    (let [stylesheet (slurp (io/file "public/css/plato.css"))]
      (is (str/includes? stylesheet (str "var(" fit/scale-property))
          "plato.css must read the custom property plato.fit writes")
      (is (str/includes?
           stylesheet
           (str "[" (name (:attr deck/overflow-policy)) "=\"shrink\"]"))
          "and must scope it to the slides that declared {:overflow :shrink}"))))

;; ── the declaration, checked when the deck is built ─────────────────────────

(deftest a-policy-reaches-the-dom-as-an-attribute
  (testing "so the checker reads the author's intent off the page itself, and
            works the same against the live shell and the static export"
    (is (= "allow"
           (get (deck/section-attrs {:id :wide :overflow :allow})
                (:attr deck/overflow-policy))))
    (is (= "shrink"
           (get (deck/section-attrs {:id :tall :overflow :shrink})
                (:attr deck/overflow-policy)))))
  (testing "and a slide that declares nothing carries no attribute"
    (is (not (contains? (deck/section-attrs {:id :plain})
                        (:attr deck/overflow-policy))))))

(deftest an-overflow-value-the-checker-would-not-recognise-fails-the-build
  (testing "this is the part that IS decidable without a layout engine"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":overflow accepts"
         (deck/deck {:slides [(deck/slide :a [:p "a"] {:overflow :allowed})]}))))
  (testing "including inside a vertical stack"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":overflow accepts"
         (deck/deck {:slides [(deck/stack :s [(deck/slide :a [:p "a"]
                                                          {:overflow "allow"})])]}))))
  (testing "and every value the checker does recognise passes"
    (doseq [value (:values deck/overflow-policy)]
      (is (some? (deck/deck {:slides [(deck/slide :a [:p "a"] {:overflow value})]}))))))
