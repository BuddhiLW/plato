(ns plato.doc
  "Front-end agnostic document IR and its compilation to a deck.

   document {:title    string-or-nil
             :meta     {keyword -> value}   ; front matter / file keywords
             :config   {...}                ; Reveal config overrides
             :sections [section]}

   section  {:id       keyword              ; slug of :title, made unique
             :title    string-or-nil
             :level    int
             :opts     {...}                ; slide options: :notes :transition ...
             :blocks   [content-value]      ; plato.content values
             :children [section]}           ; vertical sub-slides

   Markdown, Org and every other front end emit this shape; nothing here knows
   about any of them."
  (:require [clojure.string :as str]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.text :as text]))

(def default-section
  {:id :section :title nil :level 1 :opts {} :blocks [] :children []})

(defn slug
  "Slug keyword for `s`: lower-cased, every run of characters that is neither a
   letter nor a digit replaced by \"-\", leading/trailing \"-\" trimmed.
   Combining marks are dropped, so accented letters slug to their base letter;
   CJK, Greek and Cyrillic letters are kept verbatim. Blank input yields
   :section; input that slugs to nothing yields a stable :s-<hash> rather than
   sharing :section with every other one."
  [s]
  (let [source (str s)]
    (if (str/blank? source)
      :section
      (let [body (-> (->> (text/decompose (str/lower-case source))
                          (keep #(cond (text/mark? %) nil
                                       (text/letter-or-digit? %) %
                                       :else \-))
                          (apply str))
                     (str/replace #"-+" "-")
                     (str/replace #"^-|-$" ""))]
        (if (str/blank? body)
          (keyword (str "s-" (text/stable-hash source)))
          (keyword body))))))

(defn section
  "Section with `title` and the defaults filled in. `m` overrides any key."
  ([title] (section title {}))
  ([title m] (merge default-section {:id (slug title) :title title} m)))

(defn document
  "Document with `sections` and the defaults filled in. `m` overrides any key."
  ([sections] (document sections {}))
  ([sections m]
   (merge {:title nil :meta {} :config {} :sections (vec sections)} m)))

(defn stack-id
  "Id of the stack wrapping a section's vertical slides. Distinct from the
   section's own id, which stays on its first vertical slide."
  [id]
  (keyword (str (name id) "-stack")))

(defn- ids-of
  "Every id a section will occupy: its own, plus its stack id when it has
   children."
  [id stacked?]
  (if stacked? [id (stack-id id)] [id]))

(defn- unique-id [taken id stacked?]
  (let [free? (fn [candidate]
                (not-any? #(contains? taken %) (ids-of candidate stacked?)))]
    (if (free? id)
      id
      (loop [n 2]
        (let [candidate (keyword (str (name id) "-" n))]
          (if (free? candidate) candidate (recur (inc n))))))))

(defn- uniquify-in [taken sections]
  (reduce (fn [[acc taken] sec]
            (let [stacked? (boolean (seq (:children sec)))
                  id (unique-id taken (:id sec) stacked?)
                  [children taken] (uniquify-in (into taken (ids-of id stacked?))
                                                (:children sec))]
              [(conj acc (assoc sec :id id :children children)) taken]))
          [[] taken]
          sections))

(defn uniquify
  "Section tree with every :id made unique, in document order: a repeated id
   gains a -2, -3 ... suffix. A section with children also reserves its
   `stack-id`, so uniquify remains the single definition of the id space."
  [sections]
  (first (uniquify-in #{} (vec sections))))

(defn heading-tag
  "Hiccup tag for a section heading: :h1 at level 1, :h2 deeper."
  [level]
  (if (<= (or level 1) 1) :h1 :h2))

(defn section->slide
  "Section -> plato.deck/slide whose content is a content/group of the heading
   (omitted when :title is nil) followed by :blocks. :children are ignored."
  [{:keys [id title level opts blocks]}]
  (deck/slide id
              (content/group (into (if title [[(heading-tag level) title]] [])
                                   blocks))
              (or opts {})))

(defn section->entry
  "Section -> a deck slide, or a deck stack when it has :children. The stack's
   first vertical slide is the section itself."
  [{:keys [id children] :as sec}]
  (if (seq children)
    (deck/stack (stack-id id)
                (into [(section->slide sec)] (map section->entry) children))
    (section->slide sec)))

(defn document->deck
  "Document IR -> a validated plato.deck/deck. `opts` is merged into the deck
   spec; its :config merges over the document's."
  ([doc] (document->deck doc {}))
  ([doc opts]
   (deck/deck (-> {:title (:title doc) :meta (:meta doc)}
                  (merge (dissoc opts :config))
                  (assoc :config (merge (:config doc) (:config opts))
                         :slides (mapv section->entry
                                       (uniquify (:sections doc))))))))
