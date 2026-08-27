(ns plato.example.core
  "The deck plato uses to explain itself.

   It is the site at buddhilw.github.io/plato, and it is also a fixture: the
   browser suite drives this page, so a slide that stops rendering fails CI."
  (:require [plato.content :as content]
            [plato.core :as plato]
            [plato.deck :as deck]
            [plato.desargues :as desargues]))

;; ── scene graphs ────────────────────────────────────────────────────────────
;; Both are the shape Desargues' RecordingBackend emits: :scene, :nodes, :steps.

(def animation-graph
  {:scene :plato-demo
   :nodes
   {1 {:id 1 :node :text :text "Desargues scene graph"
       :at [0 2.6] :opts {:font-size 34 :color :white :weight "BOLD"}}
    2 {:id 2 :node :circle :at [-3 0]
       :opts {:radius 1.15 :color :teal}
       :fill {:color :teal :opacity 0.25}
       :stroke {:color :teal :width 4}}
    3 {:id 3 :node :decimal :value 100 :at [-3 0]
       :opts {:font-size 38 :color :white :num-decimal-places 0}}
    4 {:id 4 :node :text :text "Pure data → live SVG"
       :at [2.2 0] :opts {:font-size 30 :color :gold}}}
   :steps
   [{:step :play
     :anims [{:anim :appear :target 1 :opts {:run-time 0.7}}
             {:anim :draw :target 2 :opts {:run-time 1.0}}]}
    {:step :play
     :anims [{:anim :appear :target 3 :opts {:run-time 0.5}}
             {:anim :appear :target 4 :opts {:run-time 0.5}}]}
    {:step :play
     :anims [{:anim :count-to :target 3 :value 500
              :opts {:run-time 1.5}}
             {:anim :emphasize :target 4 :opts {:run-time 1.5}}]}
    {:step :play
     :anims [{:anim :glide :target 2 :to [-1.1 0]
              :opts {:run-time 1.2}}
             {:anim :glide :target 3 :to [-1.1 0]
              :opts {:run-time 1.2}}]}
    {:step :hold :seconds 1.0}]})

(def layout-graph
  {:scene :layout-demo
   :kind :layout
   :nodes
   {1 {:node :text
       :id 1
       :content "Plato"
       :box {:x 5.851911 :y 0.5 :w 1.5184 :h 0.6716}
       :style {:font-size 40 :color :gold}}
    2 {:node :text
       :id 2
       :content "Desargues layout → browser"
       :box {:x 4.550359 :y 1.1716 :w 4.121504 :h 0.40296}
       :style {:font-size 24 :color :white}}}
   :steps
   [{:step :play
     :anims [{:anim :appear :target :scene :ids [1 2] :opts {}}]}
    {:step :hold :seconds 1.0}]
   :layout
   {:desargues.layout/box {:x 0 :y 0 :w 13.222222 :h 7.0}}})

;; ── sources quoted on slides ────────────────────────────────────────────────

