(ns plato.tokens-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.color :as color]
            [plato.json :as json]
            [plato.theme :as theme]
            [plato.tokens :as tokens]))

(def default-source "theme/plato.tokens.edn")

(defn- read-tokens [path] (edn/read-string (slurp path)))

(defn- composed
  "A theme and every theme it :extends, composed base first. The test resolves
   the chain the way the CLI's I/O boundary does."
  [path]
  (let [dir (subs path 0 (inc (str/last-index-of path "/")))]
    (loop [path path acc ()]
      (let [tk (read-tokens path)
            acc (conj acc tk)]
        (if-let [parent (:extends tk)]
          (recur (str dir parent) acc)
          (tokens/compose (vec acc)))))))

(deftest sources-are-well-formed
  (is (map? (tokens/assert-tokens! (read-tokens default-source))))
  (is (map? (tokens/assert-tokens! (read-tokens "theme/acme.tokens.edn"))))
  (is (map? (tokens/assert-tokens! (composed "theme/acme-print.tokens.edn")))))

(deftest a-theme-extends-another-by-stating-only-what-differs
  (let [print-theme (composed "theme/acme-print.tokens.edn")
        acme (read-tokens "theme/acme.tokens.edn")]
    (testing "overridden keys win"
      (is (= "#ffffff" (get-in print-theme [:color :bg])))
      (is (= "Acme Print" (get-in print-theme [:meta :name])))
      (is (= "white" (get-in print-theme [:meta :reveal-theme]))))
    (testing "everything unstated is inherited"
      (is (= (get-in acme [:color :accent]) (get-in print-theme [:color :accent])))
      (is (= (:scale acme) (:scale print-theme)))
      (is (= (:type acme) (:type print-theme)))
      (is (= (:scene acme) (:scene print-theme)))
      (is (= "plato" (tokens/prefix print-theme))))
    (testing ":extends is resolved away, never emitted"
      (is (nil? (:extends print-theme))))))

(deftest rules-become-css-with-token-references-resolved
  (let [css (tokens/css (composed "theme/acme-print.tokens.edn")
                        "theme/acme-print.tokens.edn")]
    (is (str/includes? css "--plato-bg: #ffffff;"))
    (is (str/includes? css ".reveal .plato-kicker {"))
    (is (str/includes? css "color: var(--plato-accent);"))
    (is (str/includes? css "@media print {"))
    (testing "the authored [:token k] form never reaches the stylesheet"
      (is (not (str/includes? css ":token"))))))

(deftest merge-tokens-accumulates-rules-base-first
  (is (= [[:a] [:b]]
         (:rules (tokens/merge-tokens {:rules [[:a]]} {:rules [[:b]]}))))
  (is (nil? (:rules (tokens/merge-tokens {} {})))))

(deftest tokens-can-be-read-from-json
  (let [source {:meta {:prefix "acme" :name "JSON" :reveal-theme "black"}
                :color {:bg "#000000" :accent "#ff0000"}
                :scale {:gap "1rem"}
                :scene {:palette [:accent] :background :bg :fallback :accent}}
        text (json/write source)
        parsed (tokens/parse "brand.tokens.json" text)]
    (is (= source parsed) "JSON round-trips to the same token map")
    (is (map? (tokens/assert-tokens! parsed)))
    (testing "scene references come back as keywords, not strings"
      (is (= [:accent] (get-in parsed [:scene :palette])))
      (is (= :bg (get-in parsed [:scene :background]))))
    (testing "the extension picks the reader, and EDN still works"
      (is (= parsed (tokens/parse "brand.tokens.edn" (pr-str source)))))))

(deftest duplicate-leaf-keys-are-rejected
  (is (= ["gap"] (tokens/duplicate-keys {:color {:gap "#fff"} :scale {:gap "1rem"}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Duplicate token leaf keys"
       (tokens/assert-tokens! {:color {:gap "#fff"} :scale {:gap "1rem"}}))))

(deftest scene-palette-must-name-known-colors
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Scene palette names unknown colors"
       (tokens/assert-tokens! {:color {:teal "#0f0"} :scene {:palette [:teal :nope]}}))))

