(ns plato.board-test
  "The :board seam: contract, the static projection through open layers, and
   the page linking the board bundle exactly when a deck carries a board. No
   raster here: a Board is plain data, so a hand-made one with a 5-point frame
   stands in for what desargues.board/compile-board! returns."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.board :as board]
            [plato.board.geom :as geom]
            [plato.board.layer :as layer]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.hiccup :as hiccup]
            [plato.html :as html]))

;; f(x) = x on [-2, 2]: f' = 1, running integral from -2 is (x^2 - 4)/2.
(def value
  {:board/id :line
   :board/kind :calculus
   :board/kernel {:wasm "./vendor/boards/line.wasm" :export "line"}
   :board/label "f(x) = x"
   :board/window {:x [-2 2] :y [-3 3] :n 5}
   :board/params [{:id :a :min 0 :max 2 :step 0.1 :init 1}]
   :board/outputs [:xs :ys :dys :iys]
   :board/layers [{:layer :area :of :ys :probe :area}
                  {:layer :curve :of :ys :color :blue}
                  {:layer :tangent :of :ys :slope :dys :probe :tangent}
                  {:layer :integral :of :iys :probe :area}]
   :board/probes {:tangent 0.5 :area [-1 1]}
   :board/frame {:xs [-2.0 -1.0 0.0 1.0 2.0]
                 :ys [-2.0 -1.0 0.0 1.0 2.0]
                 :dys [1.0 1.0 1.0 1.0 1.0]
                 :iys [0.0 -1.5 -2.0 -1.5 0.0]}})

(defn- render [v] (hiccup/->html (content/render (board/board v))))

(deftest contract
  (is (board/board? value))
  (testing "a frame missing an output is not a board"
    (is (not (board/board? (update value :board/frame dissoc :dys)))))
  (testing "a kernel without a URL is not a board"
    (is (not (board/board? (update value :board/kernel dissoc :wasm)))))
  (testing "a param whose init is out of range is not a board"
    (is (not (board/board? (assoc-in value [:board/params 0 :init] 9)))))
  (is (thrown? clojure.lang.ExceptionInfo (board/board {:board/label "nope"}))))

(deftest geom-reads-the-grid
  (let [g {:x0 -2.0 :h 1.0 :xs (double-array (:xs (:board/frame value)))}
        ys (double-array (:ys (:board/frame value)))
        iys (double-array (:iys (:board/frame value)))]
    (is (= 0.5 (geom/at g ys 0.5)))
    (is (= 0.0 (geom/integral g iys -1 1)) "the integral of x over [-1, 1]")
    (is (= [[-1 0] [-1 -1.0] [0.0 0.0] [1 1.0] [1 0]] (geom/area-points g ys -1 1)))))

(deftest static-projection-draws-the-layers
  (let [html (render value)
        attr (second (re-find #"data-plato-board=\"([^\"]*)\"" html))
        carried (edn/read-string (-> attr (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))]
    (testing "the served bytes carry a plot, layer by layer, before any script"
      (is (str/includes? html "<svg"))
      (is (str/includes? html "<polygon") ":area")
      (is (str/includes? html "<circle") ":tangent's probe")
      (is (str/includes? html "f(x) = x")))
    (testing "the data attribute carries the board minus its frame"
      (is (= :board (:plato/type carried)))
      (is (= [:xs :ys :dys :iys] (get-in carried [:board :board/outputs])))
      (is (nil? (get-in carried [:board :board/frame]))))))

;; ---- OCP acceptance: a new layer kind is a defmethod, nothing else ---------

(defmethod layer/static-layer ::marker-test [_ {:keys [project]}]
  (let [[x y] (project [0 0])] [:circle {:class "marker-test" :cx x :cy y :r 3}]))

(deftest a-new-layer-is-a-registration
  (is (not (str/includes? (render value) "marker-test")))
  (is (str/includes? (render (update value :board/layers conj {:layer ::marker-test}))
                     "marker-test"))
  (testing "a layer kind nobody registered draws nothing, and fails nothing"
    (is (string? (render (update value :board/layers conj {:layer ::nobody}))))))

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
