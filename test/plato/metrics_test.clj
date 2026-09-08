(ns plato.metrics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.metrics :as metrics]
            [plato.tokens :as tokens]))

;; ── CSS lengths ─────────────────────────────────────────────────────────────

(deftest px-resolves-the-three-units-against-their-own-roots
  (is (= 24.0 (metrics/px "24px")))
  (is (= 24.0 (metrics/px 24)))
  (testing "rem is the HTML root, never Reveal's scale"
    (is (= 16.0 (metrics/px "1rem")))
    (is (= 32.0 (metrics/px "2rem" {:rem 16.0}))))
  (testing "em is the font size in force where the length is written"
    (is (= 20.0 (metrics/px "0.5em" {:em 40.0})))))

(deftest px-answers-nil-for-a-length-the-model-cannot-resolve
  (testing "so a caller decides what unknown means instead of budgeting zero"
    (is (nil? (metrics/px "50%")))
    (is (nil? (metrics/px "10vh")))
    (is (nil? (metrics/px "calc(100% - 2rem)")))
    (is (nil? (metrics/px nil)))
    (is (nil? (metrics/px "")))))

;; ── the metrics value ───────────────────────────────────────────────────────

(def theme (tokens/parse "theme/plato.tokens.edn" (slurp "theme/plato.tokens.edn")))

(deftest caps-are-read-from-the-tokens-not-restated
  (let [m (metrics/metrics theme)]
    (is (= 400.0 (get-in m [:limits :media-max])) "--plato-media-max")
    (is (= 356.0 (get-in m [:limits :code-max])) "--plato-code-max")
    (is (= 440.0 (get-in m [:limits :scene-max])) "--plato-scene-max")
    (is (= 16.0 (get-in m [:limits :gap])) "--plato-gap is 1rem, and a rem is 16px")))

(deftest a-token-map-that-states-no-cap-falls-back-to-the-box
  (testing "absent a max-height there is none in the stylesheet either"
    (let [m (metrics/metrics nil)]
      (is (= (:h metrics/slide-box) (get-in m [:limits :media-max]))))))

(deftest metrics-declares-the-rung-its-numbers-stand-at
  (is (= :corpus (:rung (metrics/metrics theme))))
  (is (= :measured (:rung (metrics/metrics theme {:advance {:sans 0.48}})))))

;; ── text ────────────────────────────────────────────────────────────────────

(def m (metrics/metrics theme))

(deftest a-line-that-fits-is-one-line
  (is (= 1 (metrics/line-count m :sans "short" 960.0 40.0))))

(deftest text-wraps-by-total-advance
  (testing "48 chars at 0.5 advance and 40px is 960px, exactly one line"
    (is (= 1 (metrics/line-count m :sans (apply str (repeat 48 "x")) 960.0 40.0)))
    (is (= 2 (metrics/line-count m :sans (apply str (repeat 49 "x")) 960.0 40.0)))))

(deftest an-empty-string-still-occupies-its-line-box
  (is (= 1 (metrics/line-count m :sans "" 960.0 40.0))))

(deftest no-available-width-is-one-line-not-a-division-by-zero
  (is (= 1 (metrics/line-count m :sans "anything at all" 0.0 40.0)))
  (is (= 1 (metrics/line-count m :sans "anything at all" nil 40.0))))

(deftest height-is-lines-times-the-line-box
  (is (= 104.0 (metrics/text-height m :sans (apply str (repeat 49 "x"))
                                    960.0 40.0 1.3))))

;; ── uncertainty ─────────────────────────────────────────────────────────────

(deftest a-short-slide-still-carries-the-floor-of-uncertainty
  (is (= metrics/floor-error (metrics/uncertainty 100.0))))

(deftest a-tall-slide-carries-a-proportional-uncertainty
  (is (= 120.0 (metrics/uncertainty 1000.0))))

;; ── calibration ─────────────────────────────────────────────────────────────

(deftest calibration-is-the-least-squares-scale-over-what-was-observed
  (let [corrected (metrics/calibrate [{:role :sans :chars 100 :font-size 40.0 :width 1600.0}
                                      {:role :sans :chars 100 :font-size 40.0 :width 2400.0}])]
    (is (= 0.5 (:sans corrected)) "8000px of ink over 8000 advance units")))

(deftest a-role-nobody-observed-keeps-its-corpus-ratio
  (let [corrected (metrics/calibrate [{:role :sans :chars 10 :font-size 40.0 :width 100.0}])]
    (is (= (:mono metrics/advance) (:mono corrected)))
    (is (not= (:sans metrics/advance) (:sans corrected)))))

(deftest calibration-ignores-an-observation-it-cannot-use
  (is (= metrics/advance
         (metrics/calibrate [{:role :sans :chars 0 :font-size 40.0 :width 100.0}
                             {:role :sans :chars 10 :font-size 40.0 :width nil}]))))

;; ── the stylesheet is the authority ─────────────────────────────────────────

(def stylesheet (slurp "public/css/plato.css"))

(defn declares?
  "Whether plato.css sets `property` to `value` anywhere.

   A value may be written bare or as the FALLBACK of a derived custom property
   (`font-size: var(--plato-code-size, 0.5em)`), because plato.responsive may
   override it per screen class. Both spellings state the same base number,
   which is the thing this gate holds the estimator to."
  [property value]
  (some? (re-find (re-pattern (str property "\\s*:\\s*(?:var\\(--[a-z-]+,\\s*)?"
                                   value "\\b"))
                  stylesheet)))

(deftest the-component-scale-agrees-with-the-stylesheet
  (testing "a number here that drifts from plato.css makes the estimator wrong
            in the direction nobody notices"
    (is (declares? "font-size" "0\\.5em") ".plato-code")
    (is (declares? "font-size" "0\\.42em") ".plato-caption")
    (is (declares? "font-size" "0\\.55em") ".plato-table")
    (is (declares? "font-size" "0\\.6em") ".plato-note")
    (is (declares? "font-size" "0\\.52em") ".plato-card > p")
    (is (declares? "line-height" "1\\.45") ".plato-code code")
    (is (declares? "line-height" "1\\.35") ".plato-list > li")))

(deftest the-caps-the-model-budgets-are-the-ones-the-stylesheet-applies
  (doseq [[token selector] {"--plato-media-max" ".plato-figure img"
                            "--plato-code-max" ".plato-code code"
                            "--plato-scene-max" ".plato-scene svg"}]
    (is (str/includes? stylesheet (str "max-height: var(" token ")"))
        (str token " is what caps " selector))))
