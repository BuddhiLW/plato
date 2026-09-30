(ns plato.board-test
  "The :board seam: contract, static projection, and the page linking the
   board bundle exactly when a deck carries a board. No raster here: a board
   value is plain data, so a hand-made one with a 5-point frame stands in for
   what desargues.board/compile-board! returns."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.board :as board]
            [plato.board.geom :as geom]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.hiccup :as hiccup]
            [plato.html :as html]))

;; f(x) = x on [-2, 2]: f' = 1, running integral from -2 is (x^2 - 4)/2.
(def value
  {:board/id :line
   :board/kernel {:wasm "./vendor/boards/line.wasm" :export "line"}
   :board/label "f(x) = x"
   :board/window {:x [-2 2] :y [-3 3] :n 5}
   :board/params [{:id :a :min 0 :max 2 :step 0.1 :init 1}]
   :board/probes {:tangent 0.5 :area [-1 1]}
   :board/frame {:xs [-2.0 -1.0 0.0 1.0 2.0]
                 :ys [-2.0 -1.0 0.0 1.0 2.0]
                 :dys [1.0 1.0 1.0 1.0 1.0]
                 :iys [0.0 -1.5 -2.0 -1.5 0.0]}})

(deftest contract
  (is (board/board? value))
  (testing "a frame that disagrees with the window is not a board"
    (is (not (board/board? (assoc-in value [:board/window :n] 7)))))
  (testing "a kernel without a URL is not a board"
    (is (not (board/board? (update value :board/kernel dissoc :wasm)))))
  (is (thrown? clojure.lang.ExceptionInfo (board/board {:board/label "nope"}))))

(deftest geom-reads-the-grid
  (let [g {:x0 -2.0 :h 1.0 :xs (double-array (:xs (:board/frame value)))}
        ys (double-array (:ys (:board/frame value)))
        iys (double-array (:iys (:board/frame value)))]
    (is (= 0.5 (geom/at g ys 0.5)))
    (is (= 0.0 (geom/integral g iys -1 1)) "the integral of x over [-1, 1]")
    (is (= [[-1 0] [-1 -1.0] [0.0 0.0] [1 1.0] [1 0]]
           (geom/area-points g ys -1 1)))))

(deftest static-projection
  (let [html (hiccup/->html (content/render (board/board value)))
        attr (second (re-find #"data-plato-board=\"([^\"]*)\"" html))
        carried (edn/read-string (-> attr (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))]
    (testing "the served bytes carry a plot, before any script"
      (is (str/includes? html "<svg"))
      (is (str/includes? html "class=\"f\""))
      (is (str/includes? html "f(x) = x")))
    (testing "the data attribute carries the board minus its frame"
      (is (= :board (:plato/type carried)))
      (is (= "./vendor/boards/line.wasm" (get-in carried [:board :board/kernel :wasm])))
      (is (nil? (get-in carried [:board :board/frame]))))))

(deftest page-links-the-board-bundle-only-when-needed
  (let [with (deck/deck {:title "b" :slides [(deck/slide :b [:div (board/board value)])]})
        without (deck/deck {:title "p" :slides [(deck/slide :p [:div "plain"])]})]
    (is (html/needs-board-runtime? with))
    (is (not (html/needs-board-runtime? without)))
    (let [page (html/deck->html with)]
      (is (str/includes? page "vendor/plato-board/main.js"))
      (is (str/includes? page "css/plato-board.css"))
      (is (str/includes? page "plato.board_island.hydrate()")))
    (is (not (str/includes? (html/deck->html without) "plato-board")))))
