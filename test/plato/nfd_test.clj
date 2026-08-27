(ns plato.nfd-test
  "The JVM's Normalizer is the oracle for the table plato carries.

   plato.text/decompose has one implementation on every dialect, which is what
   keeps a slide id from depending on the runtime that built the deck. That is
   only worth anything if the table it reads really is NFD — so these tests
   check it against java.text.Normalizer in both directions."
  (:require [clojure.test :refer [deftest is testing]]
            [plato.doc :as doc]
            [plato.nfd :as nfd]
            [plato.text :as text])
  (:import [java.text Normalizer Normalizer$Form]))

(defn- nfd [^String s] (Normalizer/normalize s Normalizer$Form/NFD))

(defn- covered-codepoints []
  (for [[lo hi] nfd/blocks, cp (range lo (inc hi))] cp))

(deftest every-entry-is-the-canonical-decomposition
  (doseq [[k v] nfd/table]
    (is (= (nfd (str k)) v) (str "NFD of " (pr-str k)))))

(deftest the-table-covers-every-decomposable-character-in-its-blocks
  (let [missing (for [cp (covered-codepoints)
                      :let [s (str (char cp))]
                      :when (and (not= s (nfd s)) (not (contains? nfd/table (char cp))))]
                  (format "U+%04X" cp))]
    (is (empty? missing) (str "not in the table: " (pr-str (take 20 missing))))))

(deftest characters-outside-the-blocks-are-left-alone
  (is (= "abc" (text/decompose "abc")))
  (is (= "日本語" (text/decompose "日本語"))))

(deftest decompose-separates-marks-from-their-base-letter
  (testing "left side decomposed, right side precomposed"
    (is (= "Über" (text/decompose "Über")))
    (is (= "ça" (text/decompose "ça")))
    (is (= "ά" (text/decompose "ά")))))

(deftest slugs-fold-accents-to-their-base-letter
  (testing "the property decompose exists to serve"
    (is (= :uber (doc/slug "Über")))
    (is (= :sao-paulo (doc/slug "São Paulo")))
    (is (= :cafe-creme (doc/slug "Café Crème")))
    (is (= (keyword "παρουσιαση")
           (doc/slug "Παρουσίαση")))))
