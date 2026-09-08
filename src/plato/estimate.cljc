(ns plato.estimate
  "A static box model: what a slide would take, without laying it out.

   plato.fit measures a laid-out DOM and needs a browser. This namespace
   answers the same question from the deck value alone, so a build on the JVM,
   on cljw or on cljrs can WARN about a slide that will overflow before anyone
   opens a page. It emits `hive-cljs.fit`'s measurement vocabulary -- the
   `:fit/*` keys -- so the same judge decides both rungs and there is no second
   definition of what fitting means.

   What it cannot do is settle the question. A box model does not run a layout
   engine: it does not know where a line breaks, what an image's intrinsic size
   is, or what the Markdown plugin will produce at runtime. Every measurement
   therefore carries `:fit/margin`, the model's own uncertainty, and a subject
   the model cannot see at all is reported with `:fit/modelled? false` rather
   than omitted. An omitted subject is indistinguishable from one that fits.

   The arms are a multimethod because plato's content model is an open set: a
   format that adds a `plato.content/render` method must be able to add a box
   arm without editing this namespace. The COVERAGE gate is what notices when
   it has not -- and it reads its universe off `plato.content/render`'s own
   dispatch table, never off the arms below."
  (:require [clojure.string :as str]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.metrics :as metrics]))

;; ── hiccup, taken apart ─────────────────────────────────────────────────────

