(ns plato.fit
  "Does a slide fit the box it is laid out in?

   Reveal renders a deck into a FIXED slide box — 960x700 unless the deck
   configures otherwise — and CSS-transform-scales that box to the window. A
   slide that overflows the box overflows it on every screen at every zoom, so
   fit is a property of the build, not of the viewer. That is what makes it
   gateable: one answer per deck, decided once.

   Judging is pure and loads on every dialect. Measuring reads a laid-out DOM
   and therefore exists only under ClojureScript — there is no honest way to
   answer this without a layout engine, so plato asks the one that will render
   the deck instead of modelling a second one.

   A slide may answer for its overflow: `{:overflow :allow}` declares it
   deliberate, `{:overflow :shrink}` asks plato to scale the slide down until it
   fits. Either is projected to `data-plato-overflow` on the <section>, so the
   checker reads the author's intent off the DOM and needs no reference to the
   deck that built the page."
  (:require [clojure.string :as str]
            [plato.deck :as deck]))

;; ── the contract ────────────────────────────────────────────────────────────

(def tolerance
  "Pixels of overflow to ignore.

   scrollWidth and scrollHeight are integers rounded UP from fractional layout,
   so an element whose content lands on a half pixel reports a pixel of
   overflow it does not have. Measured across plato's two decks, real defects
   start two orders of magnitude above this; every 1-2px reading was rounding."
  2)

(def min-scale
  "The smallest scale a slide may be shrunk to before shrinking stops being a
   remedy.

   A slide is laid out in a 960x700 box whose body text is 28px, so 0.6 renders
   it at under 17px once the box is scaled to a projector. Past that the slide
   fits and nobody at the back of the room can read it, which is a worse outcome
   than the build failing."
  0.6)

(defn fit-scale
  "Measurement -> the uniform scale that brings the slide inside its box, or 1
   when it already fits.

   Exact rather than searched: the scale is applied through CSS `scale`, which
   never reflows, so a slide's painted size is its measured size times this
   factor and one division answers it."
  [{:keys [box slide]}]
  (min 1.0
       (double (/ (:h box) (max 1 (:h slide))))
       (double (/ (:w box) (max 1 (:w slide))))))

(def scale-property
  "The CSS custom property a shrunk slide's scale is written to.

   Declared in every dialect although only ClojureScript writes it, so that a
   JVM test can hold the stylesheet to it: public/css/plato.css reads it back as
   `scale: var(--plato-fit-scale, 1)`, and the two agreeing is the whole reason
   a measured scale reaches the page.

   The independent `scale` property rather than `transform`: Reveal writes
   `transform` on a section for slide transitions, and the two would clobber
   each other. `scale` composes with it, and scales text, media and nested
   scenes alike."
  "--plato-fit-scale")

(defn- over
  "A dimension finding, or nil when the excess is within tolerance."
  [kind actual limit]
  (let [by (- actual limit)]
    (when (> by tolerance)
      {:kind kind :by by})))

(defn- clip-findings
  "Descendants whose content is cut off by their own box. A presentation is not
   scrollable by its audience, so a clipped element is lost content."
  [clipped]
  (into []
        (comp (filter (fn [{:keys [over-w over-h]}]
                        (or (> over-w tolerance) (> over-h tolerance))))
              (map (fn [c] (assoc c :kind :clipped))))
        clipped))

(defn findings
  "Measurement -> every way the slide fails to fit, as data. Empty when it fits."
  [{:keys [box slide clipped]}]
  (into (into [] (keep identity)
              [(over :taller-than-box (:h slide) (:h box))
               (over :wider-than-box (:w slide) (:w box))])
        (clip-findings clipped)))

