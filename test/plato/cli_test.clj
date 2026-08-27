(ns plato.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.cli :as cli]
            [plato.deck :as deck]))

(def markdown-source
  (str "---\ntitle: Smoke\n---\n\n"
       "# One\n\nText with **bold**.\n\nNote: say hello\n\n"
       "## Detail\n\n![Chart](assets/acme/chart.png \"Revenue\")\n"))

(def org-source
  (str "#+TITLE: Smoke\n\n"
       "* One\n\nText with *bold*.\n\n"
       "** Detail\n\n#+CAPTION: Revenue\n[[file:assets/acme/chart.png]]\n"))

(deftest parse-args-reads-commands-and-options
  (is (= :help (:command (cli/parse-args []))))
  (is (= :help (:command (cli/parse-args ["help"]))))
  (is (= :version (:command (cli/parse-args ["--version"]))))
  (is (= {:command :build :input "a.md" :opts {:out "a.html" :title "T"}}
         (cli/parse-args ["build" "a.md" "-o" "a.html" "--title" "T"])))
  (is (= {:print? true} (:opts (cli/parse-args ["build" "a.md" "--print"]))))
  (is (= :theme (:command (cli/parse-args ["theme" "t.edn"])))))

(deftest parse-args-reports-bad-input
  (is (str/includes? (:error (cli/parse-args ["frobnicate" "a.md"])) "Unknown command"))
  (is (str/includes? (:error (cli/parse-args ["build"])) "needs a source path"))
  (is (str/includes? (:error (cli/parse-args ["build" "a.md" "-o"])) "needs a value"))
  (is (str/includes? (:error (cli/parse-args ["build" "a.md" "--nope"])) "Unknown option"))
  (is (str/includes? (:error (cli/parse-args ["build" "a.md" "b.md" "c.md"])) "Unexpected")))

(deftest source-kind-reads-the-extension
  (is (= :markdown (cli/source-kind "a.md")))
  (is (= :markdown (cli/source-kind "A.MARKDOWN")))
  (is (= :org (cli/source-kind "deck/talk.org")))
  (is (nil? (cli/source-kind "talk.txt"))))

(deftest theme-css-name-drops-every-extension
  (is (= "acme-theme.css" (cli/theme-css-name "theme/acme.tokens.edn")))
  (is (= "plato-theme.css" (cli/theme-css-name "plato.tokens.edn")))
  (is (= "brand-theme.css" (cli/theme-css-name "brand.edn"))))

(deftest build-job-renders-a-page-from-either-front-end
  (doseq [[input text] [["talk.md" markdown-source] ["talk.org" org-source]]]
    (let [{:keys [files deck]} (cli/build-job {:input input :text text :opts {}})
          {:keys [path content]} (first files)]
      (is (= "talk.html" path))
      (is (str/starts-with? content "<!doctype html>"))
      (is (str/includes? content "<section id=\"one\""))
      (is (str/includes? content "assets/acme/chart.png"))
      (is (= [:one :detail] (mapv :id (deck/leaf-slides deck)))))))

(deftest build-job-honours-out-title-and-print
  (let [{:keys [files stdout]} (cli/build-job {:input "talk.md"
                                               :text markdown-source
                                               :opts {:out "out/deck.html"
                                                      :title "Custom"
                                                      :print? true}})]
    (is (empty? files))
    (is (str/includes? stdout "<title>Custom</title>"))))

(deftest build-job-generates-and-links-a-token-theme
  (let [{:keys [files]} (cli/build-job {:input "docs/talk.md"
                                        :text markdown-source
                                        :opts {:out "dist/talk.html"
                                               :tokens "theme/acme.tokens.edn"}
                                        :tokens-text (slurp "theme/acme.tokens.edn")})
        by-path (into {} (map (juxt :path :content)) files)]
    (is (= #{"dist/acme-theme.css" "dist/talk.html"} (set (keys by-path))))
    (is (str/includes? (get by-path "dist/acme-theme.css") "--plato-accent: #f59e0b;"))
    (is (str/includes? (get by-path "dist/talk.html") "href=\"acme-theme.css\""))))

(deftest build-job-rejects-an-unknown-extension
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Unsupported source extension"
       (cli/build-job {:input "talk.txt" :text "" :opts {}}))))

