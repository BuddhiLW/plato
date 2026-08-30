(ns plato.cli
  "Command line: render a deck source into a standalone Reveal page, and
   generate theme artifacts from a token source.

   Argument parsing and job execution are pure; only `-main` touches the
   filesystem, stdout and the exit code.

   Which source formats exist is not decided here — `plato.markdown`,
   `plato.org` and `plato.data` are required so that the `plato.source` methods
   they install are present, and a front end outside plato joins the CLI the
   same way."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [plato.data]
            [plato.deck :as deck]
            [plato.html :as html]
            [plato.markdown]
            [plato.org]
            [plato.source :as source]
            [plato.tokens :as tokens]
            #?(:clj [clojure.java.io :as io])
            [plato.json :as json]
            [plato.spec :as spec]))

(def usage
  (str/join
   "\n"
   ["plato — data-driven presentations"
    ""
    "  plato build <source.md|source.org> [options]"
    "  plato build --deck <ns/var> --out <path> [options]"
    "  plato spec  <source.md|source.org> [options]"
    "  plato theme <tokens.edn> [options]"
    "  plato help | version"
    ""
    "build options"
    "  -o, --out <path>        output HTML (default: the source with a .html suffix)"
    "      --deck <ns/var>     build the deck a var holds instead of a source file"
    "      --title <string>    page title (default: the deck title)"
    "      --theme <name>      Reveal theme name (default: night)"
    "      --theme-css <path>  extra stylesheet to link, as authored"
    "      --tokens <path>     token source: generates a theme CSS beside the output and links it"
    "      --asset-base <path> prefix for vendor/ and css/ links (default: .)"
    "      --assets <dir>      copy vendor/ and css/ from <dir> beside the page"
    "      --math              load the math plugin (it fetches KaTeX from a CDN)"
    "      --fit               load plato.fit so the page can report whether its"
    "                          slides fit; implied by any {:overflow :shrink} slide"
    "      --print             write the page to stdout instead of a file"
    ""
    "spec options — emit an AutoPDF DocumentSpec, for a Beamer PDF of the same source"
    "  -o, --out <path>        output JSON (default: the source with a .json suffix)"
    "      --title <string>    document id (default: the deck title)"
    "      --theme <name>      theme name carried to the renderer"
    "      --print             write the JSON to stdout instead of a file"
    ""
    "theme options"
    "  -o, --out <path>        output CSS (default: the source with a .css suffix)"
    "      --json <path>       also write the language-neutral manifest"
    "      --cljc <path>       also write the tokens as a Clojure namespace"
    "      --sty <path>        also write a Beamer style file, for autopdf deck"
    "      --ns <symbol>       namespace for --cljc (default: plato.theme)"
    "      --print             write the CSS to stdout instead of a file"]))

(def flags
  "Long/short option -> [key arity]. Arity 0 options are booleans."
  {"-o" [:out 1] "--out" [:out 1]
   "--deck" [:deck 1]
   "--title" [:title 1]
   "--theme" [:theme 1]
   "--theme-css" [:theme-css 1]
   "--tokens" [:tokens 1]
   "--asset-base" [:asset-base 1]
   "--assets" [:assets 1]
   "--math" [:math? 0]
   "--fit" [:fit? 0]
   "--json" [:json 1]
   "--cljc" [:cljc 1]
   "--sty" [:sty 1]
   "--ns" [:ns 1]
   "--print" [:print? 0]})

(def commands
  "Bare words that select what the CLI does."
  {"build" :build "theme" :theme "spec" :spec
   "help" :help "-h" :help "--help" :help
   "version" :version "-v" :version "--version" :version})

