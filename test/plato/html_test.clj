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
  (testing "reveal runtime and every enabled plugin"
    (is (str/includes? page "<script src=\"./vendor/reveal.js\"></script>"))
    (doseq [plugin (html/plugin-scripts html/default-opts)]
      (is (str/includes? page
                         (str "<script src=\"./vendor/plugin/" plugin ".js\"></script>"))
          plugin)))
  (testing "math is opt-in, because the vendored build fetches KaTeX from a CDN"
    (is (not (str/includes? page "/vendor/plugin/math.js")))
    (let [with-math (html/deck->html model {:math? true})]
      (is (str/includes? with-math "/vendor/plugin/math.js"))
      (is (str/includes? with-math "RevealMath.KaTeX")))))

(deftest init-uses-plugin-globals
  (is (str/includes? page "Reveal.initialize(Object.assign("))
  (is (str/includes? page
                     (str "plugins: [RevealMarkdown, RevealHighlight, RevealNotes, "
                          "RevealSearch, RevealZoom]")))
  (testing "the script list and the globals list are projections of one definition"
    (is (= (count (html/plugin-scripts html/default-opts))
           (count (html/plugin-globals html/default-opts)))))
  (testing "deck config is emitted as JSON, not escaped"
    (is (str/includes? page "\"hash\":true"))
    (is (str/includes? page "\"transition\":\"slide\""))
    (is (str/includes? page "\"backgroundTransition\":\"fade\""))
    (is (str/includes? page "\"navigationMode\":\"default\""))))

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
  (is (str/includes? page "<section id=\"md\" data-markdown=\"\">"))
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
    (is (str/includes? out "<script src=\"static/vendor/plugin/notes.js\"></script>"))
    (is (str/includes? out "href=\"css/extra.css\""))
    (is (str/includes? out "<script src=\"js/extra.js\"></script>"))
    (testing "extra scripts load before the bootstrap"
      (is (< (str/index-of out "js/extra.js")
             (str/index-of out "Reveal.initialize"))))))
