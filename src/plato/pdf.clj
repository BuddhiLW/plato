(ns plato.pdf
  "The CLI's `pdf`: a deck as a PDF that looks like the browser deck.

   The printing itself is `resources/plato/pdf.mjs`, a Node script, because the
   one thing that renders a Reveal page exactly as a browser does is a browser,
   and Playwright is how one is driven. This namespace is the part that makes it
   a plato command: it finds that script on the classpath, so the command works
   from any repository that depends on plato, and it builds the page first when
   handed a source rather than a page, so one command goes from `talk.md` or a
   deck var to `talk.pdf`.

   JVM only, like `serve`: it starts a process."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [plato.cli :as cli])
  (:import (java.io File IOException)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def script-resource
  "Classpath name of the printer. Under resources/, so it travels with plato
   whether plato is a checkout, a git dependency or a jar."
  "plato/pdf.mjs")

(defn built-page?
  "Is `input` a page to print as it stands, rather than a source to build
   first? An .html file, or a directory holding an index.html."
  [input]
  (boolean
   (and input
        (or (re-find #"(?i)\.html?$" input)
            (.isDirectory (io/file input))))))

(defn default-out
  "Where the PDF of a source goes when --out does not say: beside the source."
  [input]
  (str (str/replace (str input) #"\.[^./]+$" "") ".pdf"))

(defn printer-args
  "The printer's own argv for `page`. `out` may be nil, and then the printer
   writes beside the page."
  [page out opts]
  (cond-> [page]
    out (into ["-o" out])
    (:no-transitions? opts) (conj "--no-transitions")
    (:no-compress? opts) (conj "--no-compress")
    (:dpi opts) (into ["--dpi" (str (:dpi opts))])
    (:engine opts) (into ["--engine" (:engine opts)])
    (:keep? opts) (conj "--keep")))

(defn page-job
  "The build that puts a source's page in `dir`, ready to print: build-job's
   own files and copies, plus the assets/ tree of --assets. `build` leaves that
   tree to whoever publishes the page; a page printed where it was built has
   nobody else to bring its pictures."
  [input opts dir]
  (let [page (str dir "/index.html")
        job (cli/build-job (cli/deck-job input (-> opts (assoc :out page) (dissoc :print?))))]
    (cond-> (assoc job :page page)
      (:assets opts)
      (update :copies conj {:from (str (:assets opts) "/assets") :to (str dir "/assets")}))))

(defn- script-path
  "A path Node can run. A resource in a directory is run where it lies, so a
   node_modules beside a plato checkout is found; one inside a jar is copied out."
  []
  (let [url (or (io/resource script-resource)
                (throw (ex-info (str "plato's PDF printer is not on the classpath: " script-resource)
                                {:resource script-resource})))]
    (if (= "file" (.getProtocol url))
      (.getPath (io/file url))
      (let [tmp (File/createTempFile "plato-pdf-" ".mjs")]
        (.deleteOnExit tmp)
        (with-open [in (io/input-stream url)] (io/copy in tmp))
        (.getPath tmp)))))

(defn- print!
  "Run the printer on `args`, its output on this process's own streams."
  [args]
  (let [command (into ["node" (script-path)] args)
        process (try (-> (ProcessBuilder. ^java.util.List command) .inheritIO .start)
                     (catch IOException e
                       (throw (ex-info (str "pdf needs Node on the PATH to drive a browser: " (ex-message e))
                                       {:command command}))))
        code (.waitFor process)]
    (when-not (zero? code)
      (throw (ex-info (str "the PDF printer failed (exit " code ")") {:exit code})))))

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(defn pdf!
  "The CLI's `pdf`. A built page is printed as it stands; a source, or the deck
   a --deck var holds, is built into a scratch directory first and printed from
   there. Returns a job with nothing left to write: the printer has written the
   PDF and said so."
  [{:keys [input opts]}]
  (if (built-page? input)
    (print! (printer-args input (:out opts) opts))
    (let [dir (.toFile (Files/createTempDirectory "plato-pdf-page-" (make-array FileAttribute 0)))
          out (or (:out opts) (default-out input))]
      (try
        (let [{:keys [files copies page]} (page-job input opts (.getPath dir))]
          (doseq [{:keys [path content]} files] (cli/write-file! path content))
          (doseq [{:keys [from to]} copies] (cli/copy-tree! from to))
          (print! (printer-args page out opts)))
        (finally
          (if (:keep? opts)
            (println (str "page directory: " (.getPath dir)))
            (delete-tree! dir))))))
  {:files [] :copies []})
