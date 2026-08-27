(ns plato.css-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.css :as css]))

(deftest declarations-become-a-block
  (is (= ".a {\n  color: #fff;\n}"
         (css/rules->css [[:.a {:color "#fff"}]])))
  (testing "keyword values contribute their name and sequentials become a list"
    (is (= ".a {\n  display: grid;\n  font-family: Inter, sans-serif;\n}"
           (css/rules->css [[:.a {:display :grid :font-family ["Inter" "sans-serif"]}]]))))
  (testing "a nil value drops the declaration rather than emitting nil"
    (is (= ".a {\n  color: red;\n}"
           (css/rules->css [[:.a {:color "red" :background nil}]]))))
  (testing "a rule with no declarations emits no block"
    (is (= "" (css/rules->css [[:.a]])))))

(deftest nesting-joins-selectors
  (is (= ".a {\n  color: red;\n}\n\n.a .b {\n  color: blue;\n}"
         (css/rules->css [[:.a {:color "red"} [:.b {:color "blue"}]]])))
  (testing "& splices the parent in place"
    (is (= ".a:hover {\n  color: blue;\n}"
           (css/rules->css [[:.a [":hover" nil]]  ; no decls: nothing
                            [:.a ["&:hover" {:color "blue"}]]]))))
  (testing "nesting is arbitrarily deep"
    (is (str/includes? (css/rules->css [[:.a [:.b [:.c {:color "red"}]]]])
                       ".a .b .c {"))))

(deftest a-selector-group-is-a-vector
  (is (= ".a, .b {\n  margin: 0;\n}"
         (css/rules->css [[[:.a :.b] {:margin 0}]])))
  (testing "a group multiplies through its children"
    (is (str/includes? (css/rules->css [[[:.a :.b] [:h1 {:color "red"}]]])
                       ".a h1, .b h1 {"))))

(deftest at-rules-wrap-and-keep-their-context
  (is (= "@media print {\n  .a {\n    color: red;\n  }\n}"
         (css/rules->css [["@media print" [:.a {:color "red"}]]])))
  (testing "an at-rule nested under a selector keeps that selector"
    (is (str/includes?
         (css/rules->css [[:.a {:color "red"}
                           ["@media print" [:& {:color "black"}]]]])
         "@media print {\n  .a {")))
  (testing "at-rules nest"
    (is (str/includes?
         (css/rules->css [["@supports (display: grid)"
                           ["@media print" [:.a {:display "grid"}]]]])
         "@supports (display: grid) {\n  @media print {")))
  (testing "a bodiless at-rule is a statement"
    (is (= "@import url(\"a.css\");"
           (css/rules->css [["@import url(\"a.css\")"]])))))

(deftest document-order-is-preserved
  ;; Order is the cascade, so the emitter must never reorder: the override
  ;; inside @media has to land after the rule it overrides.
  (is (= [".a {" "@media print {" ".a {" ".b {"]
         (->> (css/rules->css [[:.a {:color "red"}]
                               ["@media print" [:.a {:color "black"}]]
                               [:.b {:color "blue"}]])
              str/split-lines
              (keep #(when (str/ends-with? % "{") (str/trim %)))
              vec))))
