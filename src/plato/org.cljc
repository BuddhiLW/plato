(ns plato.org
  "Org-mode front end: text -> plato.doc document IR -> deck.

   Subset: #+KEY: file keywords, * headlines with TODO keywords and :tags:,
   :PROPERTIES: and :NOTES: drawers, #+BEGIN_ blocks (SRC, QUOTE, EXAMPLE,
   EXPORT, NOTES), the #+CAPTION: and #+ATTR_PLATO: affiliated keywords, plain
   and ordered lists with checkboxes, tables, [[link][description]] links,
   standalone media links, # comments, and inline *bold* /italic/ _underline_
   =verbatim= ~code~ +strike+."
  (:require [clojure.string :as str]
            [plato.content :as content]
            [plato.doc :as doc]))

(def image-extensions #{"png" "jpg" "jpeg" "gif" "svg" "webp" "avif" "bmp"})
(def video-extensions #{"mp4" "webm" "ogv" "mov" "m4v"})
(def audio-extensions #{"mp3" "wav" "ogg" "m4a" "flac"})

(def todo-keywords
  "Headline keywords stripped from a section title."
  #{"TODO" "DONE" "NEXT" "STARTED" "DOING" "WAIT" "WAITING" "HOLD" "REVIEW"
    "BLOCKED" "SOMEDAY" "CANCELLED" "CANCELED"})

(def markup-tags
  "Org emphasis marker -> hiccup tag."
  {"*" :strong "/" :em "_" :u "=" :code "~" :code "+" :del})

(def verbatim-markers
  "Markers whose body is literal text, never nested markup."
  #{"=" "~"})

(def affiliated-keywords
  "#+KEY: names that attach to the next element, never to the document."
  #{"CAPTION" "NAME" "HEADER" "RESULTS"})

;; ── text ────────────────────────────────────────────────────────────────────

(defn- lines
  "Text -> vector of lines, CRLF and CR normalized to LF."
  [text]
  (str/split (str/replace (str text) #"\r\n|\r" "\n") #"\n" -1))

(defn- comment-line? [line]
  (boolean (and (string? line) (re-matches #"\s*#(\s.*)?" line))))

(defn- keyword-line
  "[KEY value] for a \"#+KEY: value\" line, KEY upper-cased, or nil."
  [line]
  (when (string? line)
    (when-let [[_ k v] (re-matches #"\s*#\+([A-Za-z][A-Za-z0-9_@-]*):\s?(.*)" line)]
      [(str/upper-case k) v])))

(defn- flag-value
  "Property/attribute value: \"t\", \"true\" and \"yes\" become true."
  [value]
  (let [t (str/trim (str value))]
    (if (contains? #{"t" "true" "yes"} (str/lower-case t)) true t)))

;; ── inline ──────────────────────────────────────────────────────────────────

(defn- char-at [s i]
  (when (and (>= i 0) (< i (count s))) (nth s i)))

(defn- at? [s i sub]
  (let [end (+ i (count sub))]
    (and (<= end (count s)) (= sub (subs s i end)))))

(defn- space? [ch] (or (nil? ch) (boolean (re-matches #"\s" (str ch)))))

(defn- pre-char?
  "True when `ch` may sit before an opening emphasis marker."
  [ch]
  (or (nil? ch) (boolean (re-matches #"[\s\-('\"{]" (str ch)))))

(defn- post-char?
  "True when `ch` may sit after a closing emphasis marker."
  [ch]
  (or (nil? ch) (boolean (re-matches #"[\s\-.,:!?;'\")}\[]" (str ch)))))

(defn- emphasis-at
  "{:marker :text :next} for an emphasis run opening at index i, or nil. The
   marker binds only at a word boundary, and its body may not start or end with
   whitespace."
  [s i]
  (let [marker (str (char-at s i))]
    (when (and (markup-tags marker)
               (pre-char? (char-at s (dec i)))
               (not (space? (char-at s (inc i)))))
      (loop [j (inc i)]
        (when-let [end (str/index-of s marker j)]
          (if (and (> end (inc i))
                   (not (space? (char-at s (dec end))))
                   (post-char? (char-at s (inc end))))
            {:marker marker :text (subs s (inc i) end) :next (inc end)}
            (recur (inc end))))))))

(defn- link-at
  "{:target :desc :next} for a [[target][desc]] opening at index i, or nil."
  [s i]
  (when (at? s i "[[")
    (when-let [close (str/index-of s "]" (+ i 2))]
      (let [target (subs s (+ i 2) close)]
        (cond
          (at? s close "]]") {:target target :next (+ close 2)}
          (at? s close "][")
          (when-let [end (str/index-of s "]]" (+ close 2))]
            {:target target :desc (subs s (+ close 2) end) :next (+ end 2)}))))))

(defn- link-path
  "Org link target -> path, with a leading \"file:\" stripped."
  [target]
  (str/replace (str/trim (str target)) #"^file:" ""))

(declare inline)

(defn- token
  "Inline token starting at index i, as [node next-index], or nil."
  [s i]
  (if (at? s i "[[")
    (when-let [{:keys [target desc next]} (link-at s i)]
      (let [href (link-path target)]
        [(into [:a {:href href}] (if (str/blank? desc) [href] (inline desc))) next]))
    (when-let [{:keys [marker text next]} (emphasis-at s i)]
      [(if (verbatim-markers marker)
         [(markup-tags marker) text]
         (into [(markup-tags marker)] (inline text)))
       next])))

(defn inline
  "Inline org markup -> vector of hiccup nodes (strings and elements)."
  [text]
  (let [s (str text)
        n (count s)]
    (loop [i 0 buf [] nodes []]
      (let [flushed (if (seq buf) (conj nodes (str/join buf)) nodes)]
        (if (>= i n)
          flushed
          (if-let [[node next-i] (token s i)]
            (recur next-i [] (conj flushed node))
            (recur (inc i) (conj buf (nth s i)) nodes)))))))

(defn- inline-content
  "Inline markup as one content value: the lone node when there is one, else
   a seq — never a vector, whose first element would be read as a tag."
  [text]
  (let [nodes (inline text)]
    (case (count nodes)
      0 nil
      1 (first nodes)
      (seq nodes))))

;; ── line predicates ─────────────────────────────────────────────────────────

(defn- split-tags
  "[title tags] for a headline body with a trailing :tag:list:."
  [text]
  (if-let [[_ body tags] (re-matches #"(.*?)\s+(:(?:[A-Za-z0-9_@#%]+:)+)" text)]
    [body (vec (remove str/blank? (str/split tags #":")))]
    [text []]))

(defn- strip-todo [text]
  (let [[word remaining] (str/split text #"\s+" 2)]
    (if (todo-keywords word) (str remaining) text)))

(defn- headline
  "{:level :title :tags} for a headline line, or nil. A TODO keyword, a [#A]
   priority cookie and the trailing tags are stripped from the title."
  [line]
  (when (string? line)
    (when-let [[_ stars text] (re-matches #"(\*+)\s+(.*)" line)]
      (let [[body tags] (split-tags (str/trim text))
            title (-> (strip-todo body)
                      (str/replace #"^\[#[A-Za-z0-9]\]\s*" "")
                      str/trim)]
        {:level (count stars)
         :title (when-not (str/blank? title) title)
         :tags tags}))))

(defn- block-info
  "{:name :args} for a \"#+BEGIN_X args\" line, or nil."
  [line]
  (when (string? line)
    (when-let [[_ k args] (re-matches #"\s*#\+([A-Za-z]+_[A-Za-z0-9_-]+)\s*(.*)" line)]
      (let [k (str/upper-case k)]
        (when (str/starts-with? k "BEGIN_")
          {:name (subs k 6) :args (str/trim args)})))))

(defn- block-end? [name line]
  (boolean (and (string? line)
                (when-let [[_ k] (re-matches #"\s*#\+([A-Za-z]+_[A-Za-z0-9_-]+)\s*" line)]
                  (= (str/upper-case k) (str "END_" name))))))

(defn- drawer-name
  "Upper-cased name of a \":NAME:\" drawer line, or nil."
  [line]
  (when (string? line)
    (when-let [[_ n] (re-matches #"\s*:([A-Za-z][A-Za-z0-9_-]*):\s*" line)]
      (str/upper-case n))))

(defn- drawer-open? [line]
  (let [n (drawer-name line)]
    (when (and n (not= "END" n)) n)))

(defn- caption-text
  "Caption declared by a #+CAPTION: line, or nil."
  [line]
  (when-let [[k v] (keyword-line line)]
    (when (= "CAPTION" k) (str/trim v))))

(defn- plist
  "Org plist text -> {keyword value}; a key with no value is true."
  [text]
  (loop [[t & more] (remove str/blank? (str/split (str/trim (str text)) #"\s+"))
         k nil vs [] acc {}]
    (let [close (fn [m]
                  (if k
                    (assoc m k (if (seq vs) (flag-value (str/join " " vs)) true))
                    m))]
      (cond
        (nil? t) (close acc)
        (str/starts-with? t ":") (recur more (keyword (subs t 1)) [] (close acc))
        :else (recur more k (conj vs t) acc)))))

(defn- attr-opts
  "Content options declared by a #+ATTR_PLATO: line, or nil."
  [line]
  (when-let [[k v] (keyword-line line)]
    (when (= "ATTR_PLATO" k) (plist v))))

(defn- list-item
  "[ordered? text] for a list-item line, or nil."
  [line]
  (when (string? line)
    (or (when-let [[_ text] (re-matches #"\s*[-+]\s+(.*)" line)] [false text])
        (when-let [[_ text] (re-matches #"\s*\d+[.)]\s+(.*)" line)] [true text]))))

(defn- item-text
  "List-item text with a leading [ ] / [X] checkbox replaced by its glyph."
  [text]
  (if-let [[_ state body] (re-matches #"\[([ xX-])\]\s*(.*)" (str text))]
    (str (if (contains? #{"x" "X"} state) "☑" "☐") " " body)
    text))

(defn- table-row? [line]
  (boolean (and (string? line) (str/starts-with? (str/trim line) "|"))))

(defn- separator-row? [line]
  (boolean (and (string? line)
                (re-matches #"\s*\|[-+|\s]*" line)
                (str/includes? line "-"))))

(defn- extension [src]
  (let [path (first (str/split (str src) #"[?#]"))
        base (str (last (str/split (str path) #"/")))
        dot (str/last-index-of base ".")]
    (when dot (str/lower-case (subs base (inc dot))))))

(defn- media-block
  "Standalone link line whose target is an image, video or audio file -> a
   content value, or nil."
  [line]
  (when (string? line)
    (when-let [[_ target desc] (re-matches #"\s*\[\[([^\]]*)\](?:\[([^\]]*)\])?\]\s*" line)]
      (let [src (link-path target)
            ext (extension src)]
        (cond
          (video-extensions ext) (content/video src)
          (audio-extensions ext) (content/audio src)
          (or (image-extensions ext) (str/starts-with? (str/trim target) "file:"))
          (content/image src (cond-> {} (seq desc) (assoc :alt desc))))))))

(defn- block-opener?
  "True when `line` cannot continue a paragraph."
  [line]
  (boolean (or (not (string? line))
               (str/blank? line)
               (comment-line? line)
               (headline line)
               (block-info line)
               (keyword-line line)
               (drawer-name line)
               (list-item line)
               (table-row? line)
               (media-block line))))

;; ── blocks ──────────────────────────────────────────────────────────────────

(defn- take-block
  "[body-lines remaining] for the block opened by the first line of `ls`. An
   unterminated block runs to the end of the section."
  [ls]
  (let [{:keys [name]} (block-info (first ls))
        inner (next ls)
        body (take-while #(not (block-end? name %)) inner)
        remaining (drop (count body) inner)]
    [body (if (seq remaining) (next remaining) remaining)]))

(defn- take-drawer
  "[body-lines remaining] for the drawer opened by the first line of `ls`, or
   nil when that drawer is never closed. An unterminated :DRAWER: is ordinary
   text, not a licence to swallow the rest of the section."
  [ls]
  (let [inner (next ls)
        body (take-while #(not= "END" (drawer-name %)) inner)]
    (when (< (count body) (count inner))
      [body (next (drop (count body) inner))])))

(defn- block-text
  "Block body lines joined, with blank lines trimmed from both ends."
  [ls]
  (->> ls
       (drop-while str/blank?)
       reverse
       (drop-while str/blank?)
       reverse
       (str/join "\n")))

(defn- block-lang
  "Language keyword from a #+BEGIN_SRC argument line, or nil."
  [args]
  (let [lang (first (remove str/blank? (str/split (str/trim (str args)) #"\s+")))]
    (when (and lang (not (str/starts-with? lang ":"))) (keyword lang))))

(defn- property-key [k]
  (keyword (str/lower-case (str/replace k #"_" "-"))))

(defn- properties
  "Property-drawer lines -> {option-key value}."
  [ls]
  (into {}
        (keep (fn [line]
                (when (string? line)
                  (when-let [[_ k v] (re-matches #"\s*:([A-Za-z][A-Za-z0-9_+-]*):\s*(.*)" line)]
                    [(property-key k) (flag-value v)]))))
        ls))

(defn- take-list [ls]
  (let [ordered? (first (list-item (first ls)))]
    (loop [ls ls items []]
      (let [line (first ls)
            item (list-item line)
            after (when (and (string? line) (str/blank? line))
                    (drop-while #(and (string? %) (str/blank? %)) ls))]
        (cond
          item (recur (next ls) (conj items (inline-content (item-text (second item)))))
          (= ordered? (first (list-item (first after)))) (recur after items)
          :else [(content/bullets items (cond-> {} ordered? (assoc :ordered? true)))
                 ls])))))

(defn- row-cells [line]
  (let [t (str/trim line)
        t (if (str/starts-with? t "|") (subs t 1) t)
        t (if (str/ends-with? t "|") (subs t 0 (dec (count t))) t)]
    (mapv (comp inline-content str/trim) (str/split t #"\|" -1))))

(defn- take-table [ls]
  (let [rows (take-while table-row? ls)
        [head body] (if (and (> (count rows) 1) (separator-row? (second rows)))
                      [(row-cells (first rows)) (drop 2 rows)]
                      [nil rows])]
    [(content/table head (map row-cells (remove separator-row? body)))
     (drop (count rows) ls)]))

(defn- take-paragraph [ls]
  (let [body (cons (first ls) (take-while #(not (block-opener? %)) (next ls)))]
    [(into [:p] (inline (str/join " " (map str/trim body))))
     (drop (count body) ls)]))

(defn parse-blocks
  "Section body lines -> {:blocks [content-value] :opts {...} :id id-or-nil}.

   A :NOTES: drawer and a #+BEGIN_NOTES block both hold speaker notes, and both
   are PARSED rather than captured as raw text, so :notes is a content value —
   the same shape the markdown front end produces."
  [ls]
  (loop [ls (seq ls) blocks [] opts {} id nil pending {}]
    (if-let [line (first ls)]
      (let [{:keys [level title]} (headline line)]
        (cond
          (str/blank? line) (recur (next ls) blocks opts id {})

          (comment-line? line) (recur (next ls) blocks opts id pending)

          (and (drawer-open? line) (take-drawer ls))
          (let [drawer (drawer-open? line)
                [body remaining] (take-drawer ls)
                props (if (= "PROPERTIES" drawer) (properties body) {})]
            (recur remaining blocks
                   (cond-> (merge opts (dissoc props :custom-id))
                     (= "NOTES" drawer)
                     (assoc :notes (content/collect (:blocks (parse-blocks body)))))
                   (if-let [custom (:custom-id props)] (keyword (str custom)) id)
                   pending))

          (block-info line)
          (let [{:keys [name args]} (block-info line)
                [body remaining] (take-block ls)
                source (block-text body)]
            (case name
              "NOTES" (recur remaining blocks
                             (assoc opts :notes
                                    (content/collect (:blocks (parse-blocks body))))
                             id pending)

              "SRC" (recur remaining
                           (conj blocks (content/code (block-lang args) source pending))
                           opts id {})

              "EXAMPLE" (recur remaining
                               (conj blocks (content/code :text source pending))
                               opts id {})

              "QUOTE" (recur remaining
                             (conj blocks
                                   (content/quotation
                                    (into [:p] (inline (str/join " " (map str/trim (remove str/blank? body)))))
                                    pending))
                             opts id {})

              "EXPORT" (recur remaining
                              (conj blocks (merge (content/html source) pending))
                              opts id {})

              (recur remaining blocks opts id pending)))

          (caption-text line)
          (recur (next ls) blocks opts id (assoc pending :caption (caption-text line)))

          (attr-opts line)
          (recur (next ls) blocks opts id (merge pending (attr-opts line)))

          (keyword-line line) (recur (next ls) blocks opts id pending)

          level
          (recur (next ls)
                 (conj blocks (into [(keyword (str "h" (min level 6)))] (inline title)))
                 opts id {})

          (list-item line)
          (let [[block remaining] (take-list ls)]
            (recur remaining (conj blocks (merge block pending)) opts id {}))

          (table-row? line)
          (let [[block remaining] (take-table ls)]
            (recur remaining (conj blocks (merge block pending)) opts id {}))

          (media-block line)
          (recur (next ls) (conj blocks (merge (media-block line) pending)) opts id {})

          :else
          (let [[block remaining] (take-paragraph ls)]
            (recur remaining (conj blocks block) opts id {}))))
      {:blocks blocks :opts opts :id id})))

;; ── sections ────────────────────────────────────────────────────────────────

(defn- boundary
  "{:level :title :tags} when `line` opens a new section, else nil."
  [line]
  (when-let [{:keys [level] :as head} (headline line)]
    (when (<= level 2) head)))

(defn- split-chunks
  "Lines -> [{:level :title :tags :lines}] split on level-1 and level-2
   headlines. Blocks AND drawers are opaque: a `*` line inside either is
   content, not a headline. A drawer that is never closed is not opaque —
   otherwise one stray :NOTES: would fuse the rest of the file into one slide."
  [ls]
  (loop [ls (seq ls) block nil drawer? false
         current {:level 1 :title nil :tags [] :lines []} chunks []]
    (if-let [line (first ls)]
      (cond
        block (recur (next ls)
                     (if (block-end? block line) nil block)
                     drawer?
                     (update current :lines conj line)
                     chunks)

        drawer? (recur (next ls) block (not= "END" (drawer-name line))
                       (update current :lines conj line) chunks)

        (block-info line) (recur (next ls)
                                 (:name (block-info line))
                                 drawer?
                                 (update current :lines conj line)
                                 chunks)

        (and (drawer-open? line)
             (some #(= "END" (drawer-name %)) (next ls)))
        (recur (next ls) block true (update current :lines conj line) chunks)

        (boundary line) (recur (next ls) nil false
                               (assoc (boundary line) :lines [])
                               (conj chunks current))

        :else (recur (next ls) block drawer? (update current :lines conj line) chunks))
      (conj chunks current))))

(defn- empty-chunk? [{:keys [title lines]}]
  (and (nil? title) (every? str/blank? lines)))

(defn- chunk->section [{:keys [level title tags lines]}]
  (let [{:keys [blocks opts id]} (parse-blocks lines)]
    (doc/section title (cond-> {:level level
                                :opts (cond-> opts (seq tags) (assoc :tags tags))
                                :blocks blocks}
                         id (assoc :id id)))))

(defn- nest
  "Chunks -> section tree: level-2 chunks become :children of the level-1
   section they follow, or top-level sections when none precedes them."
  [chunks]
  (reduce (fn [tops chunk]
            (let [sec (chunk->section chunk)]
              (if (or (= 1 (:level chunk)) (empty? tops))
                (conj tops (assoc sec :level 1))
                (update-in tops [(dec (count tops)) :children] conj sec))))
          []
          chunks))

(defn- file-keyword
  "[key value] for a document-level \"#+KEY: value\" line, or nil."
  [line]
  (when-let [[k v] (keyword-line line)]
    (when-not (or (affiliated-keywords k) (str/starts-with? k "ATTR_"))
      [(keyword (str/lower-case k)) (str/trim v)])))

(defn- preamble-keyword-indexes
  "Indexes of the preamble lines that are file keywords. A #+KEYWORD: inside a
   #+BEGIN_ ... #+END_ block is block content and is never one."
  [preamble]
  (loop [i 0 block nil acc #{}]
    (if-let [line (get preamble i)]
      (cond
        block (recur (inc i) (if (block-end? block line) nil block) acc)
        (block-info line) (recur (inc i) (:name (block-info line)) acc)
        (file-keyword line) (recur (inc i) block (conj acc i))
        :else (recur (inc i) block acc))
      acc)))

(defn- file-keywords
  "[meta remaining-lines]. File keywords are read from the preamble — the lines
   before the first headline — and dropped from the body. A #+KEYWORD: inside a
   preamble block stays in the block and never becomes document metadata."
  [ls]
  (let [n (count (take-while #(nil? (headline %)) ls))
        preamble (vec (take n ls))
        indexes (preamble-keyword-indexes preamble)]
    [(into {} (keep file-keyword) (map preamble (sort indexes)))
     (concat (keep-indexed (fn [i line] (when-not (contains? indexes i) line)) preamble)
             (drop n ls))]))

;; ── api ─────────────────────────────────────────────────────────────────────

(defn parse
  "Org text -> a plato.doc document IR."
  [text]
  (let [[metadata body] (file-keywords (lines text))]
    (doc/document (nest (remove empty-chunk? (split-chunks body)))
                  {:title (:title metadata)
                   :meta (dissoc metadata :title)})))

(defn ->deck
  "Org text -> a validated plato.deck/deck."
  ([text] (doc/document->deck (parse text)))
  ([text opts] (doc/document->deck (parse text) opts)))
