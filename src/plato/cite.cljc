(ns plato.cite
  "The citation pass `plato.deck/deck` runs.

   Every `plato.content/cite` is numbered in deck order of first citation, as
   BibTeX's unsrt style numbers them, and linked to the slide its entry is
   listed on. The reference list goes where a `plato.content/bibliography`
   value sits, or on slides appended to the deck when none does; each entry
   links back to the slides that cite it."
  (:require [clojure.string :as str]
            [plato.bib :as bib]
            [plato.content :as content]))

(def default-references
  "Reference-slide options a deck's :references overrides."
  {:title "References" :per-slide 6 :id :references})

(defn generated?
  "True for a slide the citation pass appended."
  [entry]
  (= :bibliography (:plato/generated entry)))

(defn strip-generated
  "Deck entries without the slides an earlier pass appended, so the pass can
   run again on its own output."
  [entries]
  (vec (remove generated? entries)))

(defn- leaves [entries]
  (mapcat (fn [e] (if (= :stack (:plato/type e)) (leaves (:slides e)) [e])) entries))

(defn- all-ids [entries]
  (mapcat (fn [e] (cons (:id e) (when (= :stack (:plato/type e)) (all-ids (:slides e)))))
          entries))

(defn- tagged? [node type]
  (and (map? node) (= type (:plato/type node))))

(defn- target? [node]
  (or (tagged? node :cite) (tagged? node :bibliography)))

(defn- found
  "Every :cite and :bibliography value inside a content value, in reading
   order. Component functions are opaque."
  [node]
  (cond
    (target? node) [node]
    (map? node) (mapcat found (vals node))
    (or (vector? node) (seq? node)) (mapcat found node)
    :else nil))

