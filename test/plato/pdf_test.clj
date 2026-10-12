(ns plato.pdf-test
  "What `plato pdf` decides before a browser is involved: what counts as a page
   to print, where the PDF goes, what the printer is told, and what the build
   that precedes a source's print puts in the scratch directory. The printing
   itself needs Chromium and TeX, and is exercised by running the command."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [plato.cli :as cli]
            [plato.pdf :as pdf]))

(def markdown-source
  (str "---\ntitle: Smoke\n---\n\n"
       "# One\n\nText with **bold**.\n\nNote: say hello\n"))

(deftest parse-args-reads-the-pdf-command
  (is (= {:command :pdf :input "dist/talk/index.html" :opts {:out "talk.pdf"}}
         (cli/parse-args ["pdf" "dist/talk/index.html" "-o" "talk.pdf"])))
  (is (= {:no-transitions? true :no-compress? true :dpi "150" :engine "pdflatex" :keep? true}
         (:opts (cli/parse-args ["pdf" "a.md" "--no-transitions" "--no-compress"
                                 "--dpi" "150" "--engine" "pdflatex" "--keep"]))))
  (is (str/includes? (:error (cli/parse-args ["pdf"])) "needs a source path"))
  (is (str/includes? (:error (cli/parse-args ["pdf" "--deck" "a/b"])) "--deck needs --out")))

(deftest a-page-is-printed-and-a-source-is-built-first
  (is (pdf/built-page? "dist/talk/index.html"))
  (is (pdf/built-page? "talk.HTM"))
  (testing "a directory is a page: the printer looks for its index.html"
    (is (pdf/built-page? "src")))
  (is (not (pdf/built-page? "talk.md")))
  (is (not (pdf/built-page? "talk.org")))
  (testing "a --deck var has no input at all"
    (is (not (pdf/built-page? nil)))))

(deftest the-pdf-of-a-source-lands-beside-it
  (is (= "talk.pdf" (pdf/default-out "talk.md")))
  (is (= "docs/acme.pdf" (pdf/default-out "docs/acme.org")))
  (testing "a dot in a directory name is not an extension"
    (is (= "v1.2/talk.pdf" (pdf/default-out "v1.2/talk")))))

(deftest the-printer-is-told-only-what-was-asked
  (is (= ["p.html"] (pdf/printer-args "p.html" nil {})))
  (is (= ["p.html" "-o" "out.pdf"] (pdf/printer-args "p.html" "out.pdf" {})))
  (is (= ["p.html" "-o" "out.pdf" "--no-transitions" "--no-compress"
          "--dpi" "150" "--engine" "pdflatex" "--keep"]
         (pdf/printer-args "p.html" "out.pdf"
                           {:no-transitions? true :no-compress? true
                            :dpi "150" :engine "pdflatex" :keep? true})))
  (testing "build options are the build's business, not the printer's"
    (is (= ["p.html"] (pdf/printer-args "p.html" nil {:tokens "t.edn" :assets "public" :fit? true})))))

(deftest a-source-is-built-into-the-scratch-directory-with-its-pictures
  (let [source (java.io.File/createTempFile "plato-pdf-test" ".md")]
    (try
      (spit source markdown-source)
      (let [job (pdf/page-job (.getPath source) {:assets "public" :out "talk.pdf"} "/scratch")]
        (is (= "/scratch/index.html" (:page job)))
        (testing "--out names the PDF, so the page does not go there"
          (is (= ["/scratch/index.html"] (mapv :path (:files job)))))
        (is (str/includes? (:content (first (:files job))) "<section"))
        (testing "build copies vendor/ and css/; pdf adds the assets/ a printed page needs"
          (is (= [{:from "public/vendor" :to "/scratch/vendor"}
                  {:from "public/css" :to "/scratch/css"}
                  {:from "public/assets" :to "/scratch/assets"}]
                 (:copies job)))))
      (testing "without --assets nothing is copied, as for build"
        (is (= [] (:copies (pdf/page-job (.getPath source) {} "/scratch")))))
      (finally (.delete source)))))

(deftest the-printer-ships-on-the-classpath
  (testing "a repository that depends on plato can run `plato pdf` without a checkout"
    (is (some? (io/resource pdf/script-resource)))))
