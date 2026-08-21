(ns plato.deck)

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

(defn- duplicate-ids [slides]
  (->> slides
       (map :id)
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

(defn deck [{:keys [slides config] :as spec}]
  (let [slides (vec slides)
        model (assoc spec
                     :plato/type :deck
                     :slides slides
                     :config (merge default-config config))
        duplicates (duplicate-ids (leaf-slides model))]
    (when-not (seq slides)
      (throw (ex-info "Deck requires at least one slide" {:spec spec})))
    (when-not (every? valid-entry? slides)
      (throw (ex-info "Invalid slide entry" {:slides slides})))
    (when (seq duplicates)
      (throw (ex-info "Duplicate slide ids" {:ids duplicates})))
    model))
