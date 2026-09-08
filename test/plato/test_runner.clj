(ns plato.test-runner
  "Runs every *_test namespace under the test root.

   The list is DISCOVERED, never written down. A hand-kept list silently skips
   a test file nobody remembered to add to it — which is the one failure mode a
   test runner must not have, because it reads as green."
  (:require [clojure.string :as str]
            [clojure.test :as test])
  (:import (java.io File)))

(def default-roots
  "Source roots scanned when `-main` is given none."
  ["test"])

(defn path->ns
  "Test-root-relative source path -> its namespace symbol."
  [relative-path]
  (-> relative-path
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace File/separator ".")
      symbol))

(defn test-namespaces
  "Every test namespace on disk under `roots`, in a stable order."
  ([] (test-namespaces default-roots))
  ([roots]
   (->> roots
        (mapcat (fn [root]
                  (let [dir (File. ^String root)
                        prefix (inc (count (.getPath dir)))]
                    (->> (file-seq dir)
                         (filter #(.isFile ^File %))
                         (map #(.getPath ^File %))
                         (filter #(str/ends-with? % "_test.clj"))
                         (map #(path->ns (subs % prefix)))))))
        sort
        vec)))

(defn -main
  "Run every discovered test namespace under `roots`, or under `default-roots`.

   Roots are arguments rather than a constant so a suite whose dependencies the
   library itself does not carry -- the fit gate, which needs hive-cljs -- can
   live in its own root and be selected by an alias, without the default suite
   ever trying to load it."
  [& roots]
  (let [roots (if (seq roots) (vec roots) default-roots)
        nses (test-namespaces roots)]
    (when (empty? nses)
      (binding [*out* *err*]
        (println "No test namespaces found under" (str/join ", " roots)))
      (System/exit 1))
    (println "Running" (count nses) "test namespaces from" (str/join ", " roots))
    (apply require nses)
    (let [{:keys [fail error]} (apply test/run-tests nses)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))
