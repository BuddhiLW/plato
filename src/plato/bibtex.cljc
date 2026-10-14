(ns plato.bibtex
  "BibTeX source text: entries as raw fields, LaTeX field text as plain text,
   and BibTeX name lists as display names. Knows nothing about decks."
  (:require [clojure.string :as str]
            [plato.nfd :as nfd]
            [plato.text :as text]))

;; ── entries ─────────────────────────────────────────────────────────────────

(def default-macros
  "@string macros every BibTeX file starts with: the month abbreviations."
  {"jan" "January" "feb" "February" "mar" "March" "apr" "April" "may" "May"
   "jun" "June" "jul" "July" "aug" "August" "sep" "September" "oct" "October"
   "nov" "November" "dec" "December"})

(defn- skip-ws [s i]
  (loop [i i]
    (if (text/space? (text/char-at s i)) (recur (inc i)) i)))

(defn- word-end
  "Index just past the identifier starting at i."
  [s i]
  (loop [i i]
    (let [c (text/char-at s i)]
      (if (and (some? c)
               (not (text/space? c))
               (not (contains? #{\, \{ \} \( \) \= \# \"} c)))
        (recur (inc i))
        i))))

(defn- balanced-end
  "Index just past the `close` matching the `open` at index i, or nil."
  [s i open close]
  (loop [j (inc i) depth 1]
    (when-let [c (text/char-at s j)]
      (cond
        (= \\ c) (recur (+ j 2) depth)
        (= open c) (recur (inc j) (inc depth))
        (= close c) (if (= 1 depth) (inc j) (recur (inc j) (dec depth)))
        :else (recur (inc j) depth)))))

(defn- quoted-end
  "Index just past the `\"` closing the quoted value at index i, or nil. A quote
   inside braces is text."
  [s i]
  (loop [j (inc i) depth 0]
    (when-let [c (text/char-at s j)]
      (cond
        (= \\ c) (recur (+ j 2) depth)
        (= \{ c) (recur (inc j) (inc depth))
        (= \} c) (recur (inc j) (dec depth))
        (and (= \" c) (zero? depth)) (inc j)
        :else (recur (inc j) depth)))))

(defn- piece
  "[text next] for one braced, quoted or bare value piece at index i, or nil."
  [s i macros]
  (let [c (text/char-at s i)]
    (cond
      (= \{ c) (when-let [end (balanced-end s i \{ \})] [(subs s (inc i) (dec end)) end])
      (= \" c) (when-let [end (quoted-end s i)] [(subs s (inc i) (dec end)) end])
      :else (let [end (word-end s i)]
              (when (> end i)
                (let [w (subs s i end)]
                  [(get macros (str/lower-case w) w) end]))))))

(defn- value
  "[text next] for a field value at index i: pieces joined by `#`."
  [s i macros]
  (loop [i i acc []]
    (when-let [[v end] (piece s (skip-ws s i) macros)]
      (let [j (skip-ws s end)]
        (if (= \# (text/char-at s j))
          (recur (inc j) (conj acc v))
          [(str/join (conj acc v)) j])))))

(defn- fields
  "[[[name raw-value]] next] for the `name = value` list starting at i and
   ending at `close`. A malformed field is skipped, not fatal."
  [s i macros close]
  (loop [i i acc []]
    (let [i (skip-ws s i)
          c (text/char-at s i)]
      (cond
        (nil? c) [acc i]
        (= close c) [acc (inc i)]
        (= \, c) (recur (inc i) acc)
        :else
        (let [end (word-end s i)
              j (skip-ws s end)]
          (if (and (> end i) (= \= (text/char-at s j)))
            (if-let [[v next] (value s (inc j) macros)]
              (recur next (conj acc [(str/lower-case (subs s i end)) v]))
              [acc (count s)])
            (recur (max (inc i) end) acc)))))))

(defn- entry-key
  "[key next] for the citation key opening an entry body at i."
  [s i close]
  (let [j (loop [j i]
            (let [c (text/char-at s j)]
              (if (or (nil? c) (= \, c) (= close c)) j (recur (inc j)))))]
    [(str/trim (subs s i j)) j]))

(defn parse-raw
  "BibTeX text -> [{:type string :key string :fields [[name raw-value]]}] in
   file order. @string macros are expanded; @comment, @preamble and text
   outside entries are skipped, as BibTeX skips them."
  [text]
  (let [s (str text)
        n (count s)]
    (loop [i 0 macros default-macros out []]
      (if-let [at (str/index-of s "@" i)]
        (let [tend (word-end s (inc at))
              kind (str/lower-case (subs s (inc at) tend))
              j (skip-ws s tend)
              open (text/char-at s j)
              close (case open \{ \} \( \) nil)]
          (cond
            (nil? close) (recur (inc at) macros out)

            (contains? #{"comment" "preamble"} kind)
            (recur (or (balanced-end s j open close) n) macros out)

            (= "string" kind)
            (let [[fs next] (fields s (inc j) macros close)]
              (recur next (into macros fs) out))

            :else
            (let [[k after-key] (entry-key s (inc j) close)
                  [fs next] (if (= close (text/char-at s after-key))
                              [[] (inc after-key)]
                              (fields s (inc after-key) macros close))]
              (recur next macros
                     (if (str/blank? k)
                       out
                       (conj out {:type kind :key k :fields fs}))))))
        out))))

;; ── LaTeX text ──────────────────────────────────────────────────────────────

(def ^:private accent-marks
  "LaTeX accent command -> combining mark."
  {"'" "\u0301" "`" "\u0300" "^" "\u0302" "\"" "\u0308" "~" "\u0303"
   "=" "\u0304" "." "\u0307" "c" "\u0327" "u" "\u0306" "v" "\u030C"
   "H" "\u030B" "k" "\u0328" "r" "\u030A"})

(def ^:private letter-commands
  "LaTeX command -> the character it stands for."
  {"ss" "ß" "ae" "æ" "AE" "Æ" "oe" "œ" "OE" "Œ" "aa" "å" "AA" "Å"
   "o" "ø" "O" "Ø" "l" "ł" "L" "Ł" "i" "ı" "j" "ȷ"})

(def ^:private composed
  "Base letter followed by one combining mark -> the precomposed character,
   inverted from `plato.nfd/table` so every host composes alike."
  (into {}
        (keep (fn [[c d]] (when (= 2 (count (str d))) [(str d) (str c)])))
        nfd/table))

(defn- accent [mark base]
  (let [base (get {"\\i" "i" "\\j" "j"} base base)
        pair (str base (accent-marks mark))]
    (get composed pair pair)))

(defn latex->text
  "LaTeX-flavoured BibTeX field text -> plain text: accents composed, letter
   commands and escaped specials replaced, other commands and grouping braces
   dropped, `---`/`--`/`~` turned into an em dash, en dash and no-break space,
   and whitespace collapsed."
  [s]
  (-> (str s)
      (str/replace #"\\([`'^\"~=.])\s*\{\s*(\\[ij]|[A-Za-z])\s*\}"
                   (fn [[_ m b]] (accent m b)))
      (str/replace #"\\([`'^\"~=.])\s*(\\[ij]|[A-Za-z])"
                   (fn [[_ m b]] (accent m b)))
      (str/replace #"\\([cuvHkr])\s*\{\s*(\\[ij]|[A-Za-z])\s*\}"
                   (fn [[_ m b]] (accent m b)))
      (str/replace #"\\([cuvHkr])\s+([A-Za-z])"
                   (fn [[_ m b]] (accent m b)))
      (str/replace #"\\([&%$#_{}])" "$1")
      (str/replace #"\\([A-Za-z]+)\s*" (fn [[_ c]] (get letter-commands c "")))
      (str/replace #"[{}]" "")
      (str/replace "---" "\u2014")
      (str/replace "--" "\u2013")
      (str/replace "~" "\u00A0")
      (str/replace #"\s+" " ")
      str/trim))

;; ── names ───────────────────────────────────────────────────────────────────

(defn- split-top
  "Split `s` wherever `sep` (a literal, matched case-insensitively and padded
   by whitespace when `padded?`) occurs outside braces."
  [s sep padded?]
  (let [n (count s)
        lower (str/lower-case s)
        hit (fn [i]
              (if padded?
                (when (text/space? (text/char-at s i))
                  (let [j (loop [j i] (if (text/space? (text/char-at s j)) (recur (inc j)) j))]
                    (when (str/starts-with? (subs lower j) sep)
                      (let [k (+ j (count sep))]
                        (when (text/space? (text/char-at s k))
                          (loop [k k] (if (text/space? (text/char-at s k)) (recur (inc k)) k)))))))
                (when (str/starts-with? (subs lower i) sep) (+ i (count sep)))))]
    (loop [i 0 depth 0 start 0 out []]
      (if (>= i n)
        (conj out (subs s start))
        (let [c (nth s i)]
          (cond
            (= \{ c) (recur (inc i) (inc depth) start out)
            (= \} c) (recur (inc i) (dec depth) start out)
            (and (zero? depth) (hit i))
            (let [next (hit i)] (recur next depth next (conj out (subs s start i))))
            :else (recur (inc i) depth start out)))))))

(defn- person
  "One BibTeX name -> \"First von Last\", from \"First von Last\", \"von Last,
   First\" or \"von Last, Jr, First\"."
  [raw]
  (let [parts (map (comp str/trim latex->text) (split-top (str/trim raw) "," false))]
    (case (count parts)
      1 (first parts)
      2 (str/trim (str (second parts) " " (first parts)))
      (str/trim (str (nth parts 2) " " (first parts) ", " (second parts))))))

(defn names
  "BibTeX name list (\"A and B\") or a collection of names -> a vector of
   display names. A braced \"{Barnes and Noble}\" is one name."
  [v]
  (cond
    (nil? v) nil
    (string? v) (->> (split-top (str/trim v) "and" true)
                     (remove str/blank?)
                     (mapv person))
    :else (mapv str v)))