(defn- rewrite
  "`node` with every :cite and :bibliography value replaced by (f value)."
  [f node]
  (cond
    (target? node) (f node)
    (map? node) (into (empty node) (map (fn [[k v]] [k (rewrite f v)])) node)
    (vector? node) (mapv #(rewrite f %) node)
    (seq? node) (doall (map #(rewrite f %) node))
    :else node))

(defn- cite-key [k] (keyword (name k)))

(def ^:private key-pattern
  "A citation key as pandoc and org-cite write it: `@` at the start, after
   whitespace or after `;`, then the key."
  #"(?:^|[\s;])@([A-Za-z0-9_][\w:.#$%&+?<>~/-]*)")

(defn citation?
  "True when `body` (the text between a citation's brackets) names a key."
  [body]
  (some? (re-find key-pattern (str body))))

(defn from-text
  "Citation body as markdown and org write it, \"see @a; @b, p. 3\" -> a
   plato.content/cite value, or nil when it names no key. Text before the first
   key is the :prefix; text after the last, less a leading comma, the :note."
  [body]
  (let [s (str body)
        raw (map second (re-seq key-pattern s))]
    (when (seq raw)
      (let [keys (map #(str/replace % #"[.:]+$" "") raw)
            first-at (str/index-of s (str "@" (first raw)))
            last-raw (last raw)
            tail (subs s (+ (str/last-index-of s (str "@" last-raw)) 1 (count last-raw)))
            prefix (str/trim (str/replace (subs s 0 first-at) #";\s*$" ""))
            note (str/trim (str/replace tail #"^\s*[,;]" ""))]
        (content/cite (mapv keyword keys)
                      (cond-> {}
                        (seq prefix) (assoc :prefix prefix)
                        (seq note) (assoc :note note)))))))

(defn- unique-id [taken id]
  (if-not (contains? taken id)
    id
    (some #(let [c (keyword (str (name id) "-" %))] (when-not (contains? taken c) c))
          (iterate inc 2))))

(defn- page-ids
  "Ids for `n` appended reference slides, none of them in `taken`."
  [taken base n]
  (first (reduce (fn [[ids taken] i]
                   (let [id (unique-id taken (if (zero? i) base (keyword (str (name base) "-" (inc i)))))]
                     [(conj ids id) (conj taken id)]))
                 [[] taken]
                 (range n))))

(defn- backrefs
  "Links from a reference back to the slides citing it, labelled by slide
   number."
  [slide-ids ordinal]
  (when (seq slide-ids)
    (into [:span.plato-bib-backrefs " ↩ "]
          (interpose ", "
                     (map (fn [id] [:a {:href (str "#/" (name id))} (str (ordinal id))])
                          slide-ids)))))

(defn- unknown-keys! [cites bib order]
  (let [missing (vec (remove #(contains? bib %) order))]
    (when (seq missing)
      (throw (ex-info (str "Citation of unknown key" (when (next missing) "s") ": "
                           (str/join ", " (map name missing))
                           (when (empty? bib) " (the deck has no :bibliography)"))
                      {:missing missing
                       :slides (vec (distinct (for [[id v] cites
                                                    :when (some (set missing) (map cite-key (:keys v)))]
                                                id)))})))))

(defn- reference-slide [title page-count i id entries]
  {:plato/type :slide
   :plato/generated :bibliography
   :id id
   :class "plato-references"
   :content (content/group
             [[:h2 (if (> page-count 1) (str title " (" (inc i) "/" page-count ")") title)]
              (assoc (content/bibliography) :entries entries)])})

(defn resolve-slides
  "Deck entries -> the same entries with their citations resolved.

   opts: :bibliography (anything `plato.bib/entries` takes), :references
   (merged over `default-references`), :pending? (true when the deck names a
   bibliography file nobody has read yet: citations then stay unresolved
   rather than failing, so the reader can supply it).

   Throws when a citation names a key the bibliography lacks."
  [deck-entries {:keys [bibliography references pending?]}]
  (let [deck-entries (strip-generated deck-entries)
        slides (vec (leaves deck-entries))
        uses (for [s slides
                   v (concat (found (:content s)) (found (:notes s)))]
               [(:id s) v])
        cites (filter (fn [[_ v]] (tagged? v :cite)) uses)
        placeholder (some (fn [[id v]] (when (tagged? v :bibliography) id)) uses)]
    (if (or (and (empty? cites) (nil? placeholder))
            (and pending? (empty? bibliography)))
      deck-entries
      (let [bib (bib/entries (or bibliography {}))
            order (distinct (for [[_ v] cites k (:keys v)] (cite-key k)))
            _ (unknown-keys! cites bib order)
            number (zipmap order (map inc (range)))
            cited-on (reduce (fn [m [id v]]
                               (reduce #(update %1 (cite-key %2) (fnil conj []) id) m (:keys v)))
                             {} cites)
            ordinal (zipmap (map :id slides) (map inc (range)))
            {:keys [title per-slide id]} (merge default-references references)
            pages (if placeholder [order] (vec (partition-all (max 1 per-slide) order)))
            ids (if placeholder
                  [placeholder]
                  (page-ids (set (all-ids deck-entries)) id (count pages)))
            home (into {} (mapcat (fn [page id] (map #(vector % id) page)) pages ids))
            listing (fn [page]
                      (mapv (fn [k]
                              {:n (number k)
                               :key k
                               :body (bib/entry-hiccup (bib k))
                               :backrefs (backrefs (distinct (cited-on k)) ordinal)})
                            page))
            fill (fn [v]
                   (if (tagged? v :cite)
                     (assoc v :refs (mapv (fn [k]
                                            (let [k (cite-key k)]
                                              {:n (number k)
                                               :key k
                                               :href (str "#/" (name (home k)))
                                               :title (bib/summary (bib k))}))
                                          (:keys v)))
                     (assoc v :entries (listing order))))
            resolve-entry (fn resolve-entry [e]
                            (if (= :stack (:plato/type e))
                              (update e :slides #(mapv resolve-entry %))
                              (cond-> (update e :content #(rewrite fill %))
                                (contains? e :notes) (update :notes #(rewrite fill %)))))
            resolved (mapv resolve-entry deck-entries)]
        (if placeholder
          resolved
          (into resolved
                (map-indexed (fn [i [page id]]
                               (reference-slide title (count pages) i id (listing page))))
                (map vector pages ids)))))))
