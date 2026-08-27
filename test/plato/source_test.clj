(ns plato.source-test
  "The set of source formats is open, and these tests are written so that they
   would still pass if plato shipped a fourth one — they assert the dispatch,
   not the roster."
  (:require [clojure.test :refer [deftest is testing]]
            [plato.data :as data]
            [plato.deck :as deck]
            [plato.markdown]
            [plato.org]
            [plato.source :as source]))

(deftest extensions-resolve-to-a-kind
  (is (= :markdown (source/kind "a.md")))
  (is (= :markdown (source/kind "A.MARKDOWN")))
  (is (= :org (source/kind "deck/talk.org")))
  (is (= :edn (source/kind "deck/talk.edn")))
  (testing "an extension nothing registered is not a kind"
    (is (nil? (source/kind "talk.txt")))
    (is (nil? (source/kind "no-extension")))))

(deftest every-registered-extension-has-a-front-end
  (testing "registering an extension without a method would be a dead format"
    (doseq [[ext kind] (source/known-extensions)]
      (is (contains? (methods source/->deck) kind)
          (str "." ext " resolves to " kind " but nothing reads it)")))))

(deftest an-unread-kind-says-so-rather-than-failing-obscurely
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No front end reads"
                        (source/->deck :latex "\\begin{frame}"))))

(deftest the-bundled-front-ends-dispatch
  (is (= :a (-> (source/->deck :markdown "# A\n\ntext\n") deck/leaf-slides first :id)))
  (is (= :a (-> (source/->deck :org "* A\n\ntext\n") deck/leaf-slides first :id))))

(deftest a-deck-round-trips-through-edn
  (testing "pr-str of a deck is a valid source file"
    (let [model (deck/deck {:title "Round trip"
                            :slides [(deck/slide :one [:h1 "One"] {:notes "n"})
                                     (deck/stack :two [(deck/slide :a [:p "A"])
                                                       (deck/slide :b [:p "B"])])]})
          reread (data/->deck (pr-str model))]
      (is (= model reread))
      (is (= [:one :a :b] (mapv :id (deck/leaf-slides reread))))))
  (testing "and it is validated, not trusted"
    (is (thrown? clojure.lang.ExceptionInfo (data/->deck "{:slides []}")))))
