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

   A slide may overflow deliberately: `{:overflow :allow}` in the slide options
   is projected to `data-plato-overflow` on its <section>, so the checker reads
   the intent off the DOM and works the same against the live shell and the
   static export."
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
   :overflows     overflows without saying so — the failure
   :waived        overflows, and the slide declared it
   :stale-waiver  declared an overflow it no longer has, so the waiver now
                  only hides the next real one"
  [{:keys [waived?] :as measurement}]
  (let [fs (findings measurement)]
    {:id (:id measurement)
     :state (cond
              (and (seq fs) waived?) :waived
              (seq fs) :overflows
              waived? :stale-waiver
              :else :ok)
     :findings fs}))

;; ── reporting ───────────────────────────────────────────────────────────────

(defn- describe [{:keys [kind by tag class over-w over-h]}]
  (case kind
    :taller-than-box (str "is " by "px taller than the slide box")
    :wider-than-box (str "is " by "px wider than the slide box")
    :clipped (str "clips <" tag (when (seq class) (str " class=\"" class "\"")) ">"
                  (when (> over-w tolerance) (str ", " over-w "px of it horizontally"))
                  (when (> over-h tolerance) (str ", " over-h "px of it vertically")))))

(defn explain
  "Verdicts -> a human-readable report of the ones that are not fine, or nil
   when every slide is. The message a build failure prints, so it names the
   slide, what overflowed, and by how much."
  [verdicts]
  (let [bad (remove (comp #{:ok :waived} :state) verdicts)]
    (when (seq bad)
      (str/join
       "\n"
       (for [{:keys [id state findings]} bad]
         (if (= :stale-waiver state)
           (str "  " id " — declares {:overflow :allow} but now fits; drop the waiver")
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
      :slide {:w (.-scrollWidth sec) :h (.-scrollHeight sec)}
      :waived? (some? (.getAttribute sec (name (:attr deck/overflow-waiver))))
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
     (let [verdicts (report)]
       (when-let [message (explain (remove (comp #{:stale-waiver} :state) verdicts))]
         (throw (ex-info (str (count (remove (comp #{:ok :waived} :state) verdicts))
                              " of " (count verdicts)
                              " slides do not fit their slide box:\n" message)
                         {:verdicts verdicts})))
       {:slides (count verdicts)
        :waived (count (filter (comp #{:waived} :state) verdicts))})))

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
