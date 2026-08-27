(ns plato.deck)

(def reveal-data-keys
  "Slide options passed through to Reveal as data- attributes, in the order
   they are emitted.

   A vector, not a set: attribute order is then a property of plato rather than
   of the host's set iteration, so the JVM and the native binary render the same
   slide to the same bytes."
  [:auto-animate
   :auto-animate-duration
   :auto-animate-easing
   :auto-animate-id
   :auto-animate-restart
   :auto-animate-unmatched
   :background-color
   :background-gradient
   :background-iframe
   :background-image
   :background-interactive
   :background-opacity
   :background-position
   :background-repeat
   :background-size
   :background-video
   :background-video-loop
   :background-video-muted
   :preload
   :state
   :timing
   :transition
   :transition-speed
   :visibility])

(def overflow-waiver
  "The slide option that declares an overflow deliberate, and the attribute it
   projects to.

   One definition with two readers: `deck` validates a slide's :overflow against
   :values, and plato.fit reads :attr back off the rendered DOM. A value that
   fails validation can never reach the DOM to be misread there as 'not waived',
   and the checker needs no reference to the deck that built the page."
  {:option :overflow
   :attr :data-plato-overflow
   :values #{:allow}})

(defn attr-value
  "Slide-option value -> DOM attribute value. `true` becomes the empty string."
  [value]
  (cond
    (true? value) ""
    (keyword? value) (name value)
    :else value))

(defn section-attrs
  "Attribute map for a slide's <section>: its id, any declared overflow waiver,
   and every Reveal data key set on the slide."
  [slide]
  (reduce (fn [attrs key]
            (if (contains? slide key)
              (assoc attrs
                     (keyword (str "data-" (name key)))
                     (attr-value (get slide key)))
              attrs))
          (cond-> {}
            (:id slide) (assoc :id (name (:id slide)))
            (:class slide) (assoc :class (:class slide))
            (:overflow slide) (assoc (:attr overflow-waiver)
                                     (name (:overflow slide))))
          reveal-data-keys))

(def default-config
  {:hash true
   :history true
   :controls true
   :progress true
   :center true
   :transition :slide
   :background-transition :fade
   :navigation-mode :default})

(defn slide
  ([id content] (slide id content {}))
  ([id content opts]
   (merge {:plato/type :slide
           :id id
           :content content}
          opts)))

(defn stack
  ([id slides] (stack id slides {}))
  ([id slides opts]
   (merge {:plato/type :stack
           :id id
           :slides (vec slides)}
          opts)))

(defn- leaves [slides]
  (mapcat (fn [entry]
            (if (= :stack (:plato/type entry))
              (leaves (:slides entry))
              [entry]))
          slides))

(defn leaf-slides [deck]
  (vec (leaves (:slides deck))))

(defn- duplicate-ids [ids]
  (->> ids
       frequencies
       (keep (fn [[id n]] (when (> n 1) id)))
       vec))

(defn- valid-entry? [entry]
  (case (:plato/type entry)
    :slide (some? (:id entry))
    :stack (and (some? (:id entry))
                (vector? (:slides entry))
                (every? valid-entry? (:slides entry)))
    false))

(defn- invalid-overflow
  "Slides whose :overflow is not a value the waiver defines, as [id value]."
  [entries]
  (vec (mapcat (fn [entry]
                 (if (= :stack (:plato/type entry))
                   (invalid-overflow (:slides entry))
                   (let [declared (:overflow entry)]
                     (when (and declared
                                (not (contains? (:values overflow-waiver) declared)))
                       [[(:id entry) declared]]))))
               entries)))

(defn entry-ids
  "Every id a deck puts in the DOM, stacks included, in document order."
  [entries]
  (vec (mapcat (fn [entry]
                 (if (= :stack (:plato/type entry))
                   (cons (:id entry) (entry-ids (:slides entry)))
                   [(:id entry)]))
               entries)))

(defn deck [{:keys [slides config] :as spec}]
  (let [slides (vec slides)
        model (assoc spec
                     :plato/type :deck
                     :slides slides
                     :config (merge default-config config))
        duplicates (duplicate-ids (entry-ids slides))
        bad-overflow (invalid-overflow slides)]
    (when-not (seq slides)
      (throw (ex-info "Deck requires at least one slide" {:spec spec})))
    (when-not (every? valid-entry? slides)
      (throw (ex-info "Invalid slide entry" {:slides slides})))
    (when (seq duplicates)
      (throw (ex-info "Duplicate slide ids" {:ids duplicates})))
    (when (seq bad-overflow)
      (throw (ex-info (str ":overflow accepts " (pr-str (:values overflow-waiver))
                           " — a slide cannot waive a fit check with a value the"
                           " checker will not recognise")
                      {:slides bad-overflow})))
    model))
