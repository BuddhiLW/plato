(ns plato.serve-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.cli :as cli]
            [plato.serve :as serve]))

(def source
  "---\ntitle: Live\n---\n\n# One\n\nHello.\n\n- a\n- b\n\n## Two\n\nMore.\n")

(defn- temp-source [text]
  (let [f (io/file "target" "serve-test" (str (gensym "deck") ".md"))]
    (io/make-parents f)
    (spit f text)
    (.getPath f)))

(deftest routing
  (let [pages {"index.html" (constantly "<p>deck</p>")
               "acme-theme.css" (constantly ":root{}")}
        route #(serve/respond pages "public" %)]
    (testing "the root and index.html are the rendered page"
      (is (= {:status 200 :type "text/html; charset=utf-8" :body "<p>deck</p>"} (route "/")))
      (is (= 200 (:status (route "/index.html"))))
      (is (= 200 (:status (route "/?slide=3"))) "a query string is not part of the name")
      (is (= "text/css" (:type (route "/acme-theme.css"))) "a generated sheet is typed as one"))
    (testing "everything else is a file under the asset root, typed by extension"
      (let [r (route "/css/plato.css")]
        (is (= 200 (:status r)))
        (is (= "text/css" (:type r)))
        (is (instance? java.io.File (:body r))))
      (is (= 404 (:status (route "/css/nothing.css"))))
      (is (= 404 (:status (route "/css/")))))
    (testing "a path climbing above the root is refused, not resolved"
      (is (= 403 (:status (route "/../deps.edn"))))
      (is (= 403 (:status (route "/css/../../deps.edn")))))))

(deftest content-types
  (is (= "text/javascript" (serve/content-type "vendor/reveal.js")))
  (is (= "font/woff2" (serve/content-type "css/fonts/open-sans.woff2")))
  (is (= "application/octet-stream" (serve/content-type "thing.unknown"))))

(deftest pages-render-from-the-source-on-every-call
  (let [path (temp-source source)
        pages (serve/pages {:input path :opts {}})
        page ((get pages "index.html"))]
    (is (= ["index.html"] (keys pages)))
    (is (str/includes? page "<div class=\"reveal\">"))
    (is (str/includes? page "<h1>One</h1>"))
    (is (str/includes? page serve/reload-js) "the page listens for the reload event")
    (testing "editing the source changes the next render"
      (spit path (str/replace source "Hello." "Changed."))
      (is (str/includes? ((get pages "index.html")) "Changed.")))
    (testing "a source that does not build shows the error and keeps listening"
      (spit path "# One\n\n<!-- .slide: data-background-color=\"#000\" -->\n\n#")
      (let [broken (serve/pages {:input "target/serve-test/none.edn" :opts {}})
            page ((get broken "index.html"))]
        (is (str/includes? page "did not build"))
        (is (str/includes? page serve/reload-js))))))

(deftest hyperframes-pages
  (let [path (temp-source source)
        pages (serve/pages {:input path :opts {} :hyperframes? true})]
    (is (= #{"index.html" "present.html"} (set (keys pages))))
    (is (str/includes? ((get pages "index.html")) "data-composition-id=\"main\""))
    (let [presenter ((get pages "present.html"))]
      (is (str/includes? presenter "<hyperframes-player interactive=\"\" src=\"index.html\">"))
      (is (str/includes? presenter serve/reload-js)))))

(deftest a-token-theme-is-served-beside-the-page
  (let [path (temp-source source)
        pages (serve/pages {:input path :opts {:tokens "theme/acme.tokens.edn"}})]
    (is (contains? pages "acme-theme.css"))
    (is (str/includes? ((get pages "acme-theme.css")) "--plato-"))
    (is (str/includes? ((get pages "index.html")) "href=\"acme-theme.css\""))))

(deftest watched-files
  (is (= ["talk.md" "theme/x.edn"] (serve/watched {:input "talk.md" :opts {:tokens "theme/x.edn"}})))
  (is (= ["talk.md"] (serve/watched {:input "talk.md" :opts {}})))
  (is (number? (serve/stamp ["deps.edn" "does-not-exist"]))))

(deftest the-cli-knows-serve
  (is (= {:command :serve :input "a.md" :opts {:port "8091" :hyperframes? true}}
         (cli/parse-args ["serve" "a.md" "--port" "8091" "--hyperframes"])))
  (is (= {:command :serve :opts {:deck "a/b"}}
         (cli/parse-args ["serve" "--deck" "a/b"]))
      "a served --deck needs no --out")
  (is (str/includes? (:error (cli/parse-args ["serve"])) "needs a source path")))
