(ns plato.responsive
  "Deriving the responsive half of the theme, as rules over facts.

   Reveal already makes a deck fit ANY screen: it lays every slide out in one
   fixed box and CSS-transform-scales that box to the window. So the thing that
   actually breaks on a small screen is not the layout, it is LEGIBILITY --
   body text declared at 40px inside a 960px box paints at 15px on a 360px
   phone, and nobody reads it. Raising the type scale to fix that makes every
   slide taller in box units, which is the other constraint: the tallest slide
   still has to fit the box.

   Two constraints pulling opposite ways, per target screen, over a set of
   tokens. That is a solving problem, not a styling one, and writing it as
   rules buys the thing a nest of ifs cannot: every derived token value can say
   which rule produced it and from what. The generated stylesheet quotes that,
   so a value nobody remembers choosing can still be interrogated.

   This namespace is DATA AND PROJECTION only. The rules are values, the
   engine that runs them is `hive-cljs.derive`, and the wiring lives in
   scripts/gen_responsive.clj -- so nothing here drags a JVM dependency into a
   deck, and plato still loads on every host it claims to."
  (:require [clojure.string :as str]
            [plato.css :as css]))

;; ── the targets ─────────────────────────────────────────────────────────────

(def viewports
  "The screen classes a deck is derived for, in CSS pixels.

   Not a survey of devices: each is the SMALLEST screen in a class, because a
   token value that is legible on the smallest member is legible on all of
   them. `:query` is the media query that selects the class, and the classes
   are ordered narrowest first so a later one overrides an earlier one in the
   cascade."
  [{:id :phone   :w 360.0  :h 640.0
    :query "(max-width: 480px)"
    :doc "A small phone held upright."}
   {:id :tablet  :w 768.0  :h 1024.0
    :query "(min-width: 481px) and (max-width: 1024px)"
    :doc "A tablet, or a phone on its side."}
   {:id :laptop  :w 1280.0 :h 800.0
    :query "(min-width: 1025px) and (max-width: 1600px)"
    :doc "The screen a deck is usually authored on."}
   {:id :room    :w 1920.0 :h 1080.0
    :query "(min-width: 1601px)"
    :doc "A projector or a large display, seen from the back of a room."}])

(def legibility-floors
  "The smallest a token may PAINT, in CSS pixels, per token.

   These are physical-readability floors, not design preferences: 16px is the
   long-standing floor for body copy on a handheld, and a caption may go
   smaller because nobody reads a caption from the back of a room. A token
   with no floor here is not constrained, which is the right default for a
   colour or a radius.

   `:room` is the outlier and it is deliberate: a projected deck is read from
   metres away, so its floor is a MULTIPLE of the handheld one rather than the
   same number."
  {:phone  {:body 16.0 :code 11.0 :caption 10.0}
   :tablet {:body 16.0 :code 12.0 :caption 11.0}
   :laptop {:body 18.0 :code 13.0 :caption 12.0}
   :room   {:body 28.0 :code 20.0 :caption 16.0}})

(def token-roles
  "Which legibility floor governs which generated custom property.

   The CSS side of the same fact: `--plato-code-max` is a length, not type, so
   it carries no floor and is scaled with the box instead."
  {:body    {:var "--plato-body-size" :base 40.0 :doc "Reveal's --r-main-font-size"}
   :code    {:var "--plato-code-size" :base 20.0 :doc ".plato-code, 0.5em of body"}
   :caption {:var "--plato-caption-size" :base 16.8 :doc ".plato-caption, 0.42em"}})

(def max-type-scale
  "How far the type scale may be raised before the remedy is worse than the
   defect.

   Past this the box holds so little that a slide authored on a laptop loses
   its shape entirely, and the honest answer is that the deck cannot serve that
   screen without being rewritten -- which is what `:unsatisfiable` says."
  1.6)

;; ── facts ───────────────────────────────────────────────────────────────────

(defn facts
  "The fact base a derivation starts from.

   `box` is the slide box the deck is laid out in, `demand` the tallest slide's
   estimated height in that box (`plato.estimate`), and `targets` the viewport
   classes to derive for."
  ([box demand] (facts box demand viewports))
  ([box demand targets]
   (into #{[:box :w (double (:w box))]
           [:box :h (double (:h box))]
           [:demand :worst (double demand)]}
         cat
         [(for [{:keys [id w h]} targets
                [k v] [[:w w] [:h h]]]
            [:viewport id k (double v)])
          (for [[role {:keys [base]}] token-roles]
            [:token role :px (double base)])
          (for [[vp floors] legibility-floors
                [role px] floors]
            [:floor vp role (double px)])])))

;; ── rules ───────────────────────────────────────────────────────────────────

(defn- rule [id doc when-patterns then & [guard]]
  (cond-> {:rule/id id :rule/doc doc :rule/when (vec when-patterns) :rule/then then}
    guard (assoc :rule/guard guard)))

