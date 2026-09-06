(ns plato.html-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.html :as html]))

(def model
  (deck/deck
   {:title "Acme"
    :slides
    [(deck/slide :cover [:h1 "Acme"]
                 {:background-image "assets/acme/hero.jpg"
                  :transition :zoom
                  :auto-animate true
                  :notes "Welcome"})
     (deck/slide :chart (content/image "assets/acme/chart.png"
                                       {:alt "Revenue"
                                        :caption "Revenue by quarter"}))
     (deck/slide :demo (content/video "assets/acme/demo.mp4"
                                      {:poster "assets/acme/demo-poster.jpg"
                                       :sources [{:src "assets/acme/demo.webm"
                                                  :type "video/webm"}
                                                 {:src "assets/acme/demo.mp4"
                                                  :type "video/mp4"}]}))
     (deck/slide :md "## Markdown\n\n- one\n- two")
     (deck/stack :chapter
                 [(deck/slide :chapter-intro [:h2 "Chapter"])
                  (deck/slide :chapter-detail [:p "Detail"])])]}))

(def page (html/deck->html model))

(deftest document-shell
  (is (str/starts-with? page "<!doctype html>"))
  (is (str/includes? page "<html lang=\"en\">"))
  (is (str/includes? page "<meta charset=\"utf-8\">"))
  (is (str/includes? page
                     "<meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"))
  (is (str/includes? page "<title>Acme</title>"))
  (is (str/includes? page "<div class=\"reveal\"><div class=\"slides\">")))

(deftest stylesheets-and-runtime
  (testing "vendored css"
    (is (str/includes? page "href=\"./vendor/reset.css\""))
    (is (str/includes? page "href=\"./vendor/reveal.css\""))
    (is (str/includes? page "href=\"./vendor/theme/night.css\""))
    (is (str/includes? page "href=\"./vendor/highlight/monokai.css\""))
    (is (str/includes? page "href=\"./css/plato.css\"")))
  (testing "reveal runtime and every enabled plugin, at the path the table names"
    (is (str/includes? page "<script src=\"./vendor/reveal.js\"></script>"))
    (doseq [src (html/plugin-scripts html/default-opts)]
      (is (str/includes? page (str "src=\"." src "\"")) src))
    (testing "highlight is plato's own slim build, not the 921 KB dist plugin"
      (is (str/includes? page "/vendor/plato-highlight/main.js"))
      (is (not (str/includes? page "/vendor/plugin/highlight.js")))))
  (testing "math is opt-in, because the vendored build fetches KaTeX from a CDN"
    (is (not (str/includes? page "/vendor/plugin/math.js")))
    (let [with-math (html/deck->html model {:math? true})]
      (is (str/includes? with-math "/vendor/plugin/math.js"))
      (is (str/includes? with-math "RevealMath.KaTeX")))))

(deftest init-uses-plugin-globals
  (is (str/includes? page "Reveal.initialize(Object.assign("))
  (testing "the bootstrap names the plugins layout depends on, and only those"
    (is (str/includes? page "plugins: [RevealMarkdown, RevealHighlight]")))
  (testing "a plugin nothing on the first paint needs loads async and registers
            itself on load — Reveal initializes a plugin registered after ready"
    (doseq [{:keys [global] :as plugin} (filter :async? (html/active-plugins html/default-opts))]
      (is (str/includes? page (str "onload=\"Reveal.registerPlugin(" global ")\""))
          global)
      (is (str/includes? page (str "<script async=\"\" onload=\"Reveal.registerPlugin(" global
                                   ")\" src=\"." (html/plugin-src plugin) "\">"))
          global)))
  (testing "the script list and the globals list are projections of one definition:
            every enabled plugin is scripted, and it is registered exactly once"
    (let [enabled (html/active-plugins html/default-opts)]
      (is (= (count enabled) (count (html/plugin-scripts html/default-opts))))
      (is (= (count enabled)
             (+ (count (html/plugin-globals html/default-opts))
                (count (filter :async? enabled)))))))
  (testing "deck config is emitted as JSON, not escaped"
    (is (str/includes? page "\"hash\":true"))
    (is (str/includes? page "\"transition\":\"slide\""))
    (is (str/includes? page "\"backgroundTransition\":\"fade\""))
    (is (str/includes? page "\"navigationMode\":\"default\""))))

