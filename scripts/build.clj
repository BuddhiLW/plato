(ns build
  "The site build, as a namespace the CLI's own runtimes run:

     cljw -cp src:examples:resources:scripts -m build [assets|site]
     clojure -M:dev -m build [assets|site]

   `assets` vendors the reveal.js dist into public/vendor. `site` prerenders
   the two site decks through plato.html and copies the vendor, css and asset
   trees beside them, into dist/site — the tree GitHub Pages publishes. With
   no argument it does both. It never spawns anything: the shadow-cljs bundles
   the pages link (plato-fit, plato-scene) are built by `bb bundles` before
   `site` runs, and bb.edn is where that order lives."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [plato.acme.deck :as acme]
            [plato.cli :as cli]
            [plato.example.deck :as site]
            [plato.html :as html]))

(def reveal-dist "node_modules/reveal.js/dist")

(def vendor-files
  "reveal.js dist path -> public/vendor path. reveal.js 6 ships reset.css
   separately from reveal.css; without it the page inherits the browser's
   default margins. A plugin plato builds itself (one with a :src) is not
   vendored from the dist."
  (into [["reset.css" "reset.css"]
         ["reveal.css" "reveal.css"]
         ["theme/night.css" "theme/night.css"]
         ["plugin/highlight/monokai.css" "highlight/monokai.css"]
         ["reveal.js" "reveal.js"]]
        (comp (remove :src)
              (map (fn [{:keys [file]}]
                     [(str "plugin/" file ".js") (str "plugin/" file ".js")])))
        html/plugins))

(def katex-dist "node_modules/katex/dist")

(defn katex-files
  "katex dist path -> public/vendor/katex/dist path: the script, the sheet,
   the two extensions the Reveal plugin can load, and the woff2 faces. The
   sheet names woff2 first, so a browser that takes it never asks for the
   woff or ttf beside it, and those stay out."
  []
  (into [["katex.min.js" "katex.min.js"]
         ["katex.min.css" "katex.min.css"]
         ["contrib/auto-render.min.js" "contrib/auto-render.min.js"]
         ["contrib/mhchem.min.js" "contrib/mhchem.min.js"]]
        (comp (map (fn [^java.io.File f] (.getName f)))
              (filter #(str/ends-with? % ".woff2"))
              (map (fn [n] [(str "fonts/" n) (str "fonts/" n)])))
        (sort-by (fn [^java.io.File f] (.getName f))
                 (.listFiles (io/file (str katex-dist "/fonts"))))))

(defn- copy-file! [from to]
  (let [dest (io/file to)]
    (when-let [parent (.getParentFile dest)] (.mkdirs parent))
    (io/copy (io/file from) dest)))

(defn- strip-font-imports
  "The Reveal theme @imports its two families from Google Fonts, a chained,
   render-blocking request on every visit. plato self-hosts those faces from
   public/fonts through css/plato.css, so the import goes."
  [css]
  (str/replace css #"@import\s+(?:url\()?[\"']?https://fonts\.googleapis\.com[^;]*;\s*" ""))

(defn- delete-tree!
  "Remove the file tree at `path`, children first. `tree-seq` + `File.delete`,
   the same primitives copy-tree! uses, so the native runtime has them too."
  [path]
  (let [root (io/file path)
        dir? (fn [^java.io.File f] (.isDirectory f))]
    (when (.exists root)
      (doseq [^java.io.File f (reverse (tree-seq dir? #(seq (.listFiles ^java.io.File %)) root))]
        (.delete f)))))

(defn assets! []
  (doseq [[from to] vendor-files]
    (copy-file! (str reveal-dist "/" from) (str "public/vendor/" to)))
  (let [theme "public/vendor/theme/night.css"]
    (cli/write-file! theme (strip-font-imports (slurp (str reveal-dist "/theme/night.css")))))
  (println (str "vendored " (count vendor-files) " reveal.js files into public/vendor"))
  (let [katex (katex-files)]
    (doseq [[from to] katex]
      (copy-file! (str katex-dist "/" from) (str "public/vendor/katex/dist/" to)))
    (println (str "vendored " (count katex) " KaTeX files into public/vendor/katex"))))

(def out-dir "dist/site")

(def site-opts
  "Every site page: assets beside the page and live scenes. The fit gate is
   NOT shipped: it is a build check, and the browser suite injects the bundle
   into the page it drives. A deck that declares {:overflow :shrink} still
   carries it, because that slide needs it to render."
  {:asset-base "." :live-scenes? true})

(def acme-backlink
  [[:style
    (str ".plato-backlink{position:fixed;left:0.9rem;bottom:0.75rem;z-index:40;"
         "font:500 0.72rem/1 var(--plato-sans, system-ui, sans-serif);"
         "letter-spacing:0.06em;text-transform:uppercase;"
         "color:var(--plato-muted, #a89e91);text-decoration:none;opacity:0.55}"
         ".plato-backlink:hover,.plato-backlink:focus-visible"
         "{color:var(--plato-accent, #f59e0b);opacity:1}")]
   [:a.plato-backlink {:href "./index.html"} "← Plato engine example"]])

(def pages
  [{:path "index.html" :deck site/model :opts site-opts}
   {:path "acme.html" :deck acme/model
    :opts (assoc site-opts
                 :stylesheets ["./css/acme-theme.css"]
                 :after-slides acme-backlink)}])

(defn site! []
  ;; A fresh tree every time: a file that stopped being linked must not ship
  ;; from a previous build's copy.
  (delete-tree! out-dir)
  (doseq [root ["vendor" "css" "fonts" "assets"]]
    (cli/copy-tree! (str "public/" root) (str out-dir "/" root)))
  ;; plato.css @imports the generated token sheet, which in the dev tree keeps
  ;; the two files separately editable. On the shipped page that import is one
  ;; more render-blocking round trip after plato.css lands, so the sheet is
  ;; inlined where the import was: same bytes, one request fewer.
  (let [css (str out-dir "/css/plato.css")
        theme (slurp (str out-dir "/css/plato-theme.css"))]
    (cli/write-file! css (str/replace (slurp css) "@import \"./plato-theme.css\";" theme)))
  (doseq [{:keys [path deck opts]} pages]
    (let [out (str out-dir "/" path)
          page (html/deck->html deck opts)]
      (cli/write-file! out page)
      (println (str "wrote " out " (" (count page) " bytes)")))))

(defn -main [& [command]]
  (case command
    "assets" (assets!)
    "site" (site!)
    nil (do (assets!) (site!))
    (throw (ex-info (str "unknown build command: " command) {:command command}))))