(def render-scale-rules
  "How much smaller than declared a token paints, per target.

   Reveal fits the whole box on screen, so the scale is the tighter of the two
   axes: a box wider than the screen is shrunk to the width, a taller one to
   the height."
  [(rule :render-scale
         "The factor Reveal's transform applies to the box on this screen."
         '[[:viewport ?vp :w ?vw] [:viewport ?vp :h ?vh] [:box :w ?bw] [:box :h ?bh]]
         (fn [{:syms [?vp ?vw ?vh ?bw ?bh]}]
           [:render-scale ?vp (min (/ ?vw ?bw) (/ ?vh ?bh))]))

   (rule :painted-size
         "What a token actually measures on this screen."
         '[[:render-scale ?vp ?s] [:token ?role :px ?px]]
         (fn [{:syms [?vp ?role ?s ?px]}]
           [:painted ?vp ?role (* ?px ?s)]))

   (rule :illegible
         "A token painting under its floor, and the factor that would fix it."
         '[[:painted ?vp ?role ?px] [:floor ?vp ?role ?floor]]
         (fn [{:syms [?vp ?role ?px ?floor]}]
           [[:illegible ?vp ?role (- ?floor ?px)]
            [:needs-scale ?vp ?role (/ ?floor ?px)]])
         (fn [{:syms [?px ?floor]}] (< ?px ?floor)))

   (rule :legible
         "A token that already clears its floor needs no remedy, and saying so
          is what keeps a target with nothing wrong out of the output."
         '[[:painted ?vp ?role ?px] [:floor ?vp ?role ?floor]]
         (fn [{:syms [?vp ?role]}] [:legible ?vp ?role])
         (fn [{:syms [?px ?floor]}] (>= ?px ?floor)))

   (rule :headroom
         "How much taller the worst slide may get before it leaves the box.

          The ceiling on any remedy: raising the type scale raises the demand
          with it, roughly in proportion, because a slide's height is mostly
          line boxes."
         '[[:demand :worst ?d] [:box :h ?bh]]
         (fn [{:syms [?d ?bh]}] [:headroom :type-scale (/ ?bh (max 1.0 ?d))]))])

(def remedy-rules
  "What to do about a target whose text is too small.

   Runs after the aggregation that picks the largest factor any token needs:
   one type scale serves a whole target, because scaling tokens by different
   amounts is not a type scale, it is a redesign."
  [(rule :within-headroom
         "The remedy fits: raise the type scale and the worst slide still fits."
         '[[:wanted-scale ?vp ?want] [:headroom :type-scale ?have]]
         (fn [{:syms [?vp ?want]}] [:type-scale ?vp ?want])
         (fn [{:syms [?want ?have]}] (and (<= ?want ?have) (<= ?want max-type-scale))))

   (rule :capped-by-fit
         "The remedy would overflow the worst slide, so it is taken as far as
          the slide allows and the shortfall is reported rather than hidden."
         '[[:wanted-scale ?vp ?want] [:headroom :type-scale ?have]]
         (fn [{:syms [?vp ?want ?have]}]
           [[:type-scale ?vp (max 1.0 (min ?have max-type-scale))]
            [:unsatisfiable ?vp :legibility ?want]])
         (fn [{:syms [?want ?have]}] (or (> ?want ?have) (> ?want max-type-scale))))

   (rule :single-column
         "A target whose render scale drops below a third cannot carry a
          three-track grid: each track paints under 100px wide, which is a
          column of hyphens.

          REPORTED, not emitted. public/css/plato.css already collapses those
          grids under 760px and does it with !important; deriving a second,
          weaker rule for the same decision would be two definitions of when a
          grid collapses, and the cascade would decide which one is true."
         '[[:render-scale ?vp ?s]]
         (fn [{:syms [?vp]}] [:layout ?vp :columns 1])
         (fn [{:syms [?s]}] (< ?s 0.34)))

   (rule :token-value
         "The value a token takes on this target.

          A scale of exactly 1 derives nothing: the target keeps the base
          value, and emitting a media query that restates it would be a rule
          the cascade has to resolve for no reason."
         '[[:type-scale ?vp ?f] [:token ?role :px ?px]]
         (fn [{:syms [?vp ?role ?f ?px]}]
           [:token-value ?vp ?role (* ?px ?f)])
         (fn [{:syms [?f]}] (not= 1.0 (double ?f))))])