(deftest the-fit-runtime-rides-along-only-when-the-deck-needs-it
  (testing "a deck nobody asked to shrink pays nothing for the feature"
    (is (not (html/needs-fit-runtime? model)))
    (is (not (str/includes? page (:src html/fit-runtime))))
    (is (not (str/includes? page (:call html/fit-runtime)))))
  (let [shrinking (deck/deck {:slides [(deck/slide :a [:p "a"])
                                       (deck/slide :b [:p "b"] {:overflow :shrink})]})]
    (testing "one slide declaring :shrink is enough, at any depth"
      (is (html/needs-fit-runtime? shrinking))
      (is (html/needs-fit-runtime?
           (deck/deck {:slides [(deck/stack :s [(deck/slide :a [:p "a"]
                                                            {:overflow :shrink})])]}))))
    (testing "and the page then carries both the script and the call, because
              the scale is measured from a laid-out page and an export without
              the runtime would show the overflow the author answered for"
      (let [html (html/deck->html shrinking)]
        (is (str/includes? html (str "<script src=\"." (:src html/fit-runtime) "\">")))
        (is (str/includes? html (:call html/fit-runtime)))
        (testing "chained off initialize, not called beside it"
          (is (str/includes? html (str ".then(function () { " (:call html/fit-runtime))))))))
  (testing "--fit asks for it on a deck that does not declare :shrink, so an
            export can be asked whether it fits even when nothing shrinks"
    (is (str/includes? (html/deck->html model {:fit? true}) (:src html/fit-runtime)))))

(deftest the-scene-runtime-is-opt-in
  (testing "an export is the standalone final-frame page unless asked otherwise"
    (is (not (str/includes? page (:src html/scene-runtime))))
    (is (not (str/includes? page (:call html/scene-runtime)))))
  (testing ":live-scenes? links the bundle ASYNC, off the first paint's path,
            and hydrates once Reveal has laid out"
    (let [live (html/deck->html model {:live-scenes? true})]
      (is (str/includes? live (str "<script async=\"\" src=\"." (:src html/scene-runtime) "\">")))
      (is (str/includes? live (str ".then(function () { " (:call html/scene-runtime))))))
  (testing "with fit as well, both calls share the one .then, fit first"
    (let [both (html/deck->html model {:fit? true :live-scenes? true})]
      (is (str/includes? both (str ".then(function () { " (:call html/fit-runtime) " "
                                   (:call html/scene-runtime) " })"))))))

(deftest math-is-read-off-the-deck
  (testing "a deck that declares {:math? true} carries the plugin without a flag,
            so the exporter and the live shell cannot disagree about it"
    (let [with-math (html/deck->html (assoc model :math? true))]
      (is (str/includes? with-math "/vendor/plugin/math.js"))
      (testing "loaded async, like every plugin the first paint does not need"
        (is (str/includes? with-math "onload=\"Reveal.registerPlugin(RevealMath.KaTeX)\"")))
      (testing "and told where the vendored KaTeX is, so the page never phones home"
        (is (str/includes? with-math "\"katex\":{\"local\":\"./vendor/katex\"}")))))
  (testing "the config both render targets hand Reveal is one function of the deck"
    (let [deck (assoc model :math? true :config {:hash true :katex {:macros {"\\R" "\\mathbb{R}"}}})]
      (is (= {:hash true :katex {:local "./vendor/katex" :macros {"\\R" "\\mathbb{R}"}}}
             (html/reveal-config deck {}))
          "a deck's own :katex options survive, only :local is filled in")
      (is (= {:hash true :katex {:local "/talk/vendor/katex" :macros {"\\R" "\\mathbb{R}"}}}
             (html/reveal-config deck {:asset-base "/talk"}))
          "and it follows :asset-base like every other vendored file")
      (is (= {:hash true} (html/reveal-config (assoc model :config {:hash true}) {}))
          "a deck without math carries no KaTeX config at all")))
  (testing "and a deck that does not still pays nothing for it"
    (is (not (str/includes? page "/vendor/plugin/math.js")))
    (is (not (str/includes? page "katex")))))

(deftest description-becomes-a-meta-tag
  (testing "absent by default"
    (is (not (str/includes? page "name=\"description\""))))
  (testing "from the deck"
    (is (str/includes? (html/deck->html (assoc model :description "A deck"))
                       "<meta content=\"A deck\" name=\"description\">")))
  (testing "the option wins over the deck"
    (is (str/includes? (html/deck->html (assoc model :description "A deck")
                                        {:description "Override"})
                       "content=\"Override\""))))

(deftest after-slides-land-between-the-deck-and-the-scripts
  (let [out (html/deck->html model {:after-slides [[:a.plato-backlink {:href "x.html"} "back"]]})
        link (str/index-of out "class=\"plato-backlink\"")]
    (is (some? link))
    (is (< (str/index-of out "<div class=\"reveal\">") link))
    (is (< link (str/index-of out "vendor/reveal.js")))))

