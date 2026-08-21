(ns plato.deck-test
  (:require [clojure.test :refer [deftest is]]
            [plato.deck :as deck]))

(deftest deck-normalization
  (let [intro (deck/slide :intro [:h1 "Hello"])
        deep  (deck/slide :deep [:h2 "Details"] {:transition :zoom})
        model (deck/deck {:title "Demo"
                          :slides [intro (deck/stack :chapter [deep])]})]
    (is (= "Demo" (:title model)))
    (is (= [:intro :deep] (mapv :id (deck/leaf-slides model))))
    (is (= :slide (:plato/type intro)))
    (is (= :stack (:plato/type (second (:slides model)))))
    (is (= :zoom (get-in model [:slides 1 :slides 0 :transition])))
    (is (= true (get-in model [:config :hash])))))

(deftest deck-rejects-duplicate-slide-ids
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Duplicate slide ids"
       (deck/deck {:slides [(deck/slide :same [:p "A"])
                            (deck/slide :same [:p "B"])]}))))

(deftest slide-options-are-reveal-data
  (let [s (deck/slide :styled [:h2 "Styled"]
                      {:background-color "#111827"
                       :auto-animate true
                       :notes "Speaker notes"})]
    (is (= "#111827" (:background-color s)))
    (is (true? (:auto-animate s)))
    (is (= "Speaker notes" (:notes s)))))
