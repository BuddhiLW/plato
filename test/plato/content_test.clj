(ns plato.content-test
  (:require [clojure.test :refer [deftest is]]
            [plato.content :as content]
            [plato.hiccup :as hiccup]))

(defn- html [value] (hiccup/->html (content/render value)))

(deftest kind-dispatches-on-shape
  (is (= :nil (content/kind nil)))
  (is (= :string (content/kind "text")))
  (is (= :hiccup (content/kind [:p "x"])))
  (is (= :seq (content/kind (map identity [1]))))
  (is (= :component (content/kind (fn []))))
  (is (= :map (content/kind {:a 1})))
  (is (= :image (content/kind (content/image "a.png")))))

(deftest plain-shapes-render-through
  (is (nil? (content/render nil)))
  (is (= "text" (content/render "text")))
  (is (= [:p "x"] (content/render [:p "x"])))
  (is (= "<span>a</span><span>b</span>"
         (html (list [:span "a"] [:span "b"])))))

(deftest nested-content-maps-expand-inside-hiccup
  (is (= "<div><h2>T</h2><ul class=\"plato-list\"><li>a</li></ul></div>"
         (html [:div [:h2 "T"] (content/bullets ["a"])]))))

(deftest image-renders-a-figure
  (is (= (str "<figure class=\"plato-figure\"><img alt=\"Chart\" src=\"assets/chart.png\">"
              "<figcaption class=\"plato-caption\">Q3</figcaption></figure>")
         (html (content/image "assets/chart.png" {:alt "Chart" :caption "Q3"}))))
  (is (= "<figure class=\"plato-figure\"><img alt=\"\" data-src=\"a.gif\"></figure>"
         (html (content/image "a.gif" {:lazy? true})))))

