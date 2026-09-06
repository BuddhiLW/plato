(ns plato.reveal
  (:require [plato.json :as json]
            ["reveal.js" :as RevealModule]
            ["reveal.js/plugin/markdown" :as MarkdownModule]
            ["reveal.js/plugin/notes" :as NotesModule]
            ["reveal.js/plugin/math" :as MathModule]
            ["reveal.js/plugin/search" :as SearchModule]
            ["reveal.js/plugin/zoom" :as ZoomModule]
            [plato.highlight :as highlight]
            [plato.html :as html]))

(defn- default-export [module]
  (or (.-default module) module))

(def plugin-modules
  "plato.html/plugins :file -> the plugin module. The exporter's table is the
   ONE definition of which plugins exist and which are optional; this is only
   its projection onto the modules shadow bundled, so a plugin cannot be
   registered here that the static page would not load, or gated differently
   on the two render targets. highlight is plato's own slim build, the same
   namespace the :highlight bundle compiles for a prerendered page."
  {"markdown" MarkdownModule
   "highlight" highlight/plugin
   "notes" NotesModule
   "math" MathModule
   "search" SearchModule
   "zoom" ZoomModule})

(defn plugins-for
  "The plugin objects `opts` enables, in the exporter's load order. RevealMath
   is a plugin object carrying its variants; KaTeX is the one the exporter
   selects, so the live shell selects the same."
  [opts]
  (into-array
   (map (fn [{:keys [file]}]
          (let [plugin (default-export (get plugin-modules file))]
            (if (= "math" file) (.-KaTeX plugin) plugin)))
        (html/active-plugins opts))))

(defn camel-keys
  "Recursively rename map keys to the camelCase names Reveal reads. Reveal
   ignores an unknown option silently, so a kebab-case :slide-number would
   simply never take effect — and the static exporter would disagree with the
   live shell. Both sides go through plato.json/camel-key."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(json/camel-key k) (camel-keys v)])) x)
    (sequential? x) (mapv camel-keys x)
    :else x))

(defn create!
  "Construct and initialize Reveal on `root`. `opts` selects plugins through
   plato.html/active-plugins ({:math? true} loads the math plugin); `on-ready`
   runs once the deck is laid out."
  ([root config] (create! root config {} nil))
  ([root config on-ready] (create! root config {} on-ready))
  ([root config opts on-ready]
   (let [options (clj->js (camel-keys config))
         constructor (default-export RevealModule)
         _ (aset options "plugins" (plugins-for opts))
         instance (js/Reflect.construct constructor #js [root options])]
     (-> (.initialize instance)
         (.then (fn []
                  (when on-ready
                    (on-ready instance)))))
     instance)))

(defn destroy! [instance]
  (when instance
    (.destroy instance)))

(defn configure! [instance config]
  (.configure instance (clj->js (camel-keys config)))
  instance)

(defn on! [instance event-name handler]
  (.on instance event-name handler)
  instance)
