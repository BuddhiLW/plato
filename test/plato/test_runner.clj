(ns plato.test-runner
  "Runs every *_test namespace under the test root.

   The list is DISCOVERED, never written down. A hand-kept list silently skips
   a test file nobody remembered to add to it — which is the one failure mode a
   test runner must not have, because it reads as green."
  (:require [clojure.string :as str]
            [clojure.test :as test])
  (:import (java.io File)))

(def test-root "test")

(defn path->ns
  "Test-root-relative source path -> its namespace symbol."
  [relative-path]
  (-> relative-path
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace File/separator ".")
      symbol))

(defn test-namespaces
  "Every test namespace on disk, in a stable order."
  []
  (let [root (File. test-root)
        prefix (inc (count (.getPath root)))]
    (->> (file-seq root)
         (filter #(.isFile ^File %))
         (map #(.getPath ^File %))
         (filter #(str/ends-with? % "_test.clj"))
         (map #(path->ns (subs % prefix)))
         sort
         vec)))

(defn -main [& _]
  (let [nses (test-namespaces)]
    (when (empty? nses)
      (binding [*out* *err*]
        (println "No test namespaces found under" test-root))
      (System/exit 1))
    (println "Running" (count nses) "test namespaces")
    (apply require nses)
    (let [{:keys [fail error]} (apply test/run-tests nses)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))