(deftest css-emits-one-custom-property-per-token
  (let [source (read-tokens default-source)
        out (tokens/css source default-source)]
    (is (str/includes? out ":root {"))
    (is (str/includes? out "--plato-accent: #f0ac5f;"))
    (is (str/includes? out "--plato-radius: 0.65rem;"))
    (is (str/includes? out "--plato-teal: #5CD0B3;"))
    (is (= (count (tokens/entries source))
           (count (re-seq #"(?m)^  --plato-" out))))))

(deftest manifest-carries-value-and-var-name
  (let [m (tokens/manifest (read-tokens default-source))]
    (is (= "Plato Midnight" (:name m)))
    (is (= {:value "#f0ac5f" :var "--plato-accent"} (get-in m [:tokens "accent"])))
    (is (= :bg (get-in m [:scene :background])))))

(deftest generated-artifacts-are-current
  (let [source (read-tokens default-source)]
    (is (= (tokens/css source default-source) (slurp "public/css/plato-theme.css"))
        "public/css/plato-theme.css is stale — regenerate the theme")
    (is (= (tokens/json source) (slurp "theme/plato.tokens.json"))
        "theme/plato.tokens.json is stale — regenerate the theme")
    (is (= (tokens/cljc source 'plato.theme default-source) (slurp "src/plato/theme.cljc"))
        "src/plato/theme.cljc is stale — regenerate the theme")
    (is (= (tokens/css (read-tokens "theme/acme.tokens.edn") "theme/acme.tokens.edn")
           (slurp "public/css/acme-theme.css"))
        "public/css/acme-theme.css is stale — regenerate the theme")
    (is (= (tokens/css (composed "theme/acme-print.tokens.edn")
                       "theme/acme-print.tokens.edn")
           (slurp "public/css/acme-print-theme.css"))
        "public/css/acme-print-theme.css is stale — regenerate the theme")
    (is (= (tokens/sty source "plato-beamer" default-source)
           (slurp "theme/plato-beamer.sty"))
        "theme/plato-beamer.sty is stale — regenerate the theme")
    (is (= (tokens/sty (read-tokens "theme/acme.tokens.edn") "acme-beamer" "theme/acme.tokens.edn")
           (slurp "theme/acme-beamer.sty"))
        "theme/acme-beamer.sty is stale — regenerate the theme")))

(deftest hex-colors-only-reach-latex
  (is (= "F59E0B" (tokens/hex-color "#f59e0b")))
  (is (= "AABBCC" (tokens/hex-color "#abc")) "a 3-digit hex expands")
  (is (nil? (tokens/hex-color "rgba(255, 255, 255, 0.028)"))
      "\\definecolor takes hex; an rgba() value has no LaTeX spelling")
  (is (nil? (tokens/hex-color "rebeccapurple"))
      "an unknown xcolor name is a compile error, so a named colour is dropped"))

(deftest the-style-file-and-the-stylesheet-carry-the-same-colors
  (testing "one token source, two projections: a colour cannot differ between them"
    (let [source (read-tokens "theme/acme.tokens.edn")
          sty (tokens/sty source "acme-beamer" "theme/acme.tokens.edn")
          css (tokens/css source "theme/acme.tokens.edn")]
      (doseq [[k v] (:color source)
              :let [hex (tokens/hex-color v)]
              :when hex]
        (is (str/includes? sty (str "\\definecolor{plato" (name k) "}{HTML}{" hex "}"))
            (str "the style file is missing " k))
        (is (str/includes? css (str (tokens/var-name source k) ": " v ";"))
            (str "the stylesheet is missing " k))))))

(deftest the-style-file-is-a-loadable-package
  (let [sty (tokens/sty (read-tokens default-source) "plato-beamer" default-source)]
    (is (str/starts-with? sty "% ") "every banner line must be a LaTeX comment")
    (is (every? #(or (str/blank? %) (str/starts-with? % "%") (str/starts-with? % "\\"))
                (str/split-lines sty))
        "a line that is neither comment nor command would typeset as stray text")
    (is (str/includes? sty "\\ProvidesPackage{plato-beamer}")
        "LaTeX requires the package name to match the file's base name")
    (is (str/includes? sty "\\RequirePackage{xcolor}"))
    (is (str/includes? sty "\\setbeamercolor{structure}{fg=platoaccent}"))))

(deftest the-generated-namespace-is-the-whole-token-map
  (let [source (composed "theme/acme-print.tokens.edn")
        generated (tokens/cljc source 'acme.print "theme/acme-print.tokens.edn")
        read-back (->> generated
                       (re-find #"(?s)\(def tokens\s+\"[^\"]*\"\s+(\{.*\})\)")
                       second
                       edn/read-string)]
    (testing "a theme's rules survive the projection to Clojure data"
      (is (seq (:rules source)) "fixture must declare rules")
      (is (= (:rules source) (:rules read-back))))
    (testing "and so does everything else"
      (is (= (select-keys source [:meta :color :scale :type :scene])
             (select-keys read-back [:meta :color :scale :type :scene]))))))

(deftest color-palette-comes-from-the-theme
  (is (= (tokens/palette theme/tokens) color/palette))
  (is (= "#5CD0B3" (color/hex :teal)))
  (is (= "#8A8F98" (color/hex :unknown-name)))
  (is (= "#abc" (color/hex "#abc"))))
