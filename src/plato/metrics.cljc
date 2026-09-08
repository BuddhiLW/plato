(ns plato.metrics
  "The typographic model a slide is laid out under, as data.

   plato.fit answers whether a slide fits by MEASURING a laid-out DOM, which
   needs a browser. This namespace is the other half: the constants a pure box
   model needs to ESTIMATE the same answer from the deck value alone, on every
   host plato runs on.

   Two sources feed it, and neither is restated here:

     - the Reveal theme's type scale, fixed for a 960x700 deck and read off the
       theme file the tokens name;
     - the deck's own token map, which is already what public/css/plato.css
       reads for every max-height and gap it declares, so a theme that changes
       --plato-code-max changes what the estimator budgets for a code block.

   One family of constants is this namespace's own: the average glyph advance
   of a font. It cannot be derived from a stylesheet, only from the font file
   or a laid-out page, so it is the model's error term. `calibrate` corrects it
   from real measurements, and `rung` says which of the two a metrics value
   currently stands at.

   THREE font sizes are in play and confusing them is the classic error here:
   `rem` resolves against the HTML root (16px, untouched by Reveal), `em`
   against the font size in force at the element, and Reveal's own scale
   against `--r-main-font-size`. plato.css uses all three."
  (:require [clojure.string :as str]))

;; ── the box ─────────────────────────────────────────────────────────────────

(def slide-box
  "The box Reveal lays every slide out in, in CSS pixels.

   Reveal's `width`/`height` configuration, which plato.deck/default-config
   does not override. The box is CSS-transform-scaled to the window, so it is
   the same box on a phone and on a projector."
  {:w 960.0 :h 700.0})

(def root-font-size
  "The HTML root font size every `rem` in plato.css resolves against.

   Not Reveal's `--r-main-font-size`: Reveal sets its scale on `.reveal`, and
   `rem` never looks there. A `1rem` gap is 16px on a slide whose body text is
   40px."
  16.0)

;; ── the Reveal type scale ───────────────────────────────────────────────────

(def reveal
  "The Reveal theme's type scale.

   `:font-size`, `:block-margin` and the `:margin-*` entries are CSS pixels;
   every entry under `:size` is a multiple of the font size in force at that
   element.

   These are the values public/vendor/theme/night.css declares, which is the
   theme plato.tokens names under :meta/:reveal-theme. A deck on another Reveal
   theme has another scale: hand one to `metrics` rather than editing this."
  {:font-size 40.0
   :line-height 1.3
   :block-margin 20.0
   :heading-line-height 1.2
   :heading-margin-bottom 20.0
   :size {:h1 3.77 :h2 2.11 :h3 1.55 :h4 1.0 :h5 1.0 :h6 1.0 :small 0.6}
   :section-padding 0.0})

;; ── plato's own scale ───────────────────────────────────────────────────────

(def plato-scale
  "What public/css/plato.css sets on plato's own components.

   `:size` is a multiple of the font size in force at that element; `:pad-em`
   and `:margin-em` are multiples of the component's OWN font size, which is
   how CSS reads an em inside a padding declaration.

   The stylesheet is the authority. A value here that disagrees with it makes
   the estimator wrong in the direction nobody notices, which is why
   plato.metrics-test holds the two together."
  {:code      {:size 0.5  :line-height 1.45 :pad-em 0.9}
   :caption   {:size 0.42 :line-height 1.4}
   :cite      {:size 0.5  :line-height 1.3  :margin-em 0.6}
   :kicker    {:size 0.45 :line-height 1.3}
   :table     {:size 0.55 :line-height 1.3  :pad-em 0.45}
   :note      {:size 0.6  :line-height 1.45 :pad-em 0.85 :margin-em 0.6}
   :quote     {:size 1.0  :line-height 1.3  :pad-em 0.8  :margin-em 0.6}
   :list-item {:size 1.0  :line-height 1.35 :margin-em 0.35}
   :card-body {:size 0.52 :line-height 1.45}
   :card-head {:size 0.7  :line-height 1.2  :margin-em 0.35}
   :transport {:size 0.45 :line-height 1.3}
   :unknown   {:size 0.45 :line-height 1.3  :pad-em 0.6}})

