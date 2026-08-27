(ns plato.text
  "Character primitives shared by every front end.

   The lowest stratum of the text stack: it knows about characters, never about
   markdown, org or decks. Every predicate is Unicode-aware on every dialect,
   spelled per-dialect on purpose — a \\p{...} class inside a shared regex
   literal does not survive ClojureScript's RegExp reconstruction, which drops
   the u flag."
  (:require [plato.nfd :as nfd]))

(defn char-at
  "Character at index `i` of `s`, or nil when `i` is out of bounds."
  [s i]
  (when (and (>= i 0) (< i (count s))) (nth s i)))

(defn char-code
  "UTF-16 code unit of `c`."
  [c]
  #?(:clj (int ^char c) :cljs (.charCodeAt c 0) :default (int c)))

(def letter-or-digit?
  "True when `c` is a Unicode letter or digit. nil is not."
  #?(:clj  (fn [c] (and c (Character/isLetterOrDigit ^char c)))
     :cljs (let [re (js/RegExp. "[\\p{L}\\p{N}]" "u")]
             (fn [c] (and (some? c) (.test re (str c)))))
     :default (fn [c] (and c (some? (re-matches #"[A-Za-z0-9]" (str c)))))))

(def mark?
  "True when `c` is a Unicode combining mark."
  #?(:clj  (fn [c] (and c (contains? #{(int Character/NON_SPACING_MARK)
                                       (int Character/COMBINING_SPACING_MARK)
                                       (int Character/ENCLOSING_MARK)}
                                     (Character/getType ^char c))))
     :cljs (let [re (js/RegExp. "\\p{M}" "u")]
             (fn [c] (and (some? c) (.test re (str c)))))
     :default (constantly false)))

(def space?
  "True when `c` is Unicode whitespace. nil is a boundary, not a space."
  #?(:clj  (fn [c] (and c (Character/isWhitespace ^char c)))
     :cljs (let [re (js/RegExp. "\\s" "u")]
             (fn [c] (and (some? c) (.test re (str c)))))
     :default (fn [c] (and c (some? (re-matches #"\s" (str c)))))))

(defn decompose
  "`s` with every precomposed character expanded into its base letter and
   combining marks, so a mark can be dropped on its own. Characters outside
   `plato.nfd/table`'s blocks come back unchanged.

   One implementation on every dialect — a host normalizer would make the same
   heading slug differently depending on which runtime built the deck."
  [s]
  (if (some nfd/table s)
    (apply str (map #(get nfd/table % %) s))
    s))

(defn stable-hash
  "Deterministic 31-rolling hash of `s`. Identical on every host, so an id
   derived from it is reproducible across front ends and builds."
  [s]
  (reduce (fn [h c] (mod (+ (* h 31) (char-code c)) 0x7FFFFFFF)) 7 s))
