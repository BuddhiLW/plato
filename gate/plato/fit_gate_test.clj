(ns plato.fit-gate-test
  "plato as an applied case of the hive-cljs fit gate.

   Everything a gate DECIDES -- what an overflow is, when an estimate may fail
   a build, what makes a coverage check vacuous -- lives in hive-cljs. What
   lives here is only what is plato's to answer: where measurements come from,
   and what plato's document model admits.

   This suite is in its own source root because it is the one part of plato's
   tests that needs a dependency plato itself does not carry. `clojure -M:test`
   stays dependency-free; `clojure -M:gate` runs this."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.fit :as fit]
            [hive-cljs.fit.test :as fit-test :refer [deffit]]
            [hive-cljs.ports :as ports]
            [hive-cljs.schema :as s]
            [malli.core :as m]
            [plato.acme.deck :as acme]
            [plato.estimate :as est]
            [plato.example.deck :as site]
            [plato.metrics :as metrics]
            [plato.tokens :as tokens]))

(defn theme
  "The token map a deck is themed by, read from the file the CSS is generated
   from -- so the caps the estimator budgets are the ones the page applies."
  [path]
  (tokens/parse path (slurp path)))

;; ── the two things plato owns ───────────────────────────────────────────────

(def shipped
  "Every deck plato publishes, each with the theme it is published under.

   The gate is over the project, not over one deck: coverage of the content
   model is a claim about what plato SHIPS, and asserting it per deck would
   fail the site deck for kinds the Acme demo is the one that exercises."
  [{:deck site/model :tokens "theme/plato.tokens.edn" :name "site"}
   {:deck acme/model :tokens "theme/acme.tokens.edn" :name "acme"}])

(defn static-source
  "plato's box model over `decks`, as a fit source at the estimated rung.

   The rung is not negotiable here: `plato.estimate` runs a box model, so it
   says :estimated however plausible its numbers look, and hive-cljs decides
   from that what it is entitled to fail a build on.

   Slide ids are qualified by deck, because two decks may name a slide the same
   and a report that merges them would name the wrong one."
  [decks]
  (reify ports/IFitSource
    (fit-rung [_] :estimated)
    (fit-measurements [_]
      (into []
            (mapcat (fn [{:keys [deck tokens name]}]
                      (map #(update % :fit/id (fn [id] (str name "/" id)))
                           (est/measure-deck deck (theme tokens)))))
            decks))))

(def content-model
  "plato's content registry as the coverage universe.

   `plato.estimate/model-kinds` reads `plato.content/render`'s own dispatch
   table, which is the independent source the check needs: a content kind that
   exists but has no box arm shows up as a gap here, and a kind added to the
   registry joins the universe by existing."
  (reify ports/IFitUniverse
    (fit-universe [_] (est/model-kinds))))

;; ── the gate ────────────────────────────────────────────────────────────────

(deffit shipped
  {:source (static-source shipped)
   :universe content-model
   :doc "Every slide plato publishes, judged without a browser.

         What this gate can and cannot say: an overflow larger than the box
         model's own margin FAILS, one inside it warns, and a slide the model
         cannot see at all warns. The browser rung (test/plato/e2e.cljs, and
         the :fit scenarios in hive-cljs.edn) is what settles the question --
         this one is what says so before anyone opens a page."})

;; ── the vocabulary the two repos share ──────────────────────────────────────

(deftest platos-measurements-are-hive-cljs-measurements
  (testing "plato names the :fit/* keys without depending on hive-cljs at
            runtime, so this is the assertion that keeps the two in step"
    (let [ms (ports/fit-measurements (static-source shipped))]
      (is (seq ms))
      (doseq [x ms]
        (is (m/validate s/FitMeasurement x)
            (str (:fit/id x) ": " (pr-str (m/explain s/FitMeasurement x))))))))

(deftest the-estimated-rung-cannot-fail-a-build-on-noise
  (testing "every measurement states the model's own uncertainty, which is what
            separates a warning from a gate"
    (is (every? #(pos? (:fit/margin %))
                (ports/fit-measurements (static-source shipped))))))

;; ── what the estimate actually says, printed ────────────────────────────────

(deftest ^:report the-estimate-reports-what-it-found
  (let [run (fit-test/run (static-source shipped) content-model nil)
        report (:fit/report run)]
    (println)
    (println (metrics/describe (metrics/metrics (theme "theme/plato.tokens.edn"))))
    (println "shipped decks:" (pr-str (:fit/tally report))
             "->" (name (:fit/state report)))
    (when-let [note (fit-test/warnings-note run)] (println note))
    (is (contains? #{:pass :warn :fail} (:fit/state report))
        "a report over the shipped deck must reach a verdict, whatever it is")))
