(ns plato.fit-test
  "The judging half of the fit check is pure, so it is tested here rather than
   in a browser. What a browser is needed for — measuring — is asserted by the
   :fit scenarios, which drive a real page.

   These tests are written against the contract, not against plato's own decks:
   they would still hold if every slide in the repo were rewritten."
  (:require [clojure.test :refer [deftest is testing]]
            [plato.deck :as deck]
            [plato.fit :as fit]))

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

;; ── the waiver ──────────────────────────────────────────────────────────────

(deftest a-slide-that-fits-and-claims-nothing-is-ok
  (is (= :ok (:state (fit/verdict (assoc (measured 960 600) :waived? false))))))

(deftest an-undeclared-overflow-is-the-failure
  (is (= :overflows (:state (fit/verdict (assoc (measured 960 863) :waived? false))))))

(deftest a-declared-overflow-is-allowed-through
  (is (= :waived (:state (fit/verdict (assoc (measured 960 863) :waived? true))))))

(deftest a-waiver-on-a-slide-that-now-fits-is-itself-a-finding
  (testing "it would otherwise hide the next real overflow on that slide"
    (is (= :stale-waiver
           (:state (fit/verdict (assoc (measured 960 600) :waived? true)))))))

;; ── reporting ───────────────────────────────────────────────────────────────

(deftest explain-is-nil-when-there-is-nothing-to-say
  (is (nil? (fit/explain [(fit/verdict (assoc (measured 960 600) :waived? false))
                          (fit/verdict (assoc (measured 960 863) :waived? true))]))))

(deftest explain-names-the-slide-the-axis-and-the-amount
  (let [message (fit/explain [(fit/verdict (assoc (measured 960 863) :waived? false))])]
    (is (re-find #"a-slide" message))
    (is (re-find #"163px taller" message))))

(deftest explain-tells-a-stale-waiver-apart-from-an-overflow
  (is (re-find #"now fits"
               (fit/explain [(fit/verdict (assoc (measured 960 600) :waived? true))]))))

;; ── the declaration, checked when the deck is built ─────────────────────────

(deftest a-waiver-reaches-the-dom-as-an-attribute
  (testing "so the checker reads the author's intent off the page itself, and
            works the same against the live shell and the static export"
    (is (= "allow"
           (get (deck/section-attrs {:id :wide :overflow :allow})
                (:attr deck/overflow-waiver)))))
  (testing "and a slide that declares nothing carries no attribute"
    (is (not (contains? (deck/section-attrs {:id :plain})
                        (:attr deck/overflow-waiver))))))

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
  (testing "and the declared value passes"
    (is (some? (deck/deck {:slides [(deck/slide :a [:p "a"] {:overflow :allow})]})))))