(defn parse-args
  "argv -> {:command keyword :input string :opts map} or {:error string}."
  [args]
  (loop [[arg & more] (seq args)
         acc {:opts {}}
         positional []]
    (cond
      (nil? arg)
      (let [[command input extra] positional
            kind (get commands command)]
        (cond
          (nil? command) (assoc acc :command :help)
          (some? extra) {:error (str "Unexpected argument: " extra)}
          (nil? kind) {:error (str "Unknown command: " command)}
          (and (= :build kind) (nil? input) (nil? (get-in acc [:opts :deck])))
          {:error "Command build needs a source path or --deck <ns/var>"}
          (and (= :build kind) (get-in acc [:opts :deck]) (nil? (get-in acc [:opts :out]))
               (not (get-in acc [:opts :print?])))
          {:error "--deck needs --out <path> or --print"}
          (and (= :theme kind) (nil? input))
          {:error "Command theme needs a source path"}
          :else (cond-> (assoc acc :command kind)
                  input (assoc :input input))))

      (contains? flags arg)
      (let [[k arity] (get flags arg)]
        (if (zero? arity)
          (recur more (assoc-in acc [:opts k] true) positional)
          (if (nil? (first more))
            {:error (str "Option " arg " needs a value")}
            (recur (rest more) (assoc-in acc [:opts k] (first more)) positional))))

      (contains? commands arg) (recur more acc (conj positional arg))

      (str/starts-with? arg "-")
      {:error (str "Unknown option: " arg)}

      :else (recur more acc (conj positional arg)))))

(defn- strip-extension [path]
  (let [i (str/last-index-of (str path) ".")
        slash (or (str/last-index-of (str path) "/") -1)]
    (if (and i (> i slash)) (subs (str path) 0 i) (str path))))

(defn- file-name [path]
  (let [slash (str/last-index-of (str path) "/")]
    (if slash (subs (str path) (inc slash)) (str path))))

(defn- sibling
  "Path of `name` in the same directory as `path`."
  [path name]
  (let [slash (str/last-index-of (str path) "/")]
    (if slash (str (subs (str path) 0 (inc slash)) name) name)))

(defn theme-css-name
  "Token source path -> the stylesheet file name generated beside a page."
  [path]
  (let [base (file-name path)
        dot (str/index-of base ".")]
    (str (if dot (subs base 0 dot) base) "-theme.css")))

(def asset-roots
  "Directory names an exported page's :asset-base resolves to. `--assets <dir>`
   copies these from <dir>, which is what makes the page standalone: without
   them it links a vendor/ and css/ tree it does not carry."
  ["vendor" "css"])

(defn build-job
  "Pure build. Takes the already-read sources; returns
   {:files [{:path :content}] :copies [{:from :to}] :stdout string-or-nil
    :deck deck :summary string}.

   job: {:input path :text source-text :model deck :opts {...} :tokens-text edn-string}
   `:model` wins over `:text`; otherwise the source kind comes from `:input`."
  [{:keys [input text model opts tokens-text token-maps]}]
  (let [kind (when-not model (source/kind input))
        _ (when (and (not model) (nil? kind))
            (throw (ex-info (str "Unsupported source extension: " input)
                            {:input input
                             :known (sort (keys (source/known-extensions)))})))
        model (or model (source/->deck kind text))
        out (or (:out opts) (str (strip-extension input) ".html"))
        theme-tokens (cond
                       (seq token-maps) (tokens/assert-tokens! (tokens/compose token-maps))
                       tokens-text (tokens/assert-tokens!
                                    (tokens/parse (:tokens opts) tokens-text)))
        sheet (when theme-tokens (theme-css-name (:tokens opts)))
        sheets (vec (remove nil? [(:theme-css opts) sheet]))
        page (html/deck->html
              model
              (cond-> {:stylesheets sheets}
                (:title opts) (assoc :title (:title opts))
                (:math? opts) (assoc :math? true)
                (:fit? opts) (assoc :fit? true)
                (:asset-base opts) (assoc :asset-base (:asset-base opts))
                (or (:theme opts) (get-in theme-tokens [:meta :reveal-theme]))
                (assoc :theme (or (:theme opts)
                                  (get-in theme-tokens [:meta :reveal-theme])))))
        files (cond-> []
                theme-tokens (conj {:path (sibling out sheet)
                                    :content (tokens/css theme-tokens (:tokens opts))})
                (not (:print? opts)) (conj {:path out :content page}))
        copies (when (and (:assets opts) (not (:print? opts)))
                 (mapv (fn [root] {:from (str (:assets opts) "/" root)
                                   :to (sibling out root)})
                       asset-roots))]
    {:files files
     :copies (vec copies)
     :stdout (when (:print? opts) page)
     :deck model
     :summary (str/join
               "\n"
               (concat
                (map (fn [{:keys [path content]}]
                       (str "wrote " path " (" (count content) " bytes"
                            (when (= path out)
                              (str ", " (count (deck/leaf-slides model)) " slides"))
                            ")"))
                     files)
                (map (fn [{:keys [from to]}] (str "copied " from " -> " to))
                     copies)))}))