(defn wanted-scale
  "The aggregation between the two strata: the largest factor any token on a
   target demands.

   Not a rule, and it cannot be one. `the largest` is a fact about a SET of
   facts, and stating it in a monotone rule would take negation -- `and no
   larger one exists` -- which is what makes a fixpoint depend on the order it
   ran in. Here it is a pure function of a finished fixpoint instead.

   Returns {fact premises}: the aggregate carries the `:needs-scale` facts it
   maximised over, so a trace of the type scale walks THROUGH the aggregation
   to the floor that forced it rather than stopping at it."
  [needs]
  (into {}
        (map (fn [[vp facts]]
               [[:wanted-scale vp (reduce max 1.0 (map #(nth % 3) facts))]
                (vec facts)]))
        (group-by second needs)))

(def strata
  "The derivation, in order. Each stratum sees everything before it."
  [{:rules render-scale-rules
    :assert (fn [fx]
              (wanted-scale (filterv #(= :needs-scale (first %)) (:facts fx))))}
   {:rules remedy-rules}])

;; ── projection to CSS ───────────────────────────────────────────────────────

(defn- px
  "A derived length as CSS text, to two decimals, with a bare integer kept
   bare -- `48px` rather than `48.0px`, because the generated file is meant to
   be read."
  [v]
  (let [hundredths (Math/round (* 100.0 (double v)))
        whole (quot hundredths 100)
        rest (rem hundredths 100)]
    (str whole
         (when-not (zero? rest)
           (str "." (if (zero? (rem rest 10)) (quot rest 10) rest)))
         "px")))

(defn- of-kind [facts kind]
  (filter #(= kind (first %)) facts))

(defn declarations
  "The custom properties one target sets."
  [facts vp]
  (into {}
        (keep (fn [[_ v role value]]
                (when (= v vp)
                  [(:var (token-roles role)) (px value)])))
        (of-kind facts :token-value)))

(defn- reason
  "Why this target has a block at all, as a CSS comment body."
  [facts vp]
  (let [illegible (filter (fn [[_ v]] (= v vp)) (of-kind facts :illegible))
        scale (first (filter (fn [[_ v]] (= v vp)) (of-kind facts :type-scale)))
        unsat (filter (fn [[_ v]] (= v vp)) (of-kind facts :unsatisfiable))]
    (str/join
     " "
     (cond-> [(str "type scale " (/ (Math/round (* 1000.0 (double (nth scale 2)))) 1000.0))]
       (seq illegible)
       (conj (str "-- " (str/join ", "
                                  (map (fn [[_ _ role by]]
                                         (str (name role) " was " (px by) " under its floor"))
                                       illegible))))
       (seq unsat)
       (conj (str "-- CAPPED: legibility wanted "
                  (/ (Math/round (* 1000.0 (double (nth (first unsat) 3)))) 1000.0)
                  ", the tallest slide allows less"))))))

(defn rules
  "A finished derivation -> `plato.css` rules.

   One `@media` block per target that needs one, narrowest first so the cascade
   resolves in declaration order. A target whose text already clears its floors
   contributes nothing, which is what keeps the generated file short enough to
   read.

   Only CUSTOM PROPERTIES are emitted. A derived layout decision is reported
   instead (see `:single-column`): plato.css already owns when a grid
   collapses, and a generated rule for the same decision would be a second
   definition of it."
  ([fixpoint] (rules fixpoint viewports))
  ([fixpoint targets]
   (let [facts (:facts fixpoint)]
     (into []
           (keep (fn [{:keys [id query]}]
                   (let [decls (declarations facts id)]
                     (when (seq decls)
                       [(str "@media " query) [":root" decls]]))))
           targets))))

(defn report
  "A finished derivation -> what it decided, as lines. Printed by the generator
   so the derivation is visible without reading the CSS."
  [fixpoint]
  (let [facts (:facts fixpoint)]
    (concat
     (for [[_ vp f] (sort-by second (of-kind facts :type-scale))]
       (str "  " (name vp) ": type scale "
            (/ (Math/round (* 1000.0 (double f))) 1000.0)))
     (for [[_ vp _ want] (of-kind facts :unsatisfiable)]
       (str "  " (name vp) ": UNSATISFIABLE -- legibility wants "
            (/ (Math/round (* 1000.0 (double want))) 1000.0)
            "x, the tallest slide does not allow it; cut content on that slide"))
     (for [[_ vp _ n] (of-kind facts :layout)]
       (str "  " (name vp) ": grids collapse to " n " column")))))

(defn stylesheet
  "A finished derivation -> the body of a generated stylesheet.

   The derivation's reasons go in the header rather than beside each block:
   plato.css has no comment node, and a value whose justification lives in a
   commit message is a value nobody can interrogate."
  [fixpoint]
  (str "/* plato responsive tokens -- GENERATED, do not edit.\n"
       "   Regenerate: clojure -M:derive\n\n"
       "   Every value below answers two constraints at once: a token must\n"
       "   PAINT at or above its legibility floor on the target screen, and the\n"
       "   tallest slide must still FIT the slide box once the type scale is\n"
       "   raised to get it there. Derived by plato.responsive over\n"
       "   hive-cljs.derive, which is what makes each line answerable.\n\n"
       "   The FIT half stands at the ESTIMATED rung: the tallest slide is\n"
       "   measured by plato.estimate's box model, not by a browser. A target\n"
       "   reported unsatisfiable below is unsatisfiable according to that\n"
       "   model; the browser rung may disagree by the model's own margin.\n\n"
       (str/join "\n" (map #(str "  " %) (report fixpoint)))
       "\n*/\n\n"
       (css/rules->css (rules fixpoint)) "\n"))