(deftest slide-options-become-data-attrs
  (is (str/includes? page "id=\"cover\""))
  (is (str/includes? page "data-background-image=\"assets/acme/hero.jpg\""))
  (is (str/includes? page "data-transition=\"zoom\""))
  (is (str/includes? page "data-auto-animate=\"\""))
  (testing "section-attrs stays the single definition"
    (is (= (deck/section-attrs {:id :cover :transition :zoom})
           (second (html/slide-hiccup {:id :cover :transition :zoom
                                       :content [:h1 "Acme"]}))))))

(deftest hiccup-content
  (is (str/includes? page "<h1>Acme</h1>"))
  (is (str/includes? page "<section id=\"chapter-detail\"><p>Detail</p></section>")))

(deftest notes-become-an-aside
  (is (str/includes? page "<aside class=\"notes\">Welcome</aside>"))
  (is (not (str/includes? page "<aside class=\"notes\"></aside>"))))

(deftest media-content
  (testing "image"
    (is (str/includes? page "<figure class=\"plato-figure\">"))
    (is (str/includes? page "<img alt=\"Revenue\" src=\"assets/acme/chart.png\">"))
    (is (str/includes? page
                       "<figcaption class=\"plato-caption\">Revenue by quarter</figcaption>")))
  (testing "video"
    (is (str/includes? page "class=\"plato-video\""))
    (is (str/includes? page "poster=\"assets/acme/demo-poster.jpg\""))
    (is (str/includes? page "controls=\"\""))
    (is (str/includes? page
                       "<source src=\"assets/acme/demo.webm\" type=\"video/webm\">"))
    (is (str/includes? page
                       "<source src=\"assets/acme/demo.mp4\" type=\"video/mp4\">"))))

(deftest string-content-becomes-a-markdown-section
  (testing "data-markdown sits on a nested div, so the plugin's rewrite cannot
            swallow the notes aside beside it — the browser suite drives this"
    (is (str/includes? page "<section id=\"md\"><div data-markdown=\"\">"))
    (is (not (str/includes? page "<section id=\"md\" data-markdown"))))
  (testing "the template is a textarea, so </script> in the markdown is inert"
    (is (str/includes? page
                       "<textarea data-template=\"\">## Markdown\n\n- one\n- two</textarea>"))))

(deftest stacks-nest-sections
  (is (str/includes? page "<section id=\"chapter\"><section id=\"chapter-intro\">"))
  (is (= [:section {:id "chapter"}]
         (subvec (html/entry-hiccup (deck/stack :chapter [])) 0 2)))
  (is (= :div.slides (first (html/slides-hiccup model))))
  (is (= 5 (count (rest (html/slides-hiccup model))))))

(deftest json-emitter
  (testing "kebab-case keys become camelCase"
    (is (= "hash" (html/camel-key :hash)))
    (is (= "backgroundTransition" (html/camel-key :background-transition)))
    (is (= "navigationMode" (html/camel-key :navigation-mode)))
    (is (= "autoAnimateEasing" (html/camel-key :auto-animate-easing))))
  (testing "scalars"
    (is (= "null" (html/->json nil)))
    (is (= "true" (html/->json true)))
    (is (= "false" (html/->json false)))
    (is (= "960" (html/->json 960)))
    (is (= "\"slide\"" (html/->json :slide)))
    (is (= "\"hi\"" (html/->json "hi"))))
  (testing "collections"
    (is (= "{\"hash\":true}" (html/->json {:hash true})))
    (is (= "{\"navigationMode\":\"linear\"}" (html/->json {:navigation-mode :linear})))
    (is (= "[1,2,3]" (html/->json [1 2 3])))
    (is (= "{\"keyboard\":{\"autoSlide\":false}}"
           (html/->json {:keyboard {:auto-slide false}}))))
  (testing "escaping keeps the inline script well-formed"
    (is (= "\"a\\\"b\"" (html/->json "a\"b")))
    (is (= "\"a\\nb\"" (html/->json "a\nb")))
    (is (= "\"\\u003C/script>\"" (html/->json "</script>")))))

(deftest opts-are-honoured
  (let [out (html/deck->html model {:asset-base "static"
                                    :theme "black"
                                    :title "Custom"
                                    :stylesheets ["css/extra.css"]
                                    :scripts ["js/extra.js"]})]
    (is (str/includes? out "<title>Custom</title>"))
    (is (str/includes? out "href=\"static/vendor/reveal.css\""))
    (is (str/includes? out "href=\"static/vendor/theme/black.css\""))
    (is (str/includes? out "<script src=\"static/vendor/plugin/markdown.js\"></script>"))
    (is (str/includes? out "src=\"static/vendor/plugin/notes.js\""))
    (is (str/includes? out "href=\"css/extra.css\""))
    (is (str/includes? out "<script src=\"js/extra.js\"></script>"))
    (testing "extra scripts load before the bootstrap"
      (is (< (str/index-of out "js/extra.js")
             (str/index-of out "Reveal.initialize"))))))
