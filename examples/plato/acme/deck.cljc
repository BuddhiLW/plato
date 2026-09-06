(ns plato.acme.deck
  "Acme Corp — Q3 Product Review: the flagship demo deck.

   Pure CLJC data. The same `model` value is mounted live by shadow-cljs,
   rendered to static HTML on the JVM through plato.html, and asserted by
   plato.acme-test."
  (:require [plato.content :as content]
            [plato.deck :as deck]
            [plato.desargues :as desargues]))

;; ── assets ──────────────────────────────────────────────────────────────────

(def assets
  "Media key -> path, relative to the served root (public/)."
  {:logo      "assets/acme/logo.svg"
   :hero      "assets/acme/hero.jpg"
   :chart     "assets/acme/chart.png"
   :pipeline  "assets/acme/pipeline.svg"
   :loop      "assets/acme/loop.gif"
   :demo-mp4  "assets/acme/demo.mp4"
   :demo-webm "assets/acme/demo.webm"
   :poster    "assets/acme/demo-poster.jpg"
   :ambient   "assets/acme/ambient.mp4"
   :chime     "assets/acme/chime.mp3"
   :embed     "assets/acme/embed.html"})

;; ── scene graph ─────────────────────────────────────────────────────────────

(def arr-graph
  "Desargues scene: three quarter bubbles drawn, counted up to their ARR, then
   the headline lands and settles. 11 nodes, 7 steps."
  {:scene :acme-arr
   :nodes
   {1  {:id 1 :node :text :text "ARR by quarter (USD millions)"
        :at [0 3.1] :opts {:font-size 34 :color :white :weight "BOLD"}}
    2  {:id 2 :node :circle :at [-4.2 0.3]
        :opts {:radius 1.15 :color :teal}
        :fill {:color :teal :opacity 0.22}
        :stroke {:color :teal :width 4}}
    3  {:id 3 :node :decimal :value 0 :at [-4.2 0.3]
        :opts {:font-size 34 :color :white :num-decimal-places 0}}
    4  {:id 4 :node :circle :at [0 0.3]
        :opts {:radius 1.15 :color :gold}
        :fill {:color :gold :opacity 0.22}
        :stroke {:color :gold :width 4}}
    5  {:id 5 :node :decimal :value 0 :at [0 0.3]
        :opts {:font-size 34 :color :white :num-decimal-places 0}}
    6  {:id 6 :node :circle :at [4.2 0.3]
        :opts {:radius 1.15 :color :green}
        :fill {:color :green :opacity 0.22}
        :stroke {:color :green :width 4}}
    7  {:id 7 :node :decimal :value 0 :at [4.2 0.3]
        :opts {:font-size 34 :color :white :num-decimal-places 0}}
    8  {:id 8 :node :text :text "Q1" :at [-4.2 -1.4]
        :opts {:font-size 22 :color :grey}}
    9  {:id 9 :node :text :text "Q2" :at [0 -1.4]
        :opts {:font-size 22 :color :grey}}
    10 {:id 10 :node :text :text "Q3" :at [4.2 -1.4]
        :opts {:font-size 22 :color :grey}}
    11 {:id 11 :node :text :text "+42% year over year — best quarter on record"
        :at [0 -2.6] :opts {:font-size 26 :color :gold}}}
   :steps
   [{:step :play
     :anims [{:anim :appear :target 1 :opts {:run-time 0.6}}
             {:anim :appear :target :scene :ids [8 9 10] :opts {:run-time 0.6}}]}
    {:step :play
     :anims [{:anim :draw :target 2 :opts {:run-time 0.8}}
             {:anim :appear :target 3 :opts {:run-time 0.5}}]}
    {:step :play
     :anims [{:anim :draw :target 4 :opts {:run-time 0.8}}
             {:anim :appear :target 5 :opts {:run-time 0.5}}
             {:anim :draw :target 6 :opts {:run-time 0.8}}
             {:anim :appear :target 7 :opts {:run-time 0.5}}]}
    {:step :play
     :anims [{:anim :count-to :target 3 :value 74 :opts {:run-time 1.4}}
             {:anim :count-to :target 5 :value 96 :opts {:run-time 1.4}}
             {:anim :count-to :target 7 :value 118 :opts {:run-time 1.4}}]}
    {:step :play
     :anims [{:anim :appear :target 11 :opts {:run-time 0.6}}
             {:anim :emphasize :target 6 :color :gold :opts {:run-time 1.4}}]}
    {:step :play
     :anims [{:anim :glide :target 11 :to [0 -3.1] :opts {:run-time 0.9}}
             {:anim :emphasize :target 11 :color :white :opts {:run-time 0.9}}]}
    {:step :hold :seconds 1.0}]})

