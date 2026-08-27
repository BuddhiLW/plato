(ns plato.doc-test
  (:require [clojure.test :refer [deftest is]]
            [plato.deck :as deck]
            [plato.doc :as doc]))

(deftest slug-normalizes-titles
  (is (= :q3-revenue (doc/slug "Q3 Revenue!")))
  (is (= :hello-world (doc/slug "  --Hello, World--  ")))
  (is (= :a-b-c (doc/slug "a / b / c")))
  (is (= :section (doc/slug nil)))
  (is (= :section (doc/slug ""))))

(deftest slug-keeps-every-script
  (is (= :cafe-au-lait (doc/slug "Café au lait")))
  (is (= :uber (doc/slug "Über")))
  (is (= :漢字 (doc/slug "漢字")))
  (is (= :παρουσιαση (doc/slug "Παρουσίαση")))
  (is (= :презентация (doc/slug "Презентация")))
  (is (apply distinct? (map doc/slug ["漢字" "Παρουσίαση" "Презентация"]))))

(deftest slug-falls-back-to-a-stable-hash
  (is (not= :section (doc/slug "!!!")))
  (is (= (doc/slug "!!!") (doc/slug "!!!")))
  (is (not= (doc/slug "!!!") (doc/slug "???"))))

(deftest uniquify-reserves-the-stack-id
  ;; A stacked section occupies two ids, so a sibling that already holds one of
  ;; them pushes the whole pair to the next free suffix.
  (is (= [:a :a-stack-2]
         (mapv :id (doc/uniquify [{:id :a :children [{:id :b :children []}]}
                                  {:id :a-stack :children []}]))))
  (is (= [:a-stack :a-2]
         (mapv :id (doc/uniquify [{:id :a-stack :children []}
                                  {:id :a :children [{:id :b :children []}]}]))))
  (is (deck/deck {:slides (mapv doc/section->entry
                                (doc/uniquify [{:id :a :children [{:id :b :children []}]}
                                               {:id :a-stack :children []}]))})))

(deftest uniquify-walks-the-whole-tree
  (let [sections [{:id :a :children [{:id :a :children []}]}
                  {:id :a :children []}
                  {:id :b :children []}]
        result (doc/uniquify sections)]
    (is (= [:a :a-3 :b] (mapv :id result)))
    (is (= [:a-2] (mapv :id (:children (first result)))))))

(deftest uniquify-skips-ids-already-taken
  (is (= [:a :a-2 :a-3]
         (mapv :id (doc/uniquify [{:id :a :children []}
                                  {:id :a-2 :children []}
                                  {:id :a :children []}])))))

(deftest section-slide-heading-depends-on-level
  (let [top (doc/section "Top" {:level 1 :blocks [[:p "a"]]})
        sub (doc/section "Sub" {:level 2 :blocks [[:p "b"]]})
        bare (doc/section nil {:blocks [[:p "c"]]})]
    (is (= [[:h1 "Top"] [:p "a"]] (:items (:content (doc/section->slide top)))))
    (is (= [[:h2 "Sub"] [:p "b"]] (:items (:content (doc/section->slide sub)))))
    (is (= [[:p "c"]] (:items (:content (doc/section->slide bare)))))
    (is (= :group (:plato/type (:content (doc/section->slide top)))))))

(deftest section-slide-carries-opts
  (let [slide (doc/section->slide (doc/section "Styled"
                                                {:opts {:notes "hi" :transition "fade"}}))]
    (is (= :styled (:id slide)))
    (is (= "hi" (:notes slide)))
    (is (= {:id "styled" :data-transition "fade"} (deck/section-attrs slide)))))

(deftest section-entry-nests-children-into-a-stack
  (let [child (doc/section "Child" {:level 2})
        parent (doc/section "Parent" {:children [child]})
        entry (doc/section->entry parent)]
    (is (= :slide (:plato/type (doc/section->entry child))))
    (is (= :stack (:plato/type entry)))
    (is (= :parent-stack (:id entry))
        "the stack's id must differ from its first slide's, or the DOM has duplicate ids")
    (is (= [:parent :child] (mapv :id (:slides entry))))
    (is (= :slide (:plato/type (first (:slides entry)))))))

(deftest document-deck-is-validated-and-unique
  (let [document (doc/document [(doc/section "Same" {:children [(doc/section "Same" {:level 2})]})
                                (doc/section "Same")]
                               {:title "Demo" :meta {:author "Ada"} :config {:controls false}})
        model (doc/document->deck document {:config {:transition :none}})]
    (is (= "Demo" (:title model)))
    (is (= {:author "Ada"} (:meta model)))
    (is (= [:same :same-2 :same-3] (mapv :id (deck/leaf-slides model))))
    (is (= [:stack :slide] (mapv :plato/type (:slides model))))
    (is (false? (get-in model [:config :controls])))
    (is (= :none (get-in model [:config :transition])))
    (is (true? (get-in model [:config :hash])))))

(deftest document-deck-rejects-a-document-with-no-sections
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"at least one slide"
                        (doc/document->deck (doc/document [])))))