(defn theme-job
  "Pure theme generation.

   job: {:input path :text source-text :token-maps [base ... child] :opts {...}}
   `:token-maps` is the resolved :extends chain, base first; it wins over
   `:text`, which is the single-file case."
  [{:keys [input text opts token-maps]}]
  (let [tk (tokens/assert-tokens! (if (seq token-maps)
                                    (tokens/compose token-maps)
                                    (tokens/parse input text)))
        out (or (:out opts) (str (strip-extension input) ".css"))
        css (tokens/css tk input)
        ns-sym (symbol (or (:ns opts) "plato.theme"))
        files (cond-> []
                (not (:print? opts)) (conj {:path out :content css})
                (:json opts) (conj {:path (:json opts) :content (tokens/json tk)})
                (:cljc opts) (conj {:path (:cljc opts)
                                    :content (tokens/cljc tk ns-sym input)})
                (:sty opts) (conj {:path (:sty opts)
                                   :content (tokens/sty tk
                                                        (strip-extension (file-name (:sty opts)))
                                                        input)}))]
    {:files files
     :stdout (when (:print? opts) css)
     :summary (str/join "\n"
                        (map (fn [{:keys [path content]}]
                               (str "wrote " path " (" (count content) " bytes)"))
                             files))}))

(defn spec-job
  "Pure DocumentSpec projection, for AutoPDF. Takes the already-read source;
   returns the same {:files :stdout :summary} shape as the other jobs.

   job: {:input path :text source-text :opts {...}}

   Throws for a source kind with no document IR — see plato.source/->document."
  [{:keys [input text opts]}]
  (let [kind (source/kind input)
        _ (when (nil? kind)
            (throw (ex-info (str "Unsupported source extension: " input)
                            {:input input
                             :known (sort (keys (source/known-extensions)))})))
        document (source/->document kind text)
        value (spec/document->spec document
                                   (cond-> {}
                                     (:title opts) (assoc :id (:title opts))
                                     (:theme opts) (assoc :theme (:theme opts))))
        out (or (:out opts) (str (strip-extension input) ".json"))
        payload (json/write value {:key-fn json/camel-key :indent 2})
        frames (count (:blocks value))]
    {:files (if (:print? opts) [] [{:path out :content payload}])
     :copies []
     :stdout (when (:print? opts) payload)
     :spec value
     :summary (if (:print? opts)
                ""
                (str "wrote " out " (" (count payload) " bytes, " frames " frames)"))}))

(def version "0.2.0")

;; ── I/O boundary ────────────────────────────────────────────────────────────

(defn- read-source [path]
  #?(:cljs nil
     :default (slurp path)))

(defn- write-file! [path content]
  #?(:cljs nil
     :default (do (when-let [parent (.getParentFile (java.io.File. ^String path))]
                    (.mkdirs parent))
                  (spit path content))))