;; ── code listings ───────────────────────────────────────────────────────────

(def deck-snippet
  "The Clojure source shown on the :deck-as-data slide, one form per line."
  "(deck/deck
 {:title \"Acme Corp — Q3 Product Review\"
  :slides
  [(deck/slide :metrics
     (content/table [\"Metric\" \"Q2\" \"Q3\"]
                    [[\"ARR\" \"$96.0M\" \"$118.4M\"]]))
   (deck/slide :product-demo
     (content/video \"assets/acme/demo.mp4\"))]})")

(def export-snippet
  "The Clojure source shown on the :deep-dive-code slide."
  "(require '[plato.acme.deck :as acme]
         '[plato.html :as html])

(spit \"dist/acme.html\"
      (html/deck->html acme/model
                       {:asset-base \".\" :theme \"night\"}))")

;; ── deck ────────────────────────────────────────────────────────────────────

(def model
  (deck/deck
   {:title "Acme Corp — Q3 Product Review"
    :description "Acme Corp — Q3 Product Review, the Plato flagship demo deck"
    :lang "en"
    ;; The :margins slide sets a formula in \( \), so this deck asks for the
    ;; math plugin. Both render targets read this one key.
    :math? true
    :config {:hash true
             :history true
             :controls true
             :progress true
             :center true
             :slide-number "c/t"
             :transition :slide
             :background-transition :fade}
    :slides
    [(deck/slide
      :title
      [:div
       (content/kicker "Acme Corp · Internal")
       [:h1 "Q3 Product Review"]
       [:p "Revenue, delivery, and the three bets that carry us into Q4."]
       [:p.fragment "Platform team · 26 August 2026"]]
      {:background-image (:hero assets)
       :background-opacity 0.35
       :background-size "cover"
       :notes "Ninety seconds of framing, then straight into the agenda."})

     (deck/slide
      :agenda
      [:div
       [:h2 "Agenda"]
       (content/bullets
        ["Where the numbers landed"
         "What shipped, and what it looks like"
         "A live walk-through of the ARR curve"
         "Three bets for Q4"
         "Risks we are carrying"]
        {:fragments? true :effect :fade-up})]
      {:notes "Five beats. Hold questions until the risks slide."})

     (deck/slide
      :brand
      [:div
       [:h2 "One company, one system"]
       (content/columns
        [(content/column
          (content/image (:logo assets) {:alt "Acme Corp wordmark" :width 320})
          {:width :fill})
         (content/column
          [:div
           [:p "The mark, type scale, and scene palette share one token file. Rebrand once; slides, exports, and SVG scenes follow."]]
          {:width :fill})]
        {:widths ["1fr" "1.4fr"] :gap "2.5rem"})]
      {:notes "The logo is vector, so it stays crisp on the projector."})

     (deck/slide
      :revenue-chart
      [:div
       [:h2 "Revenue by quarter"]
       (content/image (:chart assets)
                      {:alt "Bar chart of Acme booked revenue by quarter"
                       :caption "Booked revenue, FY26 Q1–Q3 (USD millions)"})]
      {:notes "Q3 is the first quarter above the plan line."})

     (deck/slide
      :pipeline
      [:div
       [:h2 "Delivery pipeline"]
       (content/image (:pipeline assets)
                      {:alt "Acme delivery pipeline: ingest, enrich, publish"
                       :caption "Ingest → enrich → publish, with the new replay lane"
                       :fit :contain})]
      {:notes "The replay lane is the only new box; everything else was already live."})

     (deck/slide
      :throughput-loop
      [:div
       [:h2 "Throughput, in motion"]
       (content/image (:loop assets)
                      {:alt "Animated loop of the throughput ticker"
                       :caption "Two-second loop of the live throughput ticker"})])

     (deck/slide
      :product-demo
      [:div
       [:h2 "The new replay console"]
       (content/video (:demo-mp4 assets)
                      {:poster (:poster assets)
                       :sources [{:src (:demo-webm assets) :type "video/webm"}
                                 {:src (:demo-mp4 assets) :type "video/mp4"}]
                       :controls? true
                       :caption "Replaying a failed batch in five seconds"})]
      {:notes "Play it once, then take questions. WebM first, MP4 as the fallback."})

     (deck/slide
      :ambient
      [:div
       (content/kicker "Always on")
       [:h2 "The platform does not stop"]
       [:p.fragment "99.98% availability across the quarter."]]
      {:background-video (:ambient assets)
       :background-video-loop true
       :background-video-muted true
       :background-opacity 0.55
       :notes "Reveal owns this video; it loops muted behind the text."})

     (deck/slide
      :sound
      [:div
       [:h2 "The alert that woke nobody"]
       (content/audio (:chime assets)
                      {:controls? true
                       :caption "The new low-urgency chime — three seconds, no pager"})])

     (deck/slide
      :playground
      [:div
       [:h2 "Try it here"]
       (content/embed (:embed assets)
                      {:title "Acme pricing playground"
                       :ratio "16 / 9"
                       :caption "A self-contained page, embedded straight into the slide"})]
      {:background-color "#12100e"})

     (deck/slide
      :deck-as-data
      [:div
       [:h2 "The deck is data"]
       (content/code :clojure deck-snippet
                     {:highlight "1|3-5|"
                      :caption "examples/plato/acme/deck.cljc"})]
      {:notes "Step the highlight: the whole form, then the slide vector, then all of it."})

     (deck/slide
      :release-notes
      (str "## Shipped in Q3\n\n"
           "- Replay console (GA)\n"
           "- Token-driven theming\n"
           "- Static HTML export\n"
           "- Speaker-note sync\n\n"
           "> Every one of these landed behind a flag first.")
      {:notes "A whole slide of markdown, with speaker notes beside it."})

     (deck/slide
      :changelog
      [:div
       [:h2 "Breaking changes"]
       (content/markdown
        (str "1. `plato build` replaces `plato.snapshot/write!`\n"
             "2. Theme tokens move to `theme/*.tokens.edn`\n"
             "3. Slide ids are required and must be unique\n\n"
             "_Everything else is additive._"))])

     (deck/slide
      :unit-economics
      [:div
       (content/kicker "Unit economics")
       [:h2 "Contribution margin"]
       [:p "\\[ m = \\frac{R - C_v}{R} = \\frac{118.4 - 41.2}{118.4} = 0.652 \\]"]
       [:p.fragment "Each point of margin is about \\( \\$1.2\\text{M} \\) of annual free cash flow."]]
      {:notes "KaTeX renders these; do not read the formula aloud."})

     (deck/slide
      :metrics
      [:div
       [:h2 "The numbers"]
       (content/table
        ["Metric" "Q2 FY26" "Q3 FY26" "QoQ" "Target"]
        [["ARR" "$96.0M" "$118.4M" "+23.3%" "$115.0M"]
         ["Net revenue retention" "112%" "119%" "+7 pts" "115%"]
         ["Gross margin" "61.0%" "65.2%" "+4.2 pts" "64.0%"]
         ["Weekly active accounts" "8,410" "10,972" "+30.5%" "10,000"]]
        {:caption "Finance close, 12 August 2026 · USD"})]
      {:notes "Four rows, four beats. The only miss is nothing — all four cleared target."})

     (deck/slide
      :bets
      [:div
       [:h2 "Three bets for Q4"]
       (content/cards
        [{:icon "◆"
          :title "Replay everywhere"
          :body "Every pipeline stage replayable from the console, not just ingest."}
         {:icon "◈"
          :title "Self-serve onboarding"
          :body "First event to first dashboard in under ten minutes, unassisted."}
         {:icon "◉"
          :title "Cost per event"
          :body "Halve it by moving enrichment off the hot path."}]
        {:columns 3 :fragments? true})]
      {:notes "One owner per bet; owners are in the appendix."})

     (deck/slide
      :voice-of-customer
      (content/quotation
       "We replaced four internal dashboards with one Acme replay link, and on-call load halved inside a month."
       {:cite "Director of Platform Engineering, Northwind Logistics"}))

     (deck/slide
      :risk
      [:div
       [:h2 "What could go wrong"]
       (content/note
        (content/bullets
         ["Replay storage grows faster than the retention policy assumed."
          "Two of the three bets depend on the same four engineers."
          "The enrichment move needs a migration window we have not booked."])
        {:tone :warn :title "Carrying into Q4"})]
      {:notes "Say the staffing risk out loud; it is the one the board will ask about."})

     (deck/slide
      :arr-scene
      (desargues/scene arr-graph {:controls? true :autoplay? true})
      {:transition :fade
       :notes "Scrub with the scene controls. This is one Clojure map, compiled to a timeline."})

     (deck/slide
      :focus-metric
      [:div.plato-focus
       (content/kicker "The one number")
       [:h2 {:data-id "focus-value" :style {:font-size "3rem"}} "$118.4M"]
       [:p {:data-id "focus-label"} "Annual recurring revenue"]]
      {:auto-animate true})

     (deck/slide
      :focus-metric-growth
      [:div.plato-focus
       (content/kicker "The one number")
       [:h2 {:data-id "focus-value"
             :style {:font-size "7rem" :color "var(--plato-accent)"}} "$118.4M"]
       [:p {:data-id "focus-label"} "Annual recurring revenue"]
       [:p.fragment {:data-id "focus-delta"}
        "+23.3% quarter over quarter · +42% year over year"]]
      {:auto-animate true
       :auto-animate-duration 1.2
       :auto-animate-easing "cubic-bezier(0.4, 0, 0.2, 1)"
       :notes "Reveal matches the two headings by data-id and grows one into the other."})

     (deck/stack
      :deep-dive
      [(deck/slide
        :deep-dive-code
        [:div
         [:h3 "Exporting the deck"]
         (content/code :clojure export-snippet
                       {:highlight "1-2|4-6"
                        :caption "The same model, rendered on the JVM"})])
       (deck/slide
        :deep-dive-architecture
        [:div
         [:h3 "Where the numbers come from"]
         (content/image (:chart assets)
                        {:alt "Revenue chart"
                         :caption "One chart, one source of truth: the finance close"
                         :fit :contain})])
       (deck/slide
        :deep-dive-actions
        [:div
         [:h3 "Follow-ups"]
         (content/bullets
          ["Book the enrichment migration window before 5 September."
           "Publish the replay retention policy."
           "Hire two platform engineers."]
          {:ordered? true :fragments? true})])])

     (deck/slide
      :closing
      [:div
       (content/kicker "Q4 starts Monday")
       [:h2 "Ship the replay lane. Halve the cost per event."]
       [:p "Owners are in the appendix; the board pack goes out Friday."]
       [:p.fragment [:strong "Questions?"]]]
      {:background-gradient "linear-gradient(135deg, #12100e 0%, #7a4a05 55%, #f59e0b 100%)"
       :notes "End here. Do not advance into the appendix unless asked."})]}))
