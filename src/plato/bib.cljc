(ns plato.bib
  "Bibliographies: entries and how one is shown.

   entry {:key keyword :type keyword :author [string] :editor [string]
          :title string :year string :url string :doi string ...}

   A bibliography is {key entry}. `parse-bibtex` reads one from BibTeX and
   `entries` normalizes one written as data; `plato.cite` resolves the
   citations of a deck against it."
  (:require [clojure.string :as str]
            #?(:cljs [cljs.reader :as edn] :default [clojure.edn :as edn])
            [plato.bibtex :as bibtex]))

;; ── entries ─────────────────────────────────────────────────────────────────

(def name-fields #{:author :editor})

(def verbatim-fields
  "Fields kept exactly as written: identifiers and addresses, never prose."
  #{:url :doi :key :type})

(defn entry
  "Normalize one entry. `k` names it when the map carries no :key. Name fields
   become vectors of display names; other string fields go through
   `plato.bibtex/latex->text`, except `verbatim-fields`."
  ([m] (entry (:key m) m))
  ([k m]
   (reduce-kv (fn [acc field v]
                (assoc acc field
                       (cond
                         (name-fields field) (bibtex/names v)
                         (verbatim-fields field) v
                         (string? v) (bibtex/latex->text v)
                         (number? v) (str v)
                         :else v)))
              {:key (keyword (name k)) :type :misc}
              (dissoc m :key))))

(defn entries
  "Bibliography written as data -> {key entry}. Takes {key fields} or a
   collection of entries that each carry :key."
  [bibliography]
  (if (map? bibliography)
    (into {} (map (fn [[k m]] (let [e (entry k m)] [(:key e) e]))) bibliography)
    (into {} (map (fn [m] (let [e (entry m)] [(:key e) e]))) bibliography)))

(defn parse-bibtex
  "BibTeX text -> {key entry}."
  [text]
  (into {}
        (map (fn [{:keys [type key fields]}]
               (let [e (entry key (into {:type (keyword type)}
                                        (map (fn [[f v]] [(keyword f) v]))
                                        fields))]
                 [(:key e) e])))
        (bibtex/parse-raw text)))

(defn read-bibliography
  "Bibliography file text -> {key entry}, by the extension of `path`: .bib is
   BibTeX, .edn is a bibliography written as data."
  [path text]
  (let [p (str/lower-case (str path))]
    (cond
      (str/ends-with? p ".bib") (parse-bibtex text)
      (str/ends-with? p ".edn") (entries (edn/read-string text))
      :else (throw (ex-info (str "Unknown bibliography format: " path
                                 " (expected .bib or .edn)")
                            {:path path})))))

(defn paths
  "The bibliography files a deck's :meta names: one path, several separated by
   commas, or a collection."
  [meta-value]
  (cond
    (string? meta-value) (vec (remove str/blank? (map str/trim (str/split meta-value #","))))
    (coll? meta-value) (vec meta-value)
    :else []))

;; ── formatting ──────────────────────────────────────────────────────────────

(defn- name-list [ns]
  (case (count ns)
    0 nil
    1 (first ns)
    2 (str (first ns) " and " (second ns))
    (str (str/join ", " (butlast ns)) ", and " (last ns))))

(defn- people
  "Display names -> one string. A trailing BibTeX `others` becomes et al."
  [ns]
  (let [others? (= "others" (last ns))
        ns (if others? (butlast ns) ns)]
    (cond
      (empty? ns) nil
      others? (str (str/join ", " ns) " et al.")
      :else (name-list ns))))

(defn- sentence
  "`s` ending in terminal punctuation, so joined parts never read \"al..\"."
  [s]
  (if (re-find #"[.?!]$" s) s (str s ".")))

(defn entry-href
  "Where an entry's title links: its :url, else its DOI resolver URL."
  [{:keys [url doi]}]
  (cond
    url url
    (and doi (str/starts-with? doi "http")) doi
    doi (str "https://doi.org/" doi)))

(defn- venue [{:keys [journal booktitle publisher school institution
                      howpublished organization volume number pages]}]
  (let [where (or journal booktitle howpublished school institution organization publisher)
        issue (cond-> (str volume)
                number (str "(" number ")"))
        parts (remove str/blank? [where issue (when pages (str "pp. " pages))])]
    (when (seq parts) (str/join ", " parts))))

(defn summary
  "Entry -> one line of plain text: what a citation's tooltip shows."
  [{:keys [author editor title year] :as e}]
  (str/join " " (map sentence (remove str/blank? [(people (or author editor)) title (venue e) year]))))

(defn entry-hiccup
  "Entry -> hiccup for its line in the reference list: author. title. venue,
   year. The title links to the work when the entry has a :url or :doi."
  [{:keys [author editor title year] :as e}]
  (let [href (entry-href e)
        who (people (or author editor))
        what (or title (name (:key e)))
        where (venue e)]
    [:span.plato-bib-entry
     (when who [:span.plato-bib-author (sentence who) " "])
     (if href
       [:a.plato-bib-title {:href href :target "_blank" :rel "noopener"} what]
       [:span.plato-bib-title what])
     (if (re-find #"[.?!]$" what) " " ". ")
     (when where [:span.plato-bib-venue where (if year ", " ". ")])
     (when year [:span.plato-bib-year year "."])]))
