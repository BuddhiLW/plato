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
            [plato.board.view :as view]
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

(deftest board-math-is-typeset-beside-the-board
  (let [with-math (assoc value :board/math ["f(x) = x" "f'(x) = 1"])
        html (render with-math)
        deck-of (fn [v] (deck/deck {:title "m" :slides [(deck/slide :m [:div (board/board v)])]}))]
    (testing "each TeX line is set for KaTeX, in order (HTML-escaped; KaTeX reads the decoded text)"
      (is (str/includes? html "<span class=\"plato-board-eq\">\\(f(x) = x\\)</span><span class=\"plato-board-eq\">\\(f&#39;(x) = 1\\)</span>")))
    (testing "outside [data-plato-board], which the island empties when it mounts"
      (is (< (str/index-of html "data-plato-board=") (str/index-of html "plato-board-math")))
      (is (str/includes? html "</p></div><div class=\"plato-board-math\">")))
    (testing "a board without math renders no math block"
      (is (not (str/includes? (render value) "plato-board-math"))))
    (testing "a board's math turns KaTeX on without a flag"
      (is (html/needs-math? (deck-of with-math) {}))
      (is (not (html/needs-math? (deck-of value) {})))
      (is (str/includes? (html/deck->html (deck-of with-math)) "/vendor/plugin/math.js")))))

;; A figure: one configuration (n = 1). A, B free; P at infinity (a meet of
;; parallels); the check reads |AB|.
(def figure
  {:board/id :fig
   :board/kind :construction
   :board/kernel {:wasm "./vendor/boards/fig.wasm" :export "fig"}
   :board/label "a figure"
   :board/window {:x [-4 4] :y [-3 3] :n 1}
   :board/params [{:id :inpt0x :min -4 :max 4 :init 0 :control :point}]
   :board/outputs [:ax :ay :bx :by :px :py :ck]
   :board/layers [{:layer :segment :a [:ax :ay] :b [:bx :by]}
                  {:layer :line :a [:ax :ay] :b [:bx :by]}
                  {:layer :point :at [:bx :by] :label "B"}
                  {:layer :point :at [:px :py] :label "P"}
                  {:layer :handle :at [:ax :ay] :label "A" :drives {:x :inpt0x :y :inpt0y}}
                  {:layer :value :of :ck :label "|AB|"}]
   :board/probes {}
   :board/frame {:ax [0.0] :ay [0.0] :bx [3.0] :by [0.0] :px [##Inf] :py [1.0e300] :ck [3.0]}})

(deftest figure-layers-draw-one-configuration
  (let [html (render figure)]
    (is (board/board? figure) "n = 1 is a board")
    (testing "segment, extended line, labelled point and handle"
      (is (str/includes? html "class=\"segment\""))
      (is (str/includes? html "class=\"line\""))
      (is (str/includes? html ">B</text>"))
      (is (str/includes? html "class=\"handle\"")))
    (testing "a point at infinity is not drawn"
      (is (not (str/includes? html ">P</text>"))))))

;; ---- 3D: points named by three outputs, projected by the page --------------

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-12))

(deftest the-camera-projects-world-to-screen
  (testing "yaw 0, pitch 0: screen x is world x, screen y is world z, depth is world y"
    (let [cam (view/camera {:yaw 0 :pitch 0 :scale 1} {})]
      (is (= [1.0 3.0 2.0] (mapv double (cam 1 2 3))))))
  (testing "yaw and pitch read from the params by id; a quarter turn of yaw sends x to depth"
    (let [[sx sy d] ((view/camera {:yaw :yaw :pitch :pitch :scale 2} {:yaw (/ Math/PI 2) :pitch 0}) 1 0 0)]
      (is (close? 0 sx)) (is (close? 0 sy)) (is (close? 1 d))))
  (testing "perspective shrinks the far and swells the near"
    (let [cam (view/camera {:yaw 0 :pitch 0 :scale 1 :perspective 4} {})]
      (is (< (first (cam 1 2 0)) 1 (first (cam 1 -2 0)))))))

;; A tetrahedron's face and a vertex, seen from yaw 0, pitch 0.
(def solid
  {:board/id :solid
   :board/kind :construction
   :board/kernel {:wasm "./vendor/boards/solid.wasm" :export "solid"}
   :board/label "a solid"
   :board/window {:x [-3 3] :y [-3 3] :n 1 :axes :none}
   :board/view {:yaw :yaw :pitch 0 :scale 1}
   :board/params [{:id :yaw :min -3 :max 3 :init 0 :control :orbit :axis :yaw}]
   :board/outputs [:ax :ay :az :bx :by :bz :cx :cy :cz]
   :board/layers [{:layer :polygon :pts [[:ax :ay :az] [:bx :by :bz] [:cx :cy :cz]] :label "near"}
                  {:layer :point :at [:ax :ay :az] :label "far"}
                  {:layer :handle :at [:bx :by :bz] :along [[:ax :ay :az] [:cx :cy :cz]]
                   :drives {:s :s} :label "B"}]
   :board/probes {}
   :board/frame {:ax [0.0] :ay [2.0] :az [1.0] :bx [1.0] :by [-1.0] :bz [0.0]
                 :cx [-1.0] :cy [-1.0] :cz [0.0]}})

(deftest a-3d-board-is-projected-and-painted-far-to-near
  (let [arrays (into {} (map (fn [[k v]] [k (double-array v)])) (:board/frame solid))
        {:keys [layers arrays]} (view/project-board (:board/layers solid) arrays
                                                    (:board/view solid) {:yaw 0})
        [poly pt handle] layers]
    (testing "every point becomes a screen pair; drawn layers carry their depths"
      (is (= [[:ax-sx :ax-sy] [:bx-sx :bx-sy] [:cx-sx :cx-sy]] (:pts poly)))
      (is (= [:ax-d :bx-d :cx-d] (:depth poly)))
      (is (= [:ax-sx :ax-sy] (:at pt)))
      (is (= [0.0 1.0] (geom/point arrays (:at pt))) "A at (0, 2, 1) is at (0, 1) on screen"))
    (testing "a handle is projected but not painted by depth; its line is projected too"
      (is (nil? (:depth handle)))
      (is (= [[:ax-sx :ax-sy] [:cx-sx :cx-sy]] (:along handle))))
    (testing "the far point (depth 2) is painted before the near face (mean depth 0)"
      (is (= ["far" "near" "B"] (map :label (view/paint-order layers arrays))))))
  (testing "the static plot draws it, without plane axes"
    (let [html (render solid)]
      (is (str/includes? html "class=\"polygon\""))
      (is (str/includes? html ">far</text>"))
      (is (not (str/includes? html "class=\"axis\""))))))