(deftest video-carries-sources-poster-and-reveal-autoplay
  (let [out (html (content/video nil {:sources [{:src "a.webm" :type "video/webm"}
                                                {:src "a.mp4" :type "video/mp4"}]
                                      :poster "p.jpg"
                                      :autoplay? true
                                      :loop? true}))]
    (is (re-find #"<source src=\"a.webm\" type=\"video/webm\">" out))
    (is (re-find #"<source src=\"a.mp4\" type=\"video/mp4\">" out))
    (is (re-find #"poster=\"p.jpg\"" out))
    (is (re-find #"data-autoplay=\"\"" out))
    (is (re-find #"muted=\"\"" out))
    (is (not (re-find #"<video[^>]* src=" out))))
  (is (re-find #"<video[^>]* src=\"only.mp4\"" (html (content/video "only.mp4")))))

(deftest audio-and-embed-render
  (is (re-find #"<audio class=\"plato-audio\" controls=\"\" src=\"chime.mp3\">"
               (html (content/audio "chime.mp3"))))
  (let [out (html (content/embed "e.html" {:caption "Live" :ratio "4 / 3"}))]
    (is (re-find #"aspect-ratio:4 / 3" out))
    (is (re-find #"<iframe allowFullScreen=\"\" frameBorder=\"0\" loading=\"lazy\" src=\"e.html\" title=\"Live\">" out))))

(deftest code-emits-a-highlightable-block
  (let [out (html (content/code :clojure "(< 1 2)" {:highlight "1|2"}))]
    (is (re-find #"class=\"language-clojure\"" out))
    (is (re-find #"data-line-numbers=\"1\|2\"" out))
    (is (re-find #"\(&lt; 1 2\)" out))))

(deftest prose-kinds-render
  (is (= (str "<blockquote class=\"plato-quote\">Ship it."
              "<footer class=\"plato-cite\">Wile E.</footer></blockquote>")
         (html (content/quotation "Ship it." {:cite "Wile E."}))))
  (is (= "<ol class=\"plato-list\"><li>a</li><li>b</li></ol>"
         (html (content/bullets ["a" "b"] {:ordered? true}))))
  (is (= "<ul class=\"plato-list\"><li class=\"fragment grow\">a</li></ul>"
         (html (content/bullets ["a"] {:fragments? true :effect :grow})))))

(deftest table-renders-head-and-body
  (let [out (html (content/table ["Q" "Rev"] [["Q1" "1.2M"] ["Q2" "1.9M"]] {:caption "Revenue"}))]
    (is (re-find #"<caption>Revenue</caption>" out))
    (is (re-find #"<thead><tr><th>Q</th><th>Rev</th></tr></thead>" out))
    (is (re-find #"<tbody><tr><td>Q1</td><td>1.2M</td></tr>" out))))

(deftest layout-kinds-render
  (is (re-find #"<div class=\"plato-columns\" style=\"grid-template-columns:1fr 2fr\">"
               (html (content/columns [[:p "a"] [:p "b"]] {:widths ["1fr" "2fr"]}))))
  (let [out (html (content/columns
                   [(content/column [:p "fill"] {:width :fill})
                    (content/column [:p "shrink"] {:width :shrink})
                    (content/column [:p "fixed"] {:width {:px 240}})]))]
    (is (re-find #"<div class=\"plato-column\" style=\"width:100%\"><p>fill</p>" out))
    (is (re-find #"<div class=\"plato-column\" style=\"width:fit-content\"><p>shrink</p>" out))
    (is (re-find #"<div class=\"plato-column\" style=\"width:240px\"><p>fixed</p>" out)))
  (let [out (html (content/cards [{:title "One" :body "First" :icon "1" :width {:px 240}}]
                                 {:columns 2 :fragments? true}))]
    (is (re-find #"grid-template-columns:repeat\(2, 1fr\)" out))
    (is (re-find #"<div class=\"plato-card fragment\" style=\"width:240px\">" out))
    (is (re-find #"<h3>One</h3>" out)))
  (is (= "<div class=\"plato-group\" style=\"width:100%\"><p>a</p><p>b</p></div>"
         (html (content/group [[:p "a"] [:p "b"]] {:width :fill}))))
  (doseq [width [:wide {:px -1} {:px 1.5} {:px 1 :extra true}]]
    (is (= {:width width}
           (try
             (html (content/group [[:p "a"]] {:width width}))
             nil
             (catch clojure.lang.ExceptionInfo ex
               (ex-data ex))))))
  (is (= "<div class=\"fragment fade-up\" data-fragment-index=\"2\"><p>x</p></div>"
         (html (content/fragment [:p "x"] {:effect :fade-up :index 2})))))

(deftest note-and-kicker-render
  (is (= (str "<div class=\"plato-note plato-note-warn\">"
              "<div class=\"plato-note-title\">Careful</div><p>x</p></div>")
         (html (content/note [:p "x"] {:tone :warn :title "Careful"}))))
  (is (= "<div class=\"plato-kicker\">Q3</div>" (html (content/kicker "Q3")))))

(deftest escape-hatches-render
  (is (= (str "<div data-markdown=\"\"><textarea data-template=\"\">"
              "# Hi\n\n- &lt;b&gt;a&lt;/b&gt;</textarea></div>")
         (html (content/markdown "# Hi\n\n- <b>a</b>"))))
  (is (= "<div class=\"plato-html\"><b>raw</b></div>"
         (html (content/html "<b>raw</b>")))))

(deftest unknown-content-kind-does-not-loop
  (let [out (html {:plato/type :mystery})]
    (is (re-find #"class=\"plato-unknown\"" out))
    (is (re-find #"data-content-type=\":mystery\"" out))))

(deftest component-call-is-not-descended-into
  ;; A defmethod may hand back [component tagged-map]; the walker must treat the
  ;; rest as arguments, or it re-expands the tagged map forever.
  (let [component (fn [m] [:div.live (:plato/type m)])
        looping {:plato/type :looping}]
    (defmethod content/render :looping [m] [component m])
    (try
      (is (= [component looping] (content/expand [component looping])))
      (is (= [:div [component looping]]
             (content/expand [:div {:plato/type :looping}])))
      (is (= "<div><div class=\"live\">looping</div></div>"
             (html [:div {:plato/type :looping}])))
      (finally (remove-method content/render :looping)))))
