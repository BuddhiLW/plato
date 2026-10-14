(ns plato.cite-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.bib :as bib]
            [plato.bibtex :as bibtex]
            [plato.cli :as cli]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.hiccup :as hiccup]
            [plato.html :as html]
            [plato.markdown :as markdown]
            [plato.org :as org]
            [plato.cite :as cite])
  (:import (java.io File)))

(def bibtex-source
  "@string{cacm = \"Communications of the ACM\"}
@comment{ @misc{ignored, title = {not an entry}} }

@article{knuth84,
  author = {Donald E. Knuth},
  title = {Literate Programming},
  journal = {The Computer Journal},
  volume = 27, number = {2}, pages = {97--111}, year = 1984,
  doi = {10.1093/comjnl/27.2.97}}

@book(goedel,
  author = \"G{\\\"o}del, Kurt and {Barnes and Noble} and others\",
  title = {{\\\"U}ber formal unentscheidbare S\\\"atze},
  publisher = cacm # \" Press\",
  year = 1931)")

(def refs (bib/parse-bibtex bibtex-source))

(defn- cites-in [node]
  (filter #(and (map? %) (= :cite (:plato/type %)))
          (tree-seq coll? seq node)))

(deftest bibtex-entries
  (is (= #{:knuth84 :goedel} (set (keys refs))) "@comment hides what it holds")
  (let [{:keys [type author title pages year doi]} (:knuth84 refs)]
    (is (= :article type))
    (is (= ["Donald E. Knuth"] author))
    (is (= "Literate Programming" title))
    (is (= "97–111" pages) "-- is an en dash")
    (is (= "1984" year) "a bare number is a string")
    (is (= "10.1093/comjnl/27.2.97" doi) "a DOI is verbatim"))
  (let [{:keys [type author title publisher]} (:goedel refs)]
    (is (= :book type) "parenthesised entries parse")
    (is (= ["Kurt Gödel" "Barnes and Noble" "others"] author)
        "Last, First is reordered; a braced and is not a separator")
    (is (= "Über formal unentscheidbare Sätze" title)
        "accents compose to one precomposed character")
    (is (= "Communications of the ACM Press" publisher) "@string macros and # concatenate")))

(deftest latex-text
  (is (= "naïve café — ß" (bibtex/latex->text "na\\\"{\\i}ve caf\\'e --- {\\ss}")))
  (is (= "François & co" (bibtex/latex->text "Fran\\c{c}ois \\& \\emph{co}"))))

(deftest data-bibliographies-normalize-like-bibtex
  (is (= (:knuth84 refs)
         (:knuth84 (bib/entries {:knuth84 {:type :article
                                           :author "Donald E. Knuth"
                                           :title "Literate Programming"
                                           :journal "The Computer Journal"
                                           :volume 27 :number "2" :pages "97--111"
                                           :year 1984
                                           :doi "10.1093/comjnl/27.2.97"}}))))
  (is (= ["A" "B"] (:author (:x (bib/entries [{:key :x :author ["A" "B"]}]))))))

(deftest citation-text
  (is (= (content/cite [:a]) (cite/from-text "@a")))
  (is (= (content/cite [:a :b] {:prefix "see" :note "p. 3"})
         (cite/from-text "see @a; @b, p. 3")))
  (is (nil? (cite/from-text "mail me@example.com")) "an @ inside a word is no citation"))

(defn- slide-deck [& slides]
  {:slides (vec slides) :bibliography refs})

(deftest numbering-follows-first-citation
  (let [d (deck/deck (slide-deck (deck/slide :one [:p (content/cite :goedel)])
                                 (deck/slide :two [:p (content/cite [:knuth84 :goedel])])))
        [first-cite second-cite] (cites-in (map :content (:slides d)))]
    (is (= [:one :two :references] (mapv :id (:slides d))))
    (is (= [1] (map :n (:refs first-cite))))
    (is (= [2 1] (map :n (:refs second-cite))))
    (is (every? #(= "#/references" %) (map :href (:refs second-cite))))
    (testing "the reference list links back to the slides that cite each work"
      (let [page (html/deck->html d {})]
        (is (str/includes? page "id=\"ref-goedel\" value=\"1\""))
        (is (str/includes? page "<a href=\"#/one\">1</a>, <a href=\"#/two\">2</a>"))))))

(deftest uncited-entries-are-left-out
  (let [d (deck/deck (slide-deck (deck/slide :one [:p (content/cite :knuth84)])))
        listed (-> d :slides last :content :items second :entries)]
    (is (= [:knuth84] (map :key listed)))))

(deftest long-lists-split-across-slides
  (let [many (into {} (for [i (range 7)] [(keyword (str "k" i)) {:title (str "T" i)}]))
        d (deck/deck {:slides [(deck/slide :a [:p (content/cite (vec (keys many)))])]
                      :bibliography many
                      :references {:per-slide 3 :title "Works"}})
        pages (rest (:slides d))]
    (is (= [:references :references-2 :references-3] (map :id pages)))
    (is (= ["Works (1/3)" "Works (3/3)"]
           (map #(-> % :content :items first second) [(first pages) (last pages)])))
    (is (= "#/references-3" (-> (cites-in (:content (first (:slides d)))) first :refs last :href))
        "a citation points at the page its entry is on")))

(deftest generated-ids-never-collide
  (let [d (deck/deck (slide-deck (deck/slide :references [:p (content/cite :knuth84)])))]
    (is (= [:references :references-2] (mapv :id (:slides d))))))

(deftest a-placeholder-places-the-list
  (let [d (deck/deck (slide-deck (deck/slide :a [:p (content/cite :knuth84)])
                                 (deck/slide :refs (content/bibliography))
                                 (deck/slide :end [:p "Thanks"])))]
    (is (= [:a :refs :end] (mapv :id (:slides d))) "nothing is appended")
    (is (= "#/refs" (-> (cites-in (:content (first (:slides d)))) first :refs first :href)))))

(deftest the-pass-is-idempotent
  (let [d (deck/deck (slide-deck (deck/slide :one [:p (content/cite :knuth84)])))]
    (is (= d (deck/deck d)) "a deck re-validated does not grow a second reference slide")
    (is (= d (deck/deck (read-string (pr-str d)))) "and the EDN form round-trips")))

(deftest unknown-keys-fail-the-build
  (let [e (try (deck/deck (slide-deck (deck/slide :one [:p (content/cite :nobody)])))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= {:missing [:nobody] :slides [:one]} (ex-data e)))))

(deftest a-named-file-leaves-citations-pending
  (let [d (markdown/->deck "---\nbibliography: refs.bib\n---\n\n# A\n\nx [@knuth84]\n")
        [c] (cites-in (:content (first (:slides d))))]
    (is (= 1 (count (:slides d))) "no reference slide until the file is read")
    (is (nil? (:refs c)))
    (is (str/includes? (hiccup/->html (content/render c)) "?"))))

(def markdown-source
  "# Intro\n\nLiterate [@knuth84] and incomplete [see @goedel; @knuth84, p. 3]. A [link](http://x.y), and me@x.com.\n\n# Refs\n\n::: {#refs}\n:::\n")

(def org-source
  "* Intro\n\nLiterate [cite:@knuth84] and incomplete [cite:see @goedel;@knuth84, p. 3]. A [[http://x.y][link]], and me@x.com.\n\n* Refs\n\n#+PRINT_BIBLIOGRAPHY:\n")

(deftest markdown-and-org-cite-alike
  (let [m (markdown/->deck markdown-source {:bibliography refs})
        o (org/->deck org-source {:bibliography refs})]
    (is (= [:intro :refs] (mapv :id (:slides m)) (mapv :id (:slides o))))
    (is (= (html/deck->html m {}) (html/deck->html o {})))
    (is (= [{:plato/type :cite :keys [:knuth84]}
            {:plato/type :cite :keys [:goedel :knuth84] :prefix "see" :note "p. 3"}]
           (map #(dissoc % :refs) (cites-in (:content (first (:slides m)))))))))

(deftest the-cli-reads-the-bibliography-beside-the-source
  (let [dir (doto (File/createTempFile "plato-cite" "") (.delete) (.mkdirs))
        source (str dir "/talk.md")]
    (try
      (spit (str dir "/refs.bib") bibtex-source)
      (spit source "---\nbibliography: refs.bib\nreferences-title: Bibliografia\n---\n\n# A\n\nx [@goedel]\n")
      (let [{:keys [deck]} (cli/build-job (cli/deck-job source {:print? true}))]
        (is (= [:a :references] (mapv :id (:slides deck))))
        (is (= "Bibliografia" (-> deck :slides last :content :items first second))))
      (finally
        (doseq [^File f (reverse (file-seq dir))] (.delete f))))))
