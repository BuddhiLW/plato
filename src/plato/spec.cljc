(ns plato.spec
  "plato.doc document -> AutoPDF DocumentSpec value. Sibling of
   plato.doc/document->deck.

   Every function here is a calculation. Ids are path-derived, so a document
   projects to the same value every time.

   The DocumentSpec contract — kinds, props, degradations — is specified in
   AutoPDF's docs/plato-integration.md."
  (:require [clojure.string :as str]
            [plato.content :as content]))

(def schema-version
  "Wire format understood by AutoPDF's pkg/document."
  1)

;; ── hiccup readers ──────────────────────────────────────────────────────────

(defn- attrs
  "Attribute map of hiccup vector `v`, or nil when it carries none."
  [v]
  (when (map? (second v)) (second v)))

(defn- body
  "Child forms of hiccup vector `v`, skipping the attribute map."
  [v]
  (if (map? (second v)) (drop 2 v) (next v)))

(def ^:private mark-of
  "Hiccup tag -> the semantic mark it contributes to the spans beneath it."
  {:strong "strong" :b "strong"
   :em "emph" :i "emph"
   :code "code" :kbd "code" :samp "code"
   :del "strike" :s "strike"
   :mark "highlight"})

(defn- heading-level
  "Numeric level of an :h1-:h6 tag, else nil."
  [tag]
  (when (keyword? tag)
    (when-let [[_ digit] (re-matches #"h([1-6])" (name tag))]
      #?(:clj (Long/parseLong digit) :cljs (js/parseInt digit 10)))))

(defn text-of
  "Plain text carried by any content value, with markup dropped. Used where a
   target has no place for inline structure — table cells, alt text, notes."
  [x]
  (cond
    (nil? x) ""
    (string? x) x
    (vector? x) (str/join (map text-of (body x)))
    (map? x) (or (:text x) (:content x) (:source x) "")
    (seq? x) (str/join (map text-of x))
    :else (str x)))

;; ── inline projection (Promote) ─────────────────────────────────────────────

(defn- span
  "One inline run. Dropped when it carries no text."
  [text marks]
  (when (seq text)
    {:kind "span" :variant "default"
     :props (cond-> {:text text} (seq marks) (assoc :marks (vec (sort marks))))}))

(defn inlines
  "Inline hiccup -> a flat vector of span and link components. `marks` is the
   set of marks inherited from enclosing tags."
  ([x] (inlines x #{}))
  ([x marks]
   (cond
     (nil? x) []
     (string? x) (if-let [s (span x marks)] [s] [])
     (seq? x) (vec (mapcat #(inlines % marks) x))

     (vector? x)
     (let [tag (first x)]
       (cond
         (= :br tag) [{:kind "span" :variant "default" :props {:text "\n"}}]

         (= :a tag)
         [{:kind "link" :variant "default"
           :props (cond-> {:text (text-of x)}
                    (:href (attrs x)) (assoc :href (:href (attrs x)))
                    (seq marks) (assoc :marks (vec (sort marks))))}]

         :else
         (vec (mapcat #(inlines % (cond-> marks (mark-of tag) (conj (mark-of tag))))
                      (body x)))))

     ;; A tagged content map nested in inline position: render it as its text.
     (map? x) (inlines (text-of x) marks)
     :else (inlines (str x) marks))))

;; ── block projection (Promote) ──────────────────────────────────────────────

(defn- node
  "Component with `kind`, omitting the empty keys DocumentSpec treats as absent."
  [kind {:keys [props style children]}]
  (cond-> {:kind kind :variant "default"}
    (seq props) (assoc :props props)
    (seq style) (assoc :style style)
    (seq children) (assoc :children (vec children))))

(defn- media
  "video, audio or embed -> a media-placeholder component carrying the poster,
   caption and source."
  [media-type {:keys [src sources poster caption alt]}]
  (node "media-placeholder"
        {:props (cond-> {:media-type media-type}
                  (or src (:src (first sources))) (assoc :src (or src (:src (first sources))))
                  poster (assoc :poster poster)
                  (seq (text-of caption)) (assoc :caption (text-of caption))
                  (seq (text-of alt)) (assoc :alt (text-of alt)))}))

(declare block)

(defn- blocks
  "Project a sequence of content values, flattening groups and dropping the
   ones that carry nothing."
  [xs]
  (vec (mapcat (fn [x]
                 (if (and (map? x) (= :group (:plato/type x)))
                   (blocks (:items x))
                   (when-let [b (block x)] [b])))
               xs)))

(defn block
  "One content value -> one component, or nil when it carries nothing a
   document can hold."
  [x]
  (cond
    (nil? x) nil
    (string? x) (when (seq (str/trim x)) (node "text" {:children (inlines x)}))
    (seq? x) (node "text" {:children (vec (mapcat inlines x))})

    (vector? x)
    (let [tag (first x)
          level (heading-level tag)]
      (cond
        level (node "heading" {:props {:level level} :children (inlines (body x))})
        (= :hr tag) (node "rule" {})
        :else (when-let [runs (seq (inlines x))] (node "text" {:children runs}))))

    (map? x)
    (case (content/kind x)
      :image (node "image"
                   {:props (cond-> {:src (:src x)}
                             (seq (text-of (:alt x))) (assoc :alt (text-of (:alt x)))
                             (seq (text-of (:caption x))) (assoc :caption (text-of (:caption x)))
                             (:width x) (assoc :width (str (:width x))))})

      :video (media "video" x)
      :audio (media "audio" x)
      :embed (media "embed" x)

      :code (node "code"
                  {:props (cond-> {:source (:source x)}
                            (:lang x) (assoc :language (name (:lang x)))
                            (:highlight x) (assoc :highlight (str (:highlight x))))})

      :quote (node "quote"
                   {:props (cond-> {}
                             (seq (text-of (:cite x))) (assoc :cite (text-of (:cite x))))
                    :children (inlines (:text x))})

      :bullets (node "bullets"
                     {:props (cond-> {:items (mapv text-of (:items x))}
                               (:ordered? x) (assoc :ordered true)
                               (:fragments? x) (assoc :fragments true))})

      :table (node "table"
                   {:props (cond-> {:head (mapv text-of (:head x))
                                    :rows (mapv #(mapv text-of %) (:rows x))}
                             (seq (text-of (:caption x))) (assoc :caption (text-of (:caption x))))})

      :column (block (:content x))

      :columns (node "columns"
                     {:props (cond-> {} (seq (:widths x)) (assoc :widths (mapv str (:widths x))))
                      :children (blocks (:items x))})

      ;; Card items are {:title :body :icon} maps, not content values.
      :cards (node "cards"
                   {:props (cond-> {} (:columns x) (assoc :columns (:columns x)))
                    :children (mapv (fn [{:keys [title body icon]}]
                                      (node "card"
                                            {:props (cond-> {}
                                                      (seq (text-of title)) (assoc :title (text-of title))
                                                      (seq (text-of icon)) (assoc :icon (text-of icon)))
                                             :children (inlines body)}))
                                    (:items x))})

      :note (node "callout"
                  {:props (cond-> {:tone (name (or (:tone x) :info))}
                            (seq (text-of (:title x))) (assoc :title (text-of (:title x))))
                   :children (inlines (:content x))})

      :kicker (node "kicker" {:props {:text (text-of (:text x))}})

      ;; A fragment contributes :style {:overlay ...} to the component it
      ;; wraps; it is not a node of its own. :effect is dropped, :index is not.
      :fragment (some-> (block (:content x))
                        (assoc :style {:overlay (if-let [i (:index x)]
                                                  (str i "-")
                                                  "+-")}))

      :markdown (node "text" {:props {:format "markdown" :content (:text x)}})
      :html (node "text" {:props {:format "html" :content (:html x)}})

      :group (node "columns" {:children (blocks (:items x))})

      ;; Default: an unrecognised map projects to its text.
      (when-let [runs (seq (inlines (text-of x)))] (node "text" {:children runs})))

    :else (node "text" {:children (inlines (str x))})))

;; ── sections (Promote) ──────────────────────────────────────────────────────

(def ^:private slide-style-keys
  "Slide options that survive into a printed frame. Every other slide option is
   dropped."
  {:background-color :background-color
   :background-image :background-image
   :background-gradient :background-gradient})

(defn- slide-style [opts]
  (reduce-kv (fn [acc k target] (cond-> acc (opts k) (assoc target (str (opts k)))))
             {}
             slide-style-keys))

(defn section->component
  "One plato.doc section -> a `section` component. :children are ignored here;
   sections->components flattens them."
  [section]
  (let [{:keys [title level opts]} section]
    (node "section"
          {:props (cond-> {:level (or level 1)}
                    (seq (text-of title)) (assoc :title (text-of title)))
           :style (slide-style (or opts {}))
           :children (cond-> (blocks (:blocks section))
                       (:notes opts) (conj (node "notes" {:props {:text (text-of (:notes opts))}})))})))

(defn sections->components
  "Sections -> a flat vector of `section` components. A vertical stack becomes
   consecutive siblings, each child carrying :style {:stack-of <parent title>}."
  [sections]
  (vec (mapcat (fn [section]
                 (let [parent (section->component section)
                       parent-key (text-of (:title section))]
                   (into [parent]
                         (map #(assoc-in (section->component %) [:style :stack-of] parent-key)
                              (:children section)))))
               sections)))

;; ── identity (Promote) ──────────────────────────────────────────────────────

(defn- with-ids
  "Stamp each component with its path as :id. Ids are unique across the whole
   tree and stable for a given document, as DocumentSpec requires."
  [component path]
  (cond-> (assoc component :id path)
    (seq (:children component))
    (update :children (fn [cs] (vec (map-indexed #(with-ids %2 (str path "-" %1)) cs))))))

;; ── the projection ──────────────────────────────────────────────────────────

(defn document->spec
  "plato.doc document -> a DocumentSpec value, ready for plato.json/write with
   :key-fn plato.json/camel-key.

   opts: :id (document id), :theme (theme name carried to the renderer)."
  ([document] (document->spec document {}))
  ([document {:keys [id theme]}]
   (cond-> {:schema-version schema-version
            :blocks (vec (map-indexed #(with-ids %2 (str "b" %1))
                                      (sections->components (:sections document))))}
     (or id (:title document)) (assoc :id (str (or id (:title document))))
     theme (assoc :theme (str theme)))))