(defn parse-head
  "A hiccup head keyword -> [tag #{class ...}].

   `:figure.plato-figure` is the tag with one class; `:div#id.a.b` drops the
   id, which contributes nothing to a box."
  [head]
  (let [s (name head)
        parts (str/split s #"\.")
        tag (keyword (first (str/split (first parts) #"#")))]
    [tag (set (rest parts))]))

(defn- attr-classes [attrs]
  (let [c (:class attrs)]
    (cond
      (string? c) (set (remove str/blank? (str/split c #"\s+")))
      (coll? c) (into #{} (comp (filter string?)
                                (mapcat #(str/split % #"\s+"))
                                (remove str/blank?))
                      c)
      :else #{})))

(defn element
  "A hiccup vector -> {:tag :classes :attrs :children}, or nil when it is not
   one (a component call, a fragment of text)."
  [node]
  (when (and (vector? node) (keyword? (first node)))
    (let [[tag classes] (parse-head (first node))
          attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (rest node))]
      {:tag tag
       :classes (into classes (attr-classes attrs))
       :attrs attrs
       :children (remove nil? children)})))

;; ── boxes ───────────────────────────────────────────────────────────────────

(def empty-box {:h 0.0 :w 0.0 :clipped [] :modelled? true})

(defn- box [h w & {:keys [clipped modelled?] :or {clipped [] modelled? true}}]
  {:h (double h) :w (double w) :clipped (vec clipped) :modelled? modelled?})

(defn stack
  "Child boxes laid out one under another.

   Heights add and widths take the widest. Each arm reports its own bottom
   margin inside its height, which is how adjacent block margins collapse in
   CSS: the gap between two siblings is one margin, not two."
  ([boxes] (stack boxes 0.0))
  ([boxes gap]
   (let [boxes (remove nil? boxes)]
     (if (empty? boxes)
       empty-box
       {:h (+ (reduce + 0.0 (map :h boxes)) (* gap (dec (count boxes))))
        :w (reduce max 0.0 (map :w boxes))
        :clipped (into [] (mapcat :clipped) boxes)
        :modelled? (every? :modelled? boxes)}))))

(defn beside
  "Child boxes laid out side by side: the tallest decides the height."
  [boxes]
  (let [boxes (remove nil? boxes)]
    (if (empty? boxes)
      empty-box
      {:h (reduce max 0.0 (map :h boxes))
       :w (reduce + 0.0 (map :w boxes))
       :clipped (into [] (mapcat :clipped) boxes)
       :modelled? (every? :modelled? boxes)})))

(defn clip
  "A clipping finding in `hive-cljs.fit`'s vocabulary."
  [tag class over-w over-h]
  #:fit{:tag (name tag) :class (or class "")
        :over-w (double (max 0.0 over-w)) :over-h (double (max 0.0 over-h))})

;; ── the layout context ──────────────────────────────────────────────────────

(defn context
  "The context a child is laid out in: how wide it may be, and the type in
   force around it."
  [m]
  {:avail (- (:w (:box m)) (* 2.0 (:section-padding (:reveal m) 0.0)))
   :font-size (:font-size (:reveal m))
   :line-height (:line-height (:reveal m))
   :role :sans})

(defn- scaled
  "`ctx` re-based on one of plato's own components."
  [m ctx component & {:keys [role avail]}]
  (let [{:keys [size line-height]} (get-in m [:plato component])
        fs (* (:font-size ctx) (double (or size 1.0)))]
    (assoc ctx
           :font-size fs
           :line-height (double (or line-height (:line-height ctx)))
           :role (or role (:role ctx))
           :avail (or avail (:avail ctx)))))

(defn- em
  "`n` of the component's own em, in pixels."
  [ctx n]
  (* (double (or n 0.0)) (:font-size ctx)))

;; ── text ────────────────────────────────────────────────────────────────────

(defn text-of
  "Every string inside `node`, joined. What a text box is measured from."
  [node]
  (cond
    (string? node) node
    (number? node) (str node)
    (map? node) ""
    (coll? node) (str/join " " (remove str/blank? (map text-of node)))
    :else ""))

(defn- text-box
  [m ctx text]
  (let [h (metrics/text-height m (:role ctx) text (:avail ctx)
                               (:font-size ctx) (:line-height ctx))]
    (box h (min (:avail ctx)
                (metrics/text-width m (:role ctx) text (:font-size ctx))))))

;; ── the open set of arms ────────────────────────────────────────────────────

(def class->kind
  "The plato component a class names. Read against plato.css, whose selectors
   are the only place these classes mean anything."
  {"plato-figure" :figure
   "plato-code" :code
   "plato-table" :table
   "plato-quote" :quote
   "plato-cite" :cite
   "plato-note" :note
   "plato-caption" :caption
   "plato-kicker" :kicker
   "plato-list" :list
   "plato-grid" :cards
   "plato-card" :card
   "plato-columns" :columns
   "plato-column" :block
   "plato-group" :block
   "plato-embed" :embed
   "plato-scene" :scene
   "plato-transport" :transport
   "plato-audio" :audio
   "plato-video" :media
   "plato-html" :opaque
   "plato-unknown" :unknown-box})

(def tag->kind
  "What an ordinary HTML tag lays out as. Anything absent is a block, which is
   the correct default for a container and harmless for an inline."
  {:h1 :heading :h2 :heading :h3 :heading :h4 :heading :h5 :heading :h6 :heading
   :p :paragraph
   :ul :list :ol :list :dl :list
   :li :list-item :dt :list-item :dd :list-item
   :img :media :video :media :svg :media :picture :media
   :audio :audio
   :iframe :embed
   :pre :code
   :table :table
   :figure :figure
   :figcaption :caption
   :blockquote :quote
   :br :line-break
   :hr :rule
   :script :none :source :none :track :none :style :none})

(defn node-kind
  "The arm that lays this element out. A plato class wins over the tag: the
   stylesheet's rules for `.plato-code` outrank the browser's for `<pre>`."
  [{:keys [tag classes attrs]}]
  (or (some class->kind classes)
      (when (contains? attrs :data-markdown) :opaque)
      (when (contains? attrs :dangerouslySetInnerHTML) :opaque)
      (get tag->kind tag)
      :block))

(declare node-box)

(defmulti element-box
  "Metrics + context + a parsed element -> its box.

   Open by construction. A content kind plato learns to render needs an arm
   here, and the coverage gate is what says so."
  (fn [_m _ctx element] (node-kind element)))

(defn children-box
  ([m ctx element] (children-box m ctx element 0.0))
  ([m ctx element gap]
   (stack (map #(node-box m ctx %) (:children element)) gap)))

(defn node-box
  "Metrics + context + any hiccup node -> its box."
  [m ctx node]
  (cond
    (nil? node) empty-box
    (string? node) (text-box m ctx node)
    (number? node) (text-box m ctx (str node))
    (keyword? node) empty-box

    ;; A component call is a vector whose head is a function. There is no
    ;; honest way to lay one out without invoking it, and invoking arbitrary
    ;; author code inside a build gate is not something this model does.
    (and (vector? node) (fn? (first node)))
    (assoc empty-box :modelled? false)

    (fn? node) (assoc empty-box :modelled? false)

    (and (vector? node) (= :<> (first node)))
    (stack (map #(node-box m ctx %) (rest node)))

    (vector? node)
    (if-let [el (element node)]
      (element-box m ctx el)
      (stack (map #(node-box m ctx %) node)))

    (seq? node) (stack (map #(node-box m ctx %) node))

    ;; A tagged content map that survived expansion: render it and carry on.
    (and (map? node) (contains? node :plato/type))
    (node-box m ctx (content/expand (content/render node)))

    (map? node) empty-box
    :else (text-box m ctx (str node))))

;; ── arms ────────────────────────────────────────────────────────────────────

(defmethod element-box :none [_ _ _] empty-box)

(defmethod element-box :block [m ctx el] (children-box m ctx el))

(defmethod element-box :line-break [_ ctx _]
  (box (* (:font-size ctx) (:line-height ctx)) 0.0))

(defmethod element-box :rule [_ _ _] (box 1.0 0.0))

(defmethod element-box :opaque
  ;; Raw HTML and a Reveal markdown template are strings this model cannot
  ;; lay out: what they become is decided by a browser at runtime.
  [_ _ _]
  (assoc empty-box :modelled? false))

(defn- override
  "The rule a CONTAINER imposes on this child tag, or nil.

   plato.css restyles the elements inside some of its components -- a card's
   `<h3>` is 0.7em, not Reveal's 1.55em -- and a box model that reads only the
   Reveal scale over-estimates every one of them. The container arm puts the
   rule in the context; the element arm reads it here."
  [ctx tag]
  (get-in ctx [:overrides tag]))

(defmethod element-box :heading [m ctx el]
  (let [{:keys [reveal]} m
        rule (override ctx (:tag el))
        size (or (:size rule) (get-in reveal [:size (:tag el)] 1.0))
        fs (* (:font-size ctx) size)
        lh (or (:line-height rule) (:heading-line-height reveal))
        margin (if rule (em {:font-size fs} (:margin-em rule))
                   (:heading-margin-bottom reveal))
        text (text-of (:children el))
        h (metrics/text-height m :heading text (:avail ctx) fs lh)]
    (box (+ h margin)
         (min (:avail ctx) (metrics/text-width m :heading text fs)))))

(defmethod element-box :paragraph [m ctx el]
  (if-let [rule (override ctx :p)]
    (let [fs (* (:font-size ctx) (:size rule 1.0))
          c (assoc ctx :font-size fs :line-height (:line-height rule 1.3))]
      (update (children-box m c el) :h + (em c (:margin-em rule))))
    (update (children-box m ctx el) :h + (:block-margin (:reveal m)))))

(defmethod element-box :list [m ctx el]
  (let [indent (* 1.1 (:rem m))
        inner (assoc ctx :avail (- (:avail ctx) indent))]
    (-> (stack (map #(node-box m inner %) (:children el)))
        (update :h + (em ctx 0.6))
        (update :w + indent))))

(defmethod element-box :list-item [m ctx el]
  (let [c (scaled m ctx :list-item)
        b (children-box m c el)]
    (update b :h + (* 2.0 (em c (:margin-em (get-in m [:plato :list-item])))))))

(defmethod element-box :caption [m ctx el]
  (let [c (scaled m ctx :caption)]
    (text-box m c (text-of (:children el)))))

(defmethod element-box :kicker [m ctx el]
  (let [c (scaled m ctx :kicker :role :heading)]
    (text-box m c (text-of (:children el)))))

(defmethod element-box :cite [m ctx el]
  (let [c (scaled m ctx :cite)]
    (update (text-box m c (text-of (:children el)))
            :h + (em c (:margin-em (get-in m [:plato :cite]))))))

(defmethod element-box :quote [m ctx el]
  (let [c (scaled m ctx :quote)
        pad (em c (:pad-em (get-in m [:plato :quote])))
        inner (assoc c :avail (- (:avail c) (* 2.75 pad)))]
    (-> (children-box m inner el)
        (update :h + (* 2.0 pad) (em c (:margin-em (get-in m [:plato :quote]))))
        (assoc :w (:avail ctx)))))

(defmethod element-box :note [m ctx el]
  (let [c (scaled m ctx :note)
        pad (em c (:pad-em (get-in m [:plato :note])))
        inner (assoc c :avail (- (:avail c) (* 2.6 pad)))]
    (-> (children-box m inner el)
        (update :h + (* 2.0 pad) (em c (:margin-em (get-in m [:plato :note]))))
        (assoc :w (:avail ctx)))))

(defmethod element-box :figure [m ctx el]
  (-> (children-box m ctx el (:figure-gap (:layout m)))
      (update :h + (em ctx 0.6))))

(defn- declared-px
  "A declared width or height attribute, in pixels, or nil."
  [attrs k]
  (metrics/px (get attrs k) {:rem (double metrics/root-font-size)}))

(defmethod element-box :media
  ;; Without a layout engine there is no intrinsic size, so an image budgets
  ;; what the stylesheet caps it at. Declared dimensions are believed, capped
  ;; the same way: `.plato-figure img { max-height: var(--plato-media-max) }`.
  [m ctx el]
  (let [cap (get-in m [:limits :media-max])
        h (min cap (or (declared-px (:attrs el) :height) cap))]
    (box h (min (:avail ctx) (or (declared-px (:attrs el) :width) (:avail ctx))))))

(defmethod element-box :audio [_ ctx _]
  ;; A native audio control is a fixed-height widget, not text.
  (box 54.0 (min (:avail ctx) 512.0)))

(defmethod element-box :embed [m ctx el]
  (let [cap (get-in m [:limits :media-max])
        w (:avail ctx)]
    (box (min cap (/ w (/ 16.0 9.0))) w)))

(defmethod element-box :scene [m ctx el]
  (let [c (scaled m ctx :transport)]
    (box (+ (get-in m [:limits :scene-max])
            (:scene-gap (:layout m))
            (* (:font-size c) (:line-height c) 1.6))
         (:avail ctx))))

(defmethod element-box :transport [m ctx _]
  (let [c (scaled m ctx :transport)]
    (box (* (:font-size c) (:line-height c) 1.6) (:avail ctx))))

(defmethod element-box :code
  ;; `.plato-code code` has `max-height: var(--plato-code-max)` and
  ;; `overflow: auto`, so a listing taller than the cap is CUT OFF rather than
  ;; making the slide taller. That is a clip finding, and the only overflow in
  ;; plato that a slide-level scale cannot repair.
  [m ctx el]
  (let [c (scaled m ctx :code :role :mono)
        pad (em c (:pad-em (get-in m [:plato :code])))
        inner-avail (- (:avail c) (* 2.2 pad))
        source (text-of (:children el))
        lines (str/split-lines (if (str/blank? source) " " source))
        rows (reduce + 0 (map #(metrics/line-count m :mono % inner-avail
                                                   (:font-size c))
                              lines))
        natural (+ (* rows (:font-size c) (:line-height c)) (* 2.0 pad))
        cap (get-in m [:limits :code-max])]
    (box (min natural cap) (:avail ctx)
         :clipped (when (> natural (+ cap 2.0))
                    [(clip :code "plato-code" 0.0 (- natural cap))]))))

(defn- cell-rows
  "Every `<tr>` in a table, as vectors of cell text."
  [el]
  (letfn [(rows [node]
            (if-let [e (element node)]
              (if (= :tr (:tag e))
                [(mapv #(text-of (:children (element %))) (:children e))]
                (into [] (mapcat rows) (:children e)))
              []))]
    (into [] (mapcat rows) (:children el))))

(defmethod element-box :table
  ;; `.plato-table th, td { white-space: nowrap }` and the table itself is
  ;; `width: max-content; max-width: 100%; overflow-x: auto`. So a table wider
  ;; than the slide does not widen it -- it scrolls, and a presentation is not
  ;; scrollable by its audience.
  [m ctx el]
  (let [c (scaled m ctx :table)
        pad (em c (:pad-em (get-in m [:plato :table])))
        rows (cell-rows el)
        widths (for [i (range (reduce max 0 (map count rows)))]
                 (+ (* 2.0 pad)
                    (reduce max 0.0
                            (map #(metrics/text-width m :sans (nth % i "")
                                                      (:font-size c))
                                 rows))))
        natural-w (reduce + 0.0 widths)
        row-h (+ (* (:font-size c) (:line-height c)) (* 2.0 pad) 1.0)
        caption (some #(when (= :caption (:tag (element %))) %) (:children el))
        caption-h (if caption (:h (node-box m c caption)) 0.0)]
    (box (+ (* (count rows) row-h) caption-h (em ctx 0.6))
         (min (:avail ctx) natural-w)
         :clipped (when (> natural-w (+ (:avail ctx) 2.0))
                    [(clip :table "plato-table" (- natural-w (:avail ctx)) 0.0)]))))

(defmethod element-box :columns
  ;; `grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr))` -- the track
  ;; floor decides how many columns fit, and every column is laid out at the
  ;; resulting track width.
  [m ctx el]
  (let [{:keys [columns-min columns-gap]} (:layout m)
        n (max 1 (count (:children el)))
        fits (max 1 (long (quot (+ (:avail ctx) columns-gap)
                                (+ columns-min columns-gap))))
        cols (min n fits)
        rows (long (Math/ceil (/ (double n) (double cols))))
        track (/ (- (:avail ctx) (* columns-gap (dec cols))) (double cols))
        inner (assoc ctx :avail track)
        boxes (map #(node-box m inner %) (:children el))]
    (box (+ (* rows (reduce max 0.0 (map :h boxes)))
            (* columns-gap (dec rows)))
         (:avail ctx)
         :clipped (into [] (mapcat :clipped) boxes)
         :modelled? (every? :modelled? boxes))))

(defmethod element-box :cards
  ;; `.plato-grid { grid-template-columns: repeat(3, 1fr) }`, collapsing to one
  ;; column under 760px -- which the slide box never is.
  [m ctx el]
  (let [gap (get-in m [:limits :gap])
        cols 3
        n (max 1 (count (:children el)))
        rows (long (Math/ceil (/ (double n) (double cols))))
        track (/ (- (:avail ctx) (* gap (dec cols))) (double cols))
        boxes (map #(node-box m (assoc ctx :avail track) %) (:children el))]
    (box (+ (* rows (reduce max 0.0 (map :h boxes))) (* gap (dec rows)))
         (:avail ctx)
         :clipped (into [] (mapcat :clipped) boxes)
         :modelled? (every? :modelled? boxes))))

(def card-overrides
  "What `.plato-card > h3` and `.plato-card > p` are restyled to.

   Read against plato.css: inside a card a heading is 0.7em with a 0.35em
   bottom margin and body copy is 0.52em with none, which is nothing like the
   Reveal scale those tags carry anywhere else."
  {:h3 {:size 0.7 :line-height 1.2 :margin-em 0.35}
   :p {:size 0.52 :line-height 1.45 :margin-em 0.0}})

(defmethod element-box :card [m ctx el]
  (let [pad (:card-pad (:layout m))
        inner (assoc ctx
                     :avail (- (:avail ctx) (* 2.0 pad))
                     :overrides card-overrides)
        b (children-box m inner el)]
    (box (max (:card-min-h (:layout m)) (+ (:h b) (* 2.0 pad)))
         (:avail ctx)
         :clipped (:clipped b)
         :modelled? (:modelled? b))))

(defmethod element-box :unknown-box [m ctx _]
  ;; plato renders an unrenderable content value as a visible marker rather
  ;; than dropping it. It has a box, and the deck has a problem.
  (let [c (scaled m ctx :unknown)]
    (box (+ (* (:font-size c) (:line-height c))
            (* 2.0 (em c (:pad-em (get-in m [:plato :unknown])))))
         (:avail ctx))))

(defmethod element-box :default [m ctx el] (children-box m ctx el))

;; ── the content model's own universe ────────────────────────────────────────

(def structural-kinds
  "Dispatch values of `plato.content/render` that are SHAPES of a content
   value, not kinds of content.

   Excluded from the coverage universe because they are how any value is
   carried, not something an author declares. Stated as an exclusion rather
   than a whitelist so a NEW content kind joins the universe by existing."
  #{:nil :string :value :component :hiccup :seq :map :default})

(defn model-kinds
  "Every content kind plato's model admits, read off `plato.content/render`'s
   own dispatch table.

   The universe a coverage gate needs, and the reason it is drawn from here:
   deriving it from this namespace's arms would make the check
   `arms is a subset of arms`, which holds however many kinds have no arm."
  []
  (into #{} (remove structural-kinds) (keys (methods content/render))))

(defn content-kinds
  "Every content kind a value draws on, tagged maps nested in hiccup included."
  [node]
  (cond
    (and (map? node) (contains? node :plato/type))
    (into #{(:plato/type node)} (mapcat content-kinds) (vals node))

    (map? node) (into #{} (mapcat content-kinds) (vals node))
    (vector? node) (into #{} (mapcat content-kinds) node)
    (seq? node) (into #{} (mapcat content-kinds) node)
    :else #{}))

;; ── measuring a deck ────────────────────────────────────────────────────────

(defn measure-slide
  "One leaf slide -> a `hive-cljs.schema/FitMeasurement` at the estimated rung."
  [m slide]
  (let [ctx (context m)
        b (node-box m ctx (content/expand (content/render (:content slide))))]
    #:fit{:id (name (:id slide))
          :rung :estimated
          :box {:w (double (:w (:box m))) :h (double (:h (:box m)))}
          :extent {:w (double (max 0.0 (:w b))) :h (double (max 0.0 (:h b)))}
          :policy (:overflow slide)
          :clipped (:clipped b)
          :margin (metrics/uncertainty (:h b))
          :kinds (content-kinds (:content slide))
          :modelled? (:modelled? b)}))

(defn measure-deck
  "Every leaf slide of `deck` measured. `tokens` may be nil.

   Pure: the same deck value yields the same measurements on every host, which
   is what lets a native build and a JVM build gate on one answer."
  ([deck] (measure-deck deck nil))
  ([deck tokens] (measure-deck deck tokens {}))
  ([deck tokens opts]
   (let [m (metrics/metrics tokens opts)]
     (mapv #(measure-slide m %) (deck/leaf-slides deck)))))
