(ns plato.deck)

(def reveal-data-keys
  "Slide options passed through to Reveal as data- attributes."
  #{:auto-animate
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
    :visibility})

(defn attr-value
  "Slide-option value -> DOM attribute value. `true` becomes the empty string."
  [value]
  (cond
    (true? value) ""
    (keyword? value) (name value)
    :else value))

(defn section-attrs
  "Attribute map for a slide's <section>: its id plus every Reveal data key set
   on the slide."
  [slide]
  (reduce (fn [attrs key]
            (if (contains? slide key)
              (assoc attrs
                     (keyword (str "data-" (name key)))
                     (attr-value (get slide key)))
              attrs))
          (cond-> {}
            (:id slide) (assoc :id (name (:id slide)))
            (:class slide) (assoc :class (:class slide)))
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
        duplicates (duplicate-ids (entry-ids slides))]
    (when-not (seq slides)
      (throw (ex-info "Deck requires at least one slide" {:spec spec})))
    (when-not (every? valid-entry? slides)
      (throw (ex-info "Invalid slide entry" {:slides slides})))
    (when (seq duplicates)
      (throw (ex-info "Duplicate slide ids" {:ids duplicates})))
    model))