(def layout
  "Lengths plato.css states in rem, resolved at `root-font-size`.

   `:columns-min` is the `minmax(16rem, 1fr)` track floor that decides how many
   columns `auto-fit` produces; `:card-min-h` is `.plato-card`'s `min-height`;
   `:card-pad` its padding; `:columns-gap` the gap between column tracks."
  {:columns-min (* 16.0 root-font-size)
   :columns-gap (* 1.25 root-font-size)
   :card-min-h  (* 8.0 root-font-size)
   :card-pad    (* 1.0 root-font-size)
   :figure-gap  (* 0.5 root-font-size)
   :scene-gap   (* 0.55 root-font-size)})

;; ── glyph advance ───────────────────────────────────────────────────────────

(def advance
  "Average glyph advance as a fraction of the font size, per type role.

   The one thing in this model that no stylesheet states. A proportional face's
   average advance depends on the text as much as on the font, so these are
   corpus figures for Latin prose in the faces plato self-hosts: Open Sans for
   body, Montserrat for headings, a 0.6em-advance face for code.

   Wrong here means wrong line counts, which is exactly why an estimate warns
   and a browser measurement gates. `calibrate` replaces these from real
   numbers."
  {:sans 0.50 :heading 0.60 :mono 0.60})

(def corpus-rung
  "The evidence layer the corpus advance ratios stand at: derived from font
   metrics and letter frequency, never measured against a laid-out page."
  :corpus)

(def relative-error
  "Fraction of an estimated extent to treat as the model's own uncertainty.

   A box model that cannot see the layout engine gets line breaks, margin
   collapsing and intrinsic media sizes approximately right, and the error
   compounds down a slide. hive-cljs.fit refuses to FAIL a build on a finding
   inside this band, so it is what separates a warning from a gate."
  0.12)

(def floor-error
  "Pixels of uncertainty an estimate carries however short the slide.

   One line of body text at the Reveal scale is 52px, so a slide whose whole
   content is misjudged by a line is still inside the band."
  60.0)

;; ── CSS lengths ─────────────────────────────────────────────────────────────

(def ^:private length-pattern #"^\s*(-?[0-9]*\.?[0-9]+)\s*(px|rem|em|%)?\s*$")

(defn- parse-number [s]
  #?(:clj (try (Double/parseDouble s) (catch Exception _ nil))
     :cljs (let [n (js/parseFloat s)] (when-not (js/isNaN n) n))
     :default (try (Double/parseDouble s) (catch Exception _ nil))))

(defn px
  "CSS length -> pixels, or nil when this model cannot resolve it.

   `context` supplies `:rem` (the HTML root size, default `root-font-size`) and
   `:em` (the font size in force where the length is written). A bare number is
   already pixels.

   A percentage, a viewport unit or a calc() comes back nil rather than as a
   number: a caller then has to decide what an unknown means, instead of
   silently budgeting zero for it."
  ([value] (px value nil))
  ([value {:keys [rem em] :or {rem root-font-size}}]
   (cond
     (number? value) (double value)
     (not (string? value)) nil
     :else
     (when-let [[_ n unit] (re-matches length-pattern value)]
       (when-let [n (parse-number n)]
         (case unit
           "px" n
           "rem" (* n rem)
           "em" (* n (or em rem))
           (when (nil? unit) n)))))))

;; ── the metrics value ───────────────────────────────────────────────────────

(def default-limits
  "The caps to budget with when a token map states none.

   Absent a `--plato-media-max` there is no cap in the stylesheet either, so an
   image is bounded only by the slide box. Budgeting the box is the honest
   reading of an unconstrained image."
  {:media-max (:h slide-box)
   :code-max  (:h slide-box)
   :scene-max (:h slide-box)
   :gap       (* 1.0 root-font-size)})