(defn deck-from-var
  "\"ns/var\" -> the validated deck it holds. A var holding a 0-arg fn is
   called. The value is put through deck/deck here rather than downstream, so a
   var that holds something else fails by name instead of deep in the renderer.

   Deliberately NOT a plato.source method. That multimethod is an open set of
   TEXT FORMATS: it is handed a file's contents and dispatches on the kind its
   extension names, so a format joins by parsing a string. A var reference is
   neither a file nor text — it is a classpath lookup — and giving it a :var
   kind whose \"text\" is a symbol would make the multimethod's contract a lie
   for the one member that does not honour it.

   It is also the only conversion with a runtime it cannot serve: resolving a
   var needs namespaces loaded from a classpath, and the native binary carries
   plato's own and nothing else. A source method that silently fails on one of
   three runtimes would be worse than a flag that is honest about what it is."
  [reference]
  #?(:cljs
     (throw (ex-info (str "--deck resolves a var from the classpath, which this "
                          "runtime has no access to — build from a file instead")
                     {:deck reference}))
     :default
     (let [sym (symbol reference)
           _ (when-not (namespace sym)
               (throw (ex-info (str "--deck needs a namespaced var: " reference)
                               {:deck reference})))
           v (try (requiring-resolve sym)
                  (catch Exception e
                    (throw (ex-info (str "Cannot load " reference ": " (ex-message e))
                                    {:deck reference}))))
           _ (when-not v
               (throw (ex-info (str "Cannot resolve deck var: " reference)
                               {:deck reference})))
           value (deref v)
           value (if (fn? value) (value) value)]
       (when-not (map? value)
         (throw (ex-info (str "Deck var " reference " does not hold a deck")
                         {:deck reference})))
       (deck/deck value))))

(defn- relative-to
  "`other` resolved against the file `path`, unless it is already absolute."
  [path other]
  (if (str/starts-with? (str other) "/") (str other) (sibling path other)))

(defn- token-chain
  "Read the theme at `path` and every theme it :extends, base first. A relative
   :extends resolves against the file that declares it."
  [path]
  (loop [path path seen #{} acc ()]
    (when (contains? seen path)
      (throw (ex-info (str "Theme :extends cycle at " path) {:path path})))
    (let [tk (tokens/parse path (read-source path))
          acc (conj acc tk)]
      (if-let [parent (:extends tk)]
        (recur (relative-to path parent) (conj seen path) acc)
        (vec acc)))))

(defn- copy-tree!
  "Copy the file tree at `from` to `to`, creating directories. A missing source
   is skipped rather than fatal: a page without vendored assets is still a page,
   and the summary already says what was copied.

   `tree-seq` and `clojure.java.io/copy` are the portable spelling: the JVM's
   `file-seq` and `java.nio.file.Files` are absent from the native runtime, and
   `io/copy` is byte-exact on both, so a vendored font survives the copy."
  [from to]
  #?(:cljs nil
     :default
     (let [^java.io.File src (java.io.File. ^String from)
           prefix (count (.getPath src))
           dir? (fn [^java.io.File f] (.isDirectory f))]
       (when (dir? src)
         (doseq [^java.io.File f (tree-seq dir? #(seq (.listFiles ^java.io.File %)) src)
                 :when (.isFile f)]
           (let [^java.io.File dest (java.io.File. (str to (subs (.getPath f) prefix)))]
             (when-let [^java.io.File parent (.getParentFile dest)] (.mkdirs parent))
             (io/copy f dest)))))))

(defn- run-job
  "Read the sources a parsed command needs, run the pure job, write its files."
  [{:keys [command input opts]}]
  (let [job (case command
              :build (build-job {:input input
                                 :text (when-not (:deck opts) (read-source input))
                                 :model (when (:deck opts) (deck-from-var (:deck opts)))
                                 :opts opts
                                 :token-maps (when (:tokens opts)
                                               (token-chain (:tokens opts)))})
              :spec (spec-job {:input input :text (read-source input) :opts opts})
              :theme (theme-job {:input input :token-maps (token-chain input) :opts opts}))]
    (doseq [{:keys [path content]} (:files job)]
      (write-file! path content))
    (doseq [{:keys [from to]} (:copies job)]
      (copy-tree! from to))
    (when-let [out (:stdout job)] (println out))
    (when (seq (:summary job)) (println (:summary job)))
    0))

(defn -main [& args]
  (let [{:keys [command error] :as parsed} (parse-args args)]
    (cond
      error (binding [*out* *err*]
              (println error)
              (println)
              (println usage)
              #?(:cljs nil :default (System/exit 2)))
      (= :help command) (println usage)
      (= :version command) (println (str "plato " version))
      :else
      (let [code (try
                   (run-job parsed)
                   (catch #?(:cljs :default :default Exception) e
                     (binding [*out* *err*]
                       (println (str "plato: " (or (ex-message e) e))))
                     1))]
        (when (pos? code)
          #?(:cljs nil :default (System/exit code)))))))