(defn verdict
  "Measurement -> what to do about it.

   :ok            fits, and claimed nothing
   :overflows     overflows in a way nothing declared, or that shrinking cannot
                  repair — the failure
   :waived        overflows, and the slide declared {:overflow :allow}
   :shrunk        overflows, declared {:overflow :shrink}, and fits at :scale
   :too-small     declared {:overflow :shrink}, but fitting would take it under
                  min-scale — shrinking is no longer a remedy
   :stale-waiver  declared a policy for an overflow it no longer has, so the
                  declaration now only hides the next real one"
  [{:keys [policy] :as measurement}]
  (let [fs (findings measurement)
        clipped? (boolean (some (comp #{:clipped} :kind) fs))
        scale (fit-scale measurement)]
    (merge {:id (:id measurement) :findings fs}
           (cond
             (empty? fs) {:state (if policy :stale-waiver :ok)}
             (= :allow policy) {:state :waived}
             (not= :shrink policy) {:state :overflows}
             ;; Scaling a section scales its clipped descendants with it, so the
             ;; ratio that cuts the content off survives at every scale.
             clipped? {:state :overflows}
             (< scale min-scale) {:state :too-small :scale scale}
             :else {:state :shrunk :scale scale}))))

;; ── reporting ───────────────────────────────────────────────────────────────

(defn- describe [{:keys [kind by tag class over-w over-h]}]
  (case kind
    :taller-than-box (str "is " by "px taller than the slide box")
    :wider-than-box (str "is " by "px wider than the slide box")
    :clipped (str "clips <" tag (when (seq class) (str " class=\"" class "\"")) ">"
                  (when (> over-w tolerance) (str ", " over-w "px of it horizontally"))
                  (when (> over-h tolerance) (str ", " over-h "px of it vertically")))))

(defn- percent
  "Scale -> a whole-percent string. Author-facing: nobody acts on a third
   decimal place of a font scale."
  [scale]
  (str (int (Math/round (* 100.0 (double scale)))) "%"))

(defn explain
  "Verdicts -> a human-readable report of the ones that are not fine, or nil
   when every slide is. The message a build failure prints, so it names the
   slide, what overflowed, and by how much."
  [verdicts]
  (let [bad (remove (comp #{:ok :waived :shrunk} :state) verdicts)]
    (when (seq bad)
      (str/join
       "\n"
       (for [{:keys [id state findings scale]} bad]
         (case state
           :stale-waiver
           (str "  " id " — declares an :overflow policy but now fits; drop it")
           :too-small
           (str "  " id " — would have to shrink to " (percent scale)
                " to fit, under the readable floor of " (percent min-scale)
                "; cut content rather than shrink it")
           (str "  " id " — " (str/join "; " (map describe findings)))))))))

;; ── measuring ───────────────────────────────────────────────────────────────

#?(:cljs
   (defn- leaf-sections
     "The <section>s that are slides. A vertical stack is a <section> too, so
      the ones holding another section are containers, not slides."
     [^js slides]
     (->> (.querySelectorAll slides "section")
          (array-seq)
          (remove (fn [^js s] (.querySelector s "section"))))))

#?(:cljs
   (def ^:private hides-overflow
     "Computed `overflow` values that actually cut content off.

      `visible` is the one that does not: content spills past the border box
      and stays on screen, which is how nearly every inline element is laid
      out. Counting those as clipped reported 35 findings on one slide of
      MathJax internals — <mi>, <mfrac>, and the deliberately-offscreen
      assistive MathML — none of which loses a pixel a viewer would see."
     #{"hidden" "auto" "scroll" "clip"}))

#?(:cljs
   (def ^:private min-visible-px
     "An element thinner than this in either axis is not showing anything to
      anyone, so nothing can be clipped out of it.

      This is the visually-hidden idiom — a 1x1 box with `overflow: hidden`
      holding a copy of the content for screen readers. MathJax emits one per
      formula (.MJX_Assistive_MathML), and by construction its content always
      exceeds its box."
     4))

#?(:cljs
   (defn- clipped-in
     "Descendants of `sec` that cut their own content off: the scroll size
      exceeds the client size AND the overflow on that axis is not visible. An
      element with no width is not rendered, so it is not clipped either."
     [^js sec]
     (->> (.querySelectorAll sec "*")
          (array-seq)
          (keep (fn [^js el]
                  (let [style (js/getComputedStyle el)
                        cut? (fn [axis] (contains? hides-overflow axis))
                        over-w (if (cut? (.-overflowX style))
                                 (- (.-scrollWidth el) (.-clientWidth el))
                                 0)
                        over-h (if (cut? (.-overflowY style))
                                 (- (.-scrollHeight el) (.-clientHeight el))
                                 0)]
                    (when (and (> (.-clientWidth el) min-visible-px)
                               (> (.-clientHeight el) min-visible-px)
                               (or (> over-w tolerance) (> over-h tolerance)))
                      {:tag (str/lower-case (.-tagName el))
                       :class (str/trim (str (.-className el)))
                       :over-w over-w
                       :over-h over-h}))))
          (vec))))

#?(:cljs
   (def ^:private show-every-slide
     ;; Reveal display:none's every slide but the present one, so an unvisited
     ;; slide has no geometry at all. Top-level sections are absolutely
     ;; positioned inside .slides, so revealing them together lays each one out
     ;; at full box width without them stacking or affecting one another —
     ;; which is what lets the whole deck be measured in one synchronous pass,
     ;; with no navigation and no transition to wait out.
     ".reveal .slides section { display: block !important; }"))

#?(:cljs
   (defn- with-every-slide-laid-out [f]
     (let [style (.createElement js/document "style")]
       (set! (.-textContent style) show-every-slide)
       (.appendChild (.-head js/document) style)
       (try (f)
            (finally (.remove style))))))

#?(:cljs
   (defn- measure-section [box ^js sec]
     {:id (or (not-empty (.-id sec)) "(no id)")
      :box box
      ;; scrollWidth/scrollHeight are LAYOUT sizes, and CSS `scale` paints an
      ;; element smaller without reflowing it. A slide plato has already shrunk
      ;; therefore still measures at its natural size, so the verdict is the
      ;; same one before and after the remedy is applied and `report` can be
      ;; called at any point in the page's life.
      :slide {:w (.-scrollWidth sec) :h (.-scrollHeight sec)}
      :policy (some-> (.getAttribute sec (name (:attr deck/overflow-policy)))
                      not-empty
                      keyword)
      :clipped (clipped-in sec)}))

#?(:cljs
   (defn measure-deck
     "Measure every slide on the page, in slide-box pixels."
     []
     (if-let [^js slides (.querySelector js/document ".reveal .slides")]
       (let [box {:w (.-offsetWidth slides) :h (.-offsetHeight slides)}]
         (with-every-slide-laid-out
           #(mapv (partial measure-section box) (leaf-sections slides))))
       (throw (ex-info "No .reveal .slides on this page — nothing to measure" {})))))

#?(:cljs
   (defn report
     "Verdict for every slide on the page."
     []
     (mapv verdict (measure-deck))))

#?(:cljs
   (defn verdict-for
     "The verdict for one slide by id — what the checker says about it and why.
      The question an author asks when a slide is flagged, and the one the
      authoring overlay asks per slide."
     [id]
     (first (filter #(= id (:id %)) (report)))))

;; Two callers, one judgment. The node e2e harness wants a value it can put a
;; predicate on; hive-cljs `:eval-cljs` wants a throw. ^:export keeps the name
;; through :advanced so a probe can call it from outside the compiled bundle.

#?(:cljs
   (defn ^:export checkDeck
     "The fit report for this page as a string, or nil when every slide fits.
      Stale waivers are not failures here — checkWaivers reports those."
     []
     (explain (remove (comp #{:stale-waiver} :state) (report)))))

#?(:cljs
   (defn ^:export checkWaivers
     "Slides declaring an overflow they no longer have, as a string, or nil."
     []
     (explain (filter (comp #{:stale-waiver} :state) (report)))))

#?(:cljs
   (defn assert-deck-fits!
     "Throw unless every slide fits, or says why it does not. The e2e gate."
     []
     (let [verdicts (report)
           tally (fn [state] (count (filter (comp #{state} :state) verdicts)))]
       (when-let [message (explain (remove (comp #{:stale-waiver} :state) verdicts))]
         (throw (ex-info (str (count (remove (comp #{:ok :waived :shrunk} :state) verdicts))
                              " of " (count verdicts)
                              " slides do not fit their slide box:\n" message)
                         {:verdicts verdicts})))
       {:slides (count verdicts)
        :waived (tally :waived)
        :shrunk (tally :shrunk)})))

#?(:cljs
   (defn assert-no-stale-waivers!
     "Throw if a slide still declares an overflow it no longer has."
     []
     (let [stale (filter (comp #{:stale-waiver} :state) (report))]
       (when (seq stale)
         (throw (ex-info (str "waivers that outlived their reason:\n"
                              (explain stale))
                         {:stale (mapv :id stale)})))
       {:stale 0})))

;; ── shrinking ───────────────────────────────────────────────────────────────

#?(:cljs
   (defn ^:export fitDeck
     "Shrink every slide that declared {:overflow :shrink} until it fits, and
      return the scale applied to each by id.

      Idempotent: the scale is computed from layout sizes, which CSS `scale`
      does not change, so calling this twice lands on the same number."
     []
     (reduce (fn [applied {:keys [id state scale]}]
               (if-let [^js sec (and (= :shrunk state)
                                     (.querySelector js/document
                                                     (str "section#" (js/CSS.escape id))))]
                 (do (.setProperty (.-style sec) scale-property (str scale))
                     (assoc applied id scale))
                 applied))
             {}
             (report))))