(def deck-source
  "(deck/deck
 {:title  \"My talk\"
  :slides [(deck/slide :intro
                       [:h1 \"Hello\"]
                       {:background-image \"hero.jpg\"
                        :notes            \"Speaker-only\"})

           (deck/stack :details
                       [(deck/slide :one [:p \"First\"])
                        (deck/slide :two [:p \"Second\"])])]})")

;; Kept short on purpose: these sit side by side in two columns on the
;; :front-ends slide, so a line long enough to wrap there is a line the
;; audience reads clipped. The fit gate fails the build when they grow.
(def markdown-source
  "---
title: My talk
---

# Hello

Revenue and the *three bets*.

Note: ninety seconds.")

(def org-source
  "#+TITLE: My talk

* Hello

Revenue and the /three bets/.

:NOTES:
Ninety seconds.
:END:")

(def front-end-source
  "(ns talk.latex
  (:require [plato.source :as source]))

(defn ->deck [text]
  ...)

(source/register-extensions! :latex [\"tex\"])

(defmethod source/->deck :latex [_ text]
  (->deck text))")

(def content-source
  "(defmethod content/render :timeline [{:keys [events]}]
  (into [:ol.acme-timeline]
        (map (fn [e] [:li (:label e)]))
        events))

(defn timeline [events]
  {:plato/type :timeline :events events})")

(def cli-source
  "# any registered format, one command
plato build talk.md  -o dist/talk.html --assets public
plato build talk.org -o dist/talk.html
plato build deck.edn -o dist/talk.html

# tokens in, stylesheet out
plato theme theme/acme.tokens.edn -o css/acme.css")

(def tokens-source
  "{:meta  {:prefix \"plato\" :reveal-theme \"night\"}
 :color {:bg \"#0b0e13\" :accent \"#f0ac5f\"}
 :scene {:palette [:teal :gold] :background :bg}

 :rules
 [[:.reveal {:background [:token :bg]}
   [:.plato-kicker {:color [:token :accent]}]]]}")

;; ── the deck ────────────────────────────────────────────────────────────────

(def model
  (deck/deck
   {:title "Plato — a deck is a value"
    :config {:hash true
             :history true
             :controls true
             :progress true
             :center true
             :slide-number "c/t"
             :transition :slide}
    :slides
    [(deck/slide
      :welcome
      [:div
       (content/kicker "ClojureScript presentation engine")
       [:h1 "Plato"]
       [:p "A deck is an ordinary Clojure value, so the same source renders as a "
        "live presentation, as a standalone HTML file, and as an assertion in a "
        "test suite."]
       [:p.fragment "Slides become programs without becoming a JavaScript project."]]
      {:background-color "#0b0e13"
       :notes (content/bullets
               ["Arrow keys or space to advance."
                "O for overview, S for speaker notes, F for fullscreen, ? for shortcuts."
                "Every slide here is built by the engine it describes."])})

     (deck/slide
      :a-deck-is-a-value
      [:div
       (content/kicker "The model")
       [:h2 "A deck is a value"]
       (content/code :clojure deck-source
                     {:highlight "1|2|3-7|9-11|all"})
       [:p.fragment "Nothing is registered, mounted or configured. "
        [:code "deck/deck"] " validates the tree and throws rather than "
        "rendering something wrong."]]
      {:notes "Step the highlight: the map, the title, a slide with options, a vertical stack."})

     (deck/slide
      :content-is-open
      [:div
       (content/kicker "The content model")
       [:h2 "Sixteen kinds, one multimethod"]
       (content/cards
        [{:title "Media" :body "Images, GIFs, video with poster and sources, audio, iframes."}
         {:title "Prose" :body "Bullets, ordered lists, tables, quotations, callouts, kickers."}
         {:title "Code" :body "Language-tagged blocks with stepped line highlighting."}
         {:title "Layout" :body "Columns, card grids, groups and Reveal fragments."}]
        {:columns 2 :fragments? true})]
      {:notes "These are the shipped kinds, not the limit — the next slide adds one."})

     (deck/slide
      :adding-a-kind
      [:div
       (content/kicker "Open/closed")
       [:h2 "Adding a content kind"]
       (content/code :clojure content-source {:highlight "1-4|6-7"})
       (content/note
        [:span "One " [:code "defmethod"] ". No edit to the deck model, the "
         "browser shell, or the HTML exporter."]
        {:tone :ok :title "That is the whole extension point"})])

     (deck/slide
      :front-ends
      [:div
       (content/kicker "Authoring")
       [:h2 "Write it however you think"]
       (content/columns
        [(content/group [(content/kicker "Markdown")
                         (content/code :markdown markdown-source)])
         (content/group [(content/kicker "Org")
                         (content/code :org org-source)])])]
      {:notes "Both compile to the same deck value. docs/acme.md and docs/acme.org are the same deck written twice, and CI proves the slide ids and the HTML match."})

     (deck/slide
      :conversion-is-open
      [:div
       (content/kicker "The same lever, again")
       [:h2 "So is the set of formats"]
       (content/code :clojure front-end-source {:highlight "1-2|7|9-10"})
       [:p.fragment "Markdown, Org and EDN ship this way. "
        [:code "plato.cli"] " does not know their names."]]
      {:notes "A front end outside plato joins the CLI by existing — no case, no registry edit."})

     (deck/slide
      :animation
      (desargues/scene animation-graph {:controls? true :autoplay? true})
      {:transition :fade
       :notes (content/group
               [[:p "This is a Desargues RecordingBackend graph — the same "
                 [:code ":scene/:nodes/:steps"] " data the animation engine emits."]
                [:p "Scrub it. In a static export the identical scene renders as its final frame."]])})

     (deck/slide
      :layout
      (desargues/scene layout-graph {:controls? true :autoplay? true})
      {:transition :fade
       :notes "Emitted by desargues.scene/render-layout! — the declarative half of the contract."})

     (deck/slide
      :two-targets
      [:div
       (content/kicker "Render targets")
       [:h2 "Reagent here, a string on the JVM"]
       (content/table
        ["" "Browser" "Static export"]
        [["Runtime" "Reagent + Reveal.js" "JVM or a native binary"]
         ["Scenes" "Live, scrub-able SVG" "Final frame, same SVG code"]
         ["Slide attributes" "plato.deck/section-attrs" "plato.deck/section-attrs"]
         ["Output" "A running page" "One self-contained .html"]]
        {:caption "One definition of a slide's attributes serves both"})]
      {:notes "The shared row is the point: neither target owns the slide model."})

     (deck/slide
      :reproducible
      [:div
       (content/kicker "Determinism")
       [:h2 "The same deck, the same bytes"]
       (content/bullets
        ["Attribute order is the serializer's, not a map's iteration order."
         "Accents fold through plato's own NFD table, not the host's."
         "So the JVM and the native binary emit byte-identical pages."]
        {:fragments? true})]
      {:notes "No Clojure map keeps insertion order past a handful of entries, and the
               order it falls back to is a property of the host. A build tool cannot
               inherit that and still call itself reproducible."})

     (deck/stack
      :theming
      [(deck/slide
        :tokens
        [:div
         (content/kicker "Theming")
         [:h2 "One source, N projections"]
         (content/code :clojure tokens-source {:highlight "1-3|4|6-9"})])
       (deck/slide
        :projections
        [:div
         (content/kicker "Generated, and checked for drift")
         [:h2 "What one token file becomes"]
         (content/cards
          [{:title "CSS" :body "A :root custom property per token, then the theme's own rules."}
           {:title "Clojure" :body "The tokens as data — the scene palette is derived from it."}
           {:title "JSON" :body "A language-neutral manifest, value and variable per token."}]
          {:columns 3})
         [:p.fragment "A theme may " [:code ":extends"] " another and state only what "
          "differs. Scene colours and CSS colours cannot drift, because they are the same token."]])])

     (deck/slide
      :cli
      [:div
       (content/kicker "The CLI")
       [:h2 "Notes in, presentation out"]
       (content/code :bash cli-source {:highlight "1-4|6-7"})
       [:p.fragment "On the JVM, or as a self-contained native binary built with "
        [:a {:href "https://github.com/clojurewasm"} "ClojureWasm"] " — no JVM at run time."]])

     (deck/slide
      :markdown-native
      "## Native Markdown slides\n\nA slide whose content is a string becomes a `data-markdown` section, parsed by Reveal's own plugin in the browser.\n\n- Highlighting, math, search and notes keep working\n- The engine adds a model; it does not replace Reveal"
      {:notes "This slide is a plain string in the deck value."})

     (deck/slide
      :tested
      [:div
       (content/kicker "Evidence")
       [:h2 "Three suites"]
       (content/columns
        [(content/group
          [(content/kicker "JVM")
           (content/bullets ["Parsers, deck model, HTML"
                             "Theming, CLI, convergence"])])
         (content/group
          [(content/kicker "Browser")
           (content/bullets ["Playwright over this page"
                             "Did Reveal take the config?"])])
         (content/group
          [(content/kicker "Fit")
           (content/bullets ["Every slide measured in its box"
                             "An overflow fails the build"])])])]
      {:notes (content/group
               [[:p "The JVM suite cannot see layout at all — it asserts the blueprint."]
                [:p "This slide is checked by the fit gate, in a browser, at the size "
                 "you are reading it now."]])})

     (deck/slide
      :finish
      [:div
       (content/kicker "Plato")
       [:h2 "Author once. Present anywhere."]
       (content/quotation
        "A deck is an ordinary Clojure value."
        {:cite "the whole idea"})
       [:p [:a {:href "https://github.com/BuddhiLW/plato"} "github.com/BuddhiLW/plato"]
        " · " [:a {:href "acme.html"} "the Acme demo"]]]
      {:background-color "#111827"
       :notes "Acme exercises every content kind on 25 slides."})]}))

(defn- root [] (js/document.getElementById "app"))

(defn init []
  (plato/mount! (root) model))

(defn ^:dev/after-load remount!
  "Shadow hot-reload hook: tear the deck down and rebuild it, so an edit to the
   deck value shows without a page refresh. Reveal is destroyed on unmount, so
   the reload leaves no second instance behind."
  []
  (let [el (root)]
    (plato/unmount! el)
    (plato/mount! el model)))