(deftest theme-job-writes-css-json-and-cljc
  (let [{:keys [files]} (cli/theme-job {:input "theme/plato.tokens.edn"
                                        :text (slurp "theme/plato.tokens.edn")
                                        :opts {:out "dist/t.css"
                                               :json "dist/t.json"
                                               :cljc "dist/t.cljc"
                                               :ns "acme.theme"}})
        by-path (into {} (map (juxt :path :content)) files)]
    (is (= #{"dist/t.css" "dist/t.json" "dist/t.cljc"} (set (keys by-path))))
    (is (str/includes? (get by-path "dist/t.css") "--plato-bg: #0b0e13;"))
    (is (str/includes? (get by-path "dist/t.json") "\"prefix\": \"plato\""))
    (is (str/includes? (get by-path "dist/t.cljc") "(ns acme.theme"))))

(deftest theme-job-rejects-malformed-tokens
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Duplicate token leaf keys"
       (cli/theme-job {:input "t.edn"
                       :text "{:color {:gap \"#fff\"} :scale {:gap \"1rem\"}}"
                       :opts {}}))))

;; ── page options ────────────────────────────────────────────────────────────

(deftest build-job-honours-page-options
  (let [{:keys [files]} (cli/build-job {:input "opts.md"
                                        :text markdown-source
                                        :opts {:title "Override"
                                               :theme "black"
                                               :asset-base "/static"}})
        content (:content (first files))]
    (is (str/includes? content "<title>Override</title>"))
    (is (str/includes? content "/static/vendor/theme/black.css"))
    (is (str/includes? content "/static/vendor/reset.css")))
  (testing "math is opt-in on the CLI too"
    (let [without (:content (first (:files (cli/build-job {:input "m.md"
                                                           :text markdown-source
                                                           :opts {}}))))
          with (:content (first (:files (cli/build-job {:input "m.md"
                                                        :text markdown-source
                                                        :opts {:math? true}}))))]
      (is (not (str/includes? without "plugin/math.js")))
      (is (str/includes? with "plugin/math.js")))))

;; ── standalone assets ───────────────────────────────────────────────────────

(deftest build-job-plans-the-asset-copies
  (let [{:keys [copies summary]} (cli/build-job {:input "docs/talk.md"
                                                 :text markdown-source
                                                 :opts {:out "dist/talk.html"
                                                        :assets "public"}})]
    (is (= [{:from "public/vendor" :to "dist/vendor"}
            {:from "public/css" :to "dist/css"}]
           copies))
    (is (str/includes? summary "copied public/vendor -> dist/vendor")))
  (testing "--print asks for no page, so it asks for no assets either"
    (is (empty? (:copies (cli/build-job {:input "t.md"
                                         :text markdown-source
                                         :opts {:assets "public" :print? true}})))))
  (testing "no --assets, no copies"
    (is (empty? (:copies (cli/build-job {:input "t.md" :text markdown-source :opts {}}))))))

;; ── deck vars ───────────────────────────────────────────────────────────────

(defn deck-thunk
  "A 0-arg deck var, resolved by name below."
  []
  (deck/deck {:title "Thunk" :slides [(deck/slide :only [:h1 "Thunk"])]}))

(deftest deck-from-var-resolves-and-validates
  (let [model (cli/deck-from-var "plato.acme.deck/model")]
    (is (= :deck (:plato/type model)))
    (is (seq (deck/leaf-slides model))))
  (testing "a 0-arg fn var is called"
    (is (= "Thunk" (:title (cli/deck-from-var "plato.cli-test/deck-thunk"))))))

(deftest deck-from-var-reports-what-went-wrong
  (testing "no such var in a loadable namespace"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot resolve deck var"
                          (cli/deck-from-var "plato.acme.deck/nope"))))
  (testing "no such namespace"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot load"
                          (cli/deck-from-var "plato.no.such.ns/model"))))
  (testing "an unqualified name is not a var"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespaced var"
                          (cli/deck-from-var "model"))))
  (testing "a var that does not hold a deck"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not hold a deck"
                          (cli/deck-from-var "plato.cli/usage")))))

;; ── the shipped acme sources ────────────────────────────────────────────────

(defn- section-ids
  "Every leaf slide id of `model`, as the strings <section id=...> carries."
  [model]
  (into #{} (keep (comp :id deck/section-attrs)) (deck/leaf-slides model)))

(deftest the-shipped-sources-build
  (doseq [source ["docs/acme.md" "docs/acme.org"]
          :when (.isFile (io/file source))]
    (let [{:keys [files deck]} (cli/build-job {:input source
                                               :text (slurp source)
                                               :opts {:out "dist/acme.html"}})
          content (:content (first files))
          ids (section-ids deck)]
      (is (str/starts-with? content "<!doctype html>") source)
      (is (pos? (count (deck/leaf-slides deck))) source)
      (is (seq ids) source)
      (is (every? #(str/includes? content (str "id=\"" % "\"")) ids) source))))
