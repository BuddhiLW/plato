(ns plato.hiccup-test
  (:require [clojure.test :refer [deftest is]]
            [plato.hiccup :as hiccup]))

(deftest parse-tag-splits-shorthand
  (is (= ["div" nil []] (hiccup/parse-tag :div)))
  (is (= ["div" "main" ["card" "pad"]] (hiccup/parse-tag :div#main.card.pad)))
  (is (= ["div" nil ["card"]] (hiccup/parse-tag :.card)))
  (is (= ["section" "intro" []] (hiccup/parse-tag :section#intro))))

(deftest shorthand-and-attr-classes-merge
  (is (= "<div class=\"card pad extra\"></div>"
         (hiccup/->html [:div.card.pad {:class "extra"}])))
  (is (= "<div class=\"card a b\"></div>"
         (hiccup/->html [:div.card {:class ["a" "b"]}])))
  (is (= "<div id=\"kept\"></div>"
         (hiccup/->html [:div#shorthand {:id "kept"}]))))

(deftest attribute-values-are-normalized
  (is (= "<a href=\"/x\" data-open=\"\">go</a>"
         (hiccup/->html [:a {:href "/x" :data-open true :data-closed false} "go"])))
  (is (= "<p style=\"color:red;font-size:12px\"></p>"
         (hiccup/->html [:p {:style {:color :red :font-size "12px"}}])))
  (is (= "<p data-t=\"fade\"></p>"
         (hiccup/->html [:p {:data-t :fade :data-skip nil}]))))

(deftest text-and-attributes-are-escaped
  (is (= "<p>a &amp; b &lt;c&gt;</p>" (hiccup/->html [:p "a & b <c>"])))
  (is (= "<p title=\"&quot;q&quot; &amp; &#39;a&#39;\"></p>"
         (hiccup/->html [:p {:title "\"q\" & 'a'"}]))))

(deftest void-elements-have-no-closing-tag
  (is (= "<img src=\"a.png\" alt=\"\">" (hiccup/->html [:img {:src "a.png" :alt ""}])))
  (is (= "<br>" (hiccup/->html [:br])))
  (is (= "<hr class=\"rule\">" (hiccup/->html [:hr.rule]))))

(deftest raw-text-elements-are-not-escaped
  (is (= "<style>.a > .b { color: red }</style>"
         (hiccup/->html [:style ".a > .b { color: red }"])))
  (is (= "<script>if (a < b && c > d) f(\"x\");</script>"
         (hiccup/->html [:script "if (a < b && c > d) f(\"x\");"]))))

(deftest raw-text-children-cannot-close-the-element
  (is (= "<script>var s = \"<\\/script>\";</script>"
         (hiccup/->html [:script "var s = \"</script>\";"])))
  (is (= "<style>a{}<\\/style>x</style>"
         (hiccup/->html [:style "a{}</style>x"]))))

(deftest collection-class-is-normalized-on-any-tag
  (is (= "<div class=\"a b\"></div>" (hiccup/->html [:div {:class ["a" "b"]}])))
  (is (= "<div class=\"a b\"></div>" (hiccup/->html [:div {:class #{"a" "b"}}])))
  (is (= "<div class=\"a\"></div>" (hiccup/->html [:div {:class {:a true :b false}}])))
  (is (= "<div></div>" (hiccup/->html [:div {:class []}])))
  (is (= "<div class=\"x\"></div>" (hiccup/->html [:div {:class :x}]))))

(deftest react-only-props-never-reach-the-document
  (is (= "<li>1</li>" (hiccup/->html [:li {:key 1} 1])))
  (is (= "<div></div>" (hiccup/->html [:div {:ref "r"}]))))

(deftest void-elements-never-close-even-with-children
  (is (= "<br>" (hiccup/->html [:br "text"])))
  (is (= "<img src=\"a.png\">"
         (hiccup/->html [:img {:src "a.png"
                               :dangerouslySetInnerHTML {:__html "<b>x</b>"}}]))))

(deftest seqs-nils-and-fragments-flatten
  (is (= "<ul><li>1</li><li>2</li></ul>"
         (hiccup/->html (into [:ul] (map (fn [n] [:li n]) [1 2])))))
  (is (= "<div><span>a</span></div>"
         (hiccup/->html [:div nil [:span "a"] false])))
  (is (= "<span>a</span><span>b</span>"
         (hiccup/->html [:<> [:span "a"] [:span "b"]]))))

(deftest dangerous-inner-html-is-emitted-verbatim
  (is (= "<div class=\"plato-html\"><b>bold</b></div>"
         (hiccup/->html [:div.plato-html {:dangerouslySetInnerHTML {:__html "<b>bold</b>"}}]))))

(deftest raw-marker-is-emitted-verbatim
  (is (= "<div><b>x</b></div>"
         (hiccup/->html [:div (hiccup/raw "<b>x</b>")]))))

(deftest component-functions-are-invoked
  (let [badge (fn [text] [:span.badge text])]
    (is (= "<span class=\"badge\">new</span>" (hiccup/->html [badge "new"])))))

(deftest scalars-render
  (is (= "" (hiccup/->html nil)))
  (is (= "" (hiccup/->html true)))
  (is (= "12" (hiccup/->html 12)))
  (is (= "<p>3.5</p>" (hiccup/->html [:p 3.5]))))
