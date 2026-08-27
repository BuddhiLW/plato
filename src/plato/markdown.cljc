(ns plato.markdown
  "Markdown front end: text -> plato.doc document IR -> deck.

   Subset: YAML-ish front matter, ATX headings, Reveal's --- / -- separators,
   Note: speaker notes, <!-- .slide: ... --> options, paragraphs, lists, fenced
   code, blockquotes, GFM tables, thematic breaks, standalone media lines, and
   inline **strong** *em* `code` ~~del~~ [link]() ![image]()."
  (:require [clojure.string :as str]
            [plato.content :as content]
            [plato.doc :as doc]
            [plato.text :as text]))

(def video-extensions #{"mp4" "webm" "ogv" "mov" "m4v"})
(def audio-extensions #{"mp3" "wav" "ogg" "m4a" "flac"})

;; ── text ────────────────────────────────────────────────────────────────────

(defn- lines
  "Text -> vector of lines, CRLF and CR normalized to LF."
  [text]
  (str/split (str/replace (str text) #"\r\n|\r" "\n") #"\n" -1))

(defn- unquote-value [value]
  (let [t (str/trim value)]
    (if (re-matches #"[\"'].*[\"']" t) (subs t 1 (dec (count t))) t)))

(defn- meta-pairs [ls]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches #"\s*([A-Za-z_][\w.-]*)\s*:\s*(.*)" line)]
                  [(keyword k) (unquote-value v)])))
        ls))

(defn- meta-line?
  "True when `line` is a front-matter `key: value` pair."
  [line]
  (some? (re-matches #"\s*[A-Za-z_][\w.-]*\s*:\s*.*" (str line))))

(defn- front-matter
  "[meta remaining-lines]. Front matter is a --- delimited block of key: value
   pairs at the very start. A block that is unterminated, empty, or holds any
   line that is not a key: value pair is NOT front matter: the opening --- is a
   leading Reveal separator and the block is the first slide."
  [ls]
  (let [not-front-matter [{} ls]]
    (if (and (seq ls) (= "---" (str/trim (first ls))))
      (let [body (next ls)
            n (count (take-while #(not= "---" (str/trim %)) body))
            block (take n body)
            pairs (remove str/blank? block)]
        (if (and (< n (count body)) (seq pairs) (every? meta-line? pairs))
          [(meta-pairs block) (drop (inc n) body)]
          not-front-matter))
      not-front-matter)))

;; ── inline ──────────────────────────────────────────────────────────────────

(defn- at? [s i sub]
  (let [end (+ i (count sub))]
    (and (<= end (count s)) (= sub (subs s i end)))))

(defn- punctuation? [ch] (boolean (re-matches #"[^A-Za-z0-9\s]" (str ch))))

(defn- delimited
  "{:text :next} for `open` at index i closed by the next `close` that
   `accept?` admits (called with the index of that closing run), or nil."
  ([s i open close] (delimited s i open close (constantly true)))
  ([s i open close accept?]
   (let [start (+ i (count open))]
     (loop [from start]
       (when-let [end (str/index-of s close from)]
         (cond
           (<= end start) (recur (inc end))
           (accept? end) {:text (subs s start end) :next (+ end (count close))}
           :else (recur (inc end))))))))

(defn- opens-emphasis?
  "A delimiter run of length `n` at index `i` opens emphasis only when what
   follows it is neither whitespace nor the end of the text."
  [s i n]
  (let [c (text/char-at s (+ i n))]
    (and (some? c) (not (text/space? c)))))

(defn- closes-emphasis?
  "A delimiter run starting at `end` closes emphasis only when what precedes it
   is not whitespace."
  [s end]
  (let [c (text/char-at s (dec end))]
    (and (some? c) (not (text/space? c)))))

(defn- lone-star?
  "True when the `*` at index `i` is a run of exactly one, so it never pairs
   with half of a `**` run."
  [s i]
  (and (not= \* (text/char-at s (dec i)))
       (not= \* (text/char-at s (inc i)))))

(defn- matching-paren
  "Index of the `)` closing the `(` at index `open`, honouring nesting and
   backslash escapes, or nil."
  [s open]
  (let [n (count s)]
    (loop [i (inc open) depth 1]
      (when (< i n)
        (case (nth s i)
          \\ (recur (+ i 2) depth)
          \( (recur (inc i) (inc depth))
          \) (if (= 1 depth) i (recur (inc i) (dec depth)))
          (recur (inc i) depth))))))

(defn- link-at
  "{:label :dest :next} for a [label](dest) starting at the \"[\" at index i.
   The destination may hold balanced parentheses."
  [s i]
  (when-let [close (str/index-of s "]" i)]
    (when (= \( (text/char-at s (inc close)))
      (when-let [paren (matching-paren s (inc close))]
        {:label (subs s (inc i) close)
         :dest (subs s (+ close 2) paren)
         :next (inc paren)}))))

(defn- destination
  "Link destination -> [src title]; the title is a quoted string after the src."
  [dest]
  (let [d (str/trim (str dest))]
    (if-let [[_ src title] (re-matches #"(\S+)\s+[\"']([^\"']*)[\"']" d)]
      [src title]
      [d nil])))

(declare inline)

(defn- token
  "Inline token starting at index i, as [node next-index], or nil."
  [s i]
  (cond
    (at? s i "![")
    (when-let [{:keys [label dest next]} (link-at s (inc i))]
      (let [[src title] (destination dest)]
        [[:img (cond-> {:src src :alt label} title (assoc :title title))] next]))

    (at? s i "[")
    (when-let [{:keys [label dest next]} (link-at s i)]
      (let [[href title] (destination dest)]
        [(into [:a (cond-> {:href href} title (assoc :title title))] (inline label))
         next]))

    (at? s i "`")
    (when-let [{:keys [text next]} (delimited s i "`" "`")]
      [[:code text] next])

    (and (at? s i "**") (opens-emphasis? s i 2))
    (when-let [{:keys [text next]} (delimited s i "**" "**" #(closes-emphasis? s %))]
      [(into [:strong] (inline text)) next])

    (and (at? s i "~~") (opens-emphasis? s i 2))
    (when-let [{:keys [text next]} (delimited s i "~~" "~~" #(closes-emphasis? s %))]
      [(into [:del] (inline text)) next])

    (and (at? s i "*") (lone-star? s i) (opens-emphasis? s i 1))
    (when-let [{:keys [text next]} (delimited s i "*" "*"
                                              #(and (lone-star? s %)
                                                    (closes-emphasis? s %)))]
      [(into [:em] (inline text)) next])

    (and (at? s i "_")
         (not (text/letter-or-digit? (text/char-at s (dec i))))
         (opens-emphasis? s i 1))
    (when-let [{:keys [text next]} (delimited s i "_" "_" #(closes-emphasis? s %))]
      (when-not (text/letter-or-digit? (text/char-at s next))
        [(into [:em] (inline text)) next]))))

(defn inline
  "Inline markdown -> vector of hiccup nodes (strings and elements)."
  [source]
  (let [s (str source)
        n (count s)]
    (loop [i 0 buf [] nodes []]
      (let [flushed (if (seq buf) (conj nodes (str/join buf)) nodes)]
        (if (>= i n)
          flushed
          (let [c (nth s i)]
            (cond
              (and (= \\ c) (punctuation? (text/char-at s (inc i))))
              (recur (+ i 2) (conj buf (text/char-at s (inc i))) nodes)

              :else
              (if-let [[node next-i] (token s i)]
                (recur next-i [] (conj flushed node))
                (recur (inc i) (conj buf c) nodes)))))))))

(defn- inline-content
  "Inline markdown as one content value: the lone node when there is one, else
   a seq — never a vector, whose first element would be read as a tag."
  [text]
  (let [nodes (inline text)]
    (case (count nodes)
      0 nil
      1 (first nodes)
      (seq nodes))))

;; ── line predicates ─────────────────────────────────────────────────────────

(defn- fence-info
  "{:marker :lang} for a fence-opening line, or nil."
  [line]
  (when (string? line)
    (when-let [[_ marker info] (re-matches #"\s{0,3}(`{3,}|~{3,})\s*(.*)" line)]
      {:marker marker
       :lang (first (remove str/blank? (str/split (str/trim info) #"\s+")))})))

(defn- fence-close? [marker line]
  (when-let [[_ m] (re-matches #"\s{0,3}(`{3,}|~{3,})\s*" (str line))]
    (and (= (first m) (first marker)) (>= (count m) (count marker)))))

(defn- heading
  "[level text] for an ATX heading line, or nil. `text` is nil for an empty
   heading, so `#` opens a section without inventing a blank title. A trailing
   run of # is a closing sequence only when whitespace precedes it, so
   \"# Why C#\" keeps its #."
  [line]
  (when (string? line)
    (when-let [[_ hashes body] (re-matches #"\s{0,3}(#{1,6})(?:[ \t]+(.*))?[ \t]*" line)]
      [(count hashes)
       (let [t (str/trim (str/replace (or body "") #"(^|\s)#+\s*$" "$1"))]
         (when-not (str/blank? t) t))])))

(defn- list-item
  "[ordered? text] for a list-item line, or nil."
  [line]
  (when (string? line)
    (or (when-let [[_ text] (re-matches #"\s*[-*+]\s+(.*)" line)] [false text])
        (when-let [[_ text] (re-matches #"\s*\d+[.)]\s+(.*)" line)] [true text]))))

(defn- thematic-break? [line]
  (boolean (and (string? line)
                (re-matches #"\s{0,3}((\*\s*){3,}|(_\s*){3,}|(-\s*){3,})" line))))

(defn- table-row? [line]
  (boolean (and (string? line) (str/starts-with? (str/trim line) "|"))))

(defn- separator-row? [line]
  (boolean (and (string? line) (re-matches #"\s*\|?[\s:|-]*-[\s:|-]*\|?\s*" line))))

(defn- quote-text [line]
  (when (string? line)
    (when-let [[_ text] (re-matches #"\s{0,3}>\s?(.*)" line)] text)))

(defn- note-text
  "Speaker-note text on a Note: line, or nil."
  [line]
  (when (string? line)
    (when-let [[_ text] (re-matches #"\s*[Nn]ote:\s*(.*)" line)] text)))

(defn- slide-attrs
  "Slide options declared by a <!-- .slide: ... --> line, or nil."
  [line]
  (when (string? line)
    (when-let [[_ body] (re-matches #"\s*<!--\s*\.slide:\s*(.*?)\s*-->\s*" line)]
      (into {}
            (map (fn [[_ k v]] [(keyword (str/replace k #"^data-" "")) v]))
            (re-seq #"([\w-]+)\s*=\s*[\"']([^\"']*)[\"']" body)))))

(defn- extension [src]
  (let [path (first (str/split (str src) #"[?#]"))
        base (str (last (str/split (str path) #"/")))
        dot (str/last-index-of base ".")]
    (when dot (str/lower-case (subs base (inc dot))))))

(defn- media-block
  "Standalone image/video/audio line -> a content value, or nil."
  [line]
  (when (string? line)
    (when-let [[_ alt dest] (re-matches #"\s*!\[([^\]]*)\]\(([^)]*)\)\s*" line)]
      (let [[src title] (destination dest)
            ext (extension src)
            opts (cond-> {} title (assoc :caption title))]
        (cond
          (video-extensions ext) (content/video src opts)
          (audio-extensions ext) (content/audio src opts)
          :else (content/image src (cond-> opts (seq alt) (assoc :alt alt))))))))

(defn- block-opener?
  "True when `line` cannot continue a paragraph."
  [line]
  (boolean (or (not (string? line))
               (str/blank? line)
               (heading line)
               (fence-info line)
               (thematic-break? line)
               (list-item line)
               (table-row? line)
               (quote-text line)
               (note-text line)
               (slide-attrs line)
               (media-block line))))

;; ── blocks ──────────────────────────────────────────────────────────────────

(defn- take-fence
  "Fenced code block. An unterminated fence runs to the end of the section."
  [ls]
  (let [{:keys [marker lang]} (fence-info (first ls))
        remaining (next ls)
        body (take-while #(not (fence-close? marker %)) remaining)
        closed? (< (count body) (count remaining))
        source (if closed? body (reverse (drop-while str/blank? (reverse body))))]
    [(content/code (when lang (keyword lang)) (str/join "\n" source))
     (drop (inc (count body)) remaining)]))

(defn- take-list [ls]
  (let [ordered? (first (list-item (first ls)))]
    (loop [ls ls items []]
      (let [line (first ls)
            item (list-item line)
            after (when (and (string? line) (str/blank? line))
                    (drop-while #(and (string? %) (str/blank? %)) ls))]
        (cond
          item (recur (next ls) (conj items (inline-content (second item))))
          (= ordered? (first (list-item (first after)))) (recur after items)
          :else [(content/bullets items (cond-> {} ordered? (assoc :ordered? true)))
                 ls])))))

(defn- split-cells
  "Split a table row on its unescaped `|`. A `\\|` is a literal pipe inside a
   cell and never a column boundary."
  [s]
  (let [n (count s)]
    (loop [i 0 buf [] out []]
      (cond
        (>= i n) (conj out (str/join buf))
        (and (= \\ (nth s i)) (= \| (text/char-at s (inc i))))
        (recur (+ i 2) (conj buf \|) out)
        (= \| (nth s i)) (recur (inc i) [] (conj out (str/join buf)))
        :else (recur (inc i) (conj buf (nth s i)) out)))))

(defn- row-cells [line]
  (let [t (str/trim line)
        t (if (str/starts-with? t "|") (subs t 1) t)
        t (if (and (str/ends-with? t "|") (not (str/ends-with? t "\\|")))
            (subs t 0 (dec (count t)))
            t)]
    (mapv (comp inline-content str/trim) (split-cells t))))

(defn- take-table [ls]
  (let [rows (take-while table-row? ls)
        [head body] (if (and (> (count rows) 1) (separator-row? (second rows)))
                      [(row-cells (first rows)) (drop 2 rows)]
                      [nil rows])]
    ;; A separator row is alignment syntax wherever it appears; rendering a
    ;; stray one as data would put |---|---| in a cell.
    [(content/table head (map row-cells (remove separator-row? body)))
     (drop (count rows) ls)]))

(defn- take-quote [ls]
  (let [body (take-while quote-text ls)]
    [(content/quotation (into [:p] (inline (str/join " " (map quote-text body)))))
     (drop (count body) ls)]))

(defn- take-paragraph [ls]
  (let [body (cons (first ls) (take-while #(not (block-opener? %)) (next ls)))]
    [(into [:p] (inline (str/join " " (map str/trim body))))
     (drop (count body) ls)]))

(defn parse-blocks
  "Section body lines -> {:blocks [content-value] :opts {...}}.

   A `Note:` line starts speaker notes, which run to the end of the slide as
   Reveal defines them. Those lines are PARSED, not captured as raw text, so
   :notes is a content value like every other block."
  [ls]
  (loop [ls (seq ls) blocks [] opts {}]
    (if-let [line (first ls)]
      (let [[level title] (heading line)]
        (cond
          (str/blank? line) (recur (next ls) blocks opts)

          (slide-attrs line)
          (recur (next ls) blocks (merge opts (slide-attrs line)))

          (note-text line)
          {:blocks blocks
           :opts (assoc opts :notes
                        (content/collect
                         (:blocks (parse-blocks (cons (note-text line) (next ls))))))}

          (fence-info line)
          (let [[block remaining] (take-fence ls)]
            (recur remaining (conj blocks block) opts))

          level
          (recur (next ls)
                 (conj blocks (into [(keyword (str "h" level))] (inline title)))
                 opts)

          (thematic-break? line) (recur (next ls) (conj blocks [:hr]) opts)

          (list-item line)
          (let [[block remaining] (take-list ls)]
            (recur remaining (conj blocks block) opts))

          (table-row? line)
          (let [[block remaining] (take-table ls)]
            (recur remaining (conj blocks block) opts))

          (quote-text line)
          (let [[block remaining] (take-quote ls)]
            (recur remaining (conj blocks block) opts))

          (media-block line)
          (recur (next ls) (conj blocks (media-block line)) opts)

          :else
          (let [[block remaining] (take-paragraph ls)]
            (recur remaining (conj blocks block) opts))))
      {:blocks blocks :opts opts})))

;; ── sections ────────────────────────────────────────────────────────────────

(defn- separator-level
  "1 for a `---` slide separator, 2 for a `--` vertical separator, else nil.
   Like every other block predicate, at most three leading spaces."
  [line]
  (when-let [[_ dashes] (re-matches #" {0,3}(---|--)[ \t]*" (str line))]
    (if (= 3 (count dashes)) 1 2)))

(defn- boundary
  "{:level :title} when `line` opens a new section, else nil."
  [line]
  (or (when-let [level (separator-level line)]
        {:level level :title nil})
      (when-let [[level title] (heading line)]
        (when (<= level 2) {:level level :title title}))))

(defn- split-chunks
  "Lines -> [{:level :title :lines}] split on headings and separators. Fenced
   code is opaque: a separator inside a fence does not split."
  [ls]
  (loop [ls (seq ls) fence nil current {:level 1 :title nil :lines []} chunks []]
    (if-let [line (first ls)]
      (cond
        fence (recur (next ls)
                     (if (fence-close? fence line) nil fence)
                     (update current :lines conj line)
                     chunks)

        (fence-info line) (recur (next ls)
                                 (:marker (fence-info line))
                                 (update current :lines conj line)
                                 chunks)

        (boundary line) (recur (next ls) nil
                               (assoc (boundary line) :lines [])
                               (conj chunks current))

        :else (recur (next ls) fence (update current :lines conj line) chunks))
      (conj chunks current))))

(defn- empty-chunk? [{:keys [title lines]}]
  (and (nil? title) (every? str/blank? lines)))

(defn- chunk->section [{:keys [level title lines]}]
  (let [{:keys [blocks opts]} (parse-blocks lines)]
    (doc/section title {:level level :opts opts :blocks blocks})))

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

;; ── api ─────────────────────────────────────────────────────────────────────

(defn parse
  "Markdown text -> a plato.doc document IR."
  [text]
  (let [[metadata body] (front-matter (lines text))]
    (doc/document (nest (remove empty-chunk? (split-chunks body)))
                  {:title (:title metadata)
                   :meta (dissoc metadata :title)})))

(defn ->deck
  "Markdown text -> a validated plato.deck/deck."
  ([text] (doc/document->deck (parse text)))
  ([text opts] (doc/document->deck (parse text) opts)))