(defn- token-limits
  "Token map -> the max-heights plato.css caps its components at, in pixels.

   Read from the tokens rather than restated, because the stylesheet reads them
   from there too: `.plato-figure img { max-height: var(--plato-media-max) }`
   and its siblings are projections of these keys."
  [tokens]
  (let [scale (:scale tokens)]
    (into {} (keep (fn [k] (when-let [v (px (get scale k))] [k v])))
          [:media-max :code-max :scene-max :gap])))

(defn metrics
  "Token map -> the metrics a box model needs.

   Options:
     :reveal   another Reveal type scale
     :advance  corrected advance ratios (see `calibrate`)
     :box      a slide box other than Reveal's default

   The result is a plain value; nothing downstream reads a token file or a
   stylesheet again."
  ([] (metrics nil {}))
  ([tokens] (metrics tokens {}))
  ([tokens opts]
   (let [scale (merge reveal (:reveal opts))]
     {:box (merge slide-box (:box opts))
      :rem root-font-size
      :reveal scale
      :plato plato-scale
      :layout layout
      :advance (merge advance (:advance opts))
      :limits (merge default-limits (token-limits tokens))
      :rung (if (:advance opts) :measured corpus-rung)})))

;; ── text ────────────────────────────────────────────────────────────────────

(defn- ceil [n] #?(:clj (Math/ceil (double n))
                   :cljs (js/Math.ceil n)
                   :default (Math/ceil (double n))))

(defn advance-of
  "The advance ratio `m` uses for `role`."
  [m role]
  (get-in m [:advance role] (:sans advance)))

(defn text-width
  "The width one unbroken line of `text` takes at `font-size`, in pixels."
  [m role text font-size]
  (* (count (str text)) (advance-of m role) (double font-size)))

(defn line-count
  "How many lines `text` wraps to inside `avail` pixels at `font-size`.

   Never fewer than one: an empty string still occupies the line box its parent
   reserved for it. Words are not modelled, so a line broken early by a long
   word is under-counted; that error is what the metrics' margin covers."
  [m role text avail font-size]
  (let [w (text-width m role text font-size)]
    (if (or (nil? avail) (<= avail 0))
      1
      (max 1 (long (ceil (/ w (double avail))))))))

(defn text-height
  "The height `text` takes inside `avail` pixels, in pixels."
  [m role text avail font-size line-height]
  (* (line-count m role text avail font-size)
     (double font-size)
     (double line-height)))

(defn uncertainty
  "The pixels of error an estimated extent of `h` carries."
  [h]
  (max floor-error (* relative-error (double h))))

;; ── calibration ─────────────────────────────────────────────────────────────

(defn calibrate
  "Observations -> corrected advance ratios.

   An observation is `{:role :sans :chars n :width px :font-size px}`, one per
   run of text a browser laid out on a single line. The corrected ratio for a
   role is total measured width over the total advance those characters would
   have taken at their font size, which is the least-squares answer for one
   scale factor and needs no iteration.

   A role with no observation keeps its corpus ratio: a partial calibration
   corrects what it saw and claims nothing about the rest."
  [observations]
  (reduce (fn [acc [role obs]]
            (let [width (reduce + 0.0 (map #(double (:width %)) obs))
                  units (reduce + 0.0 (map #(* (:chars %) (double (:font-size %))) obs))]
              (if (pos? units) (assoc acc role (/ width units)) acc)))
          advance
          (group-by :role
                    (remove #(or (nil? (:width %))
                                 (nil? (:font-size %))
                                 (not (pos? (or (:chars %) 0))))
                            observations))))

(defn- two-places [v]
  (let [n #?(:clj (Math/round (* 100.0 (double v)))
             :cljs (js/Math.round (* 100.0 v))
             :default (Math/round (* 100.0 (double v))))]
    (str "0." (if (< n 10) (str "0" n) n))))

(defn describe
  "Metrics -> one line stating what its numbers are worth.

   Printed above an estimate so nobody reads it as a measurement."
  [m]
  (str "static box model at the " (name (:rung m)) " rung; advance per em: "
       (str/join ", " (map (fn [[k v]] (str (name k) " " (two-places v)))
                           (sort-by key (:advance m))))))
