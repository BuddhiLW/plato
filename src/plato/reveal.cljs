(ns plato.reveal
  (:require [plato.json :as json]
            ["reveal.js" :as RevealModule]
            ["reveal.js/plugin/markdown" :as MarkdownModule]
            ["reveal.js/plugin/highlight" :as HighlightModule]
            ["reveal.js/plugin/notes" :as NotesModule]
            ["reveal.js/plugin/math" :as MathModule]
            ["reveal.js/plugin/search" :as SearchModule]
            ["reveal.js/plugin/zoom" :as ZoomModule]))

(defn- default-export [module]
  (or (.-default module) module))

(def default-plugins
  #js [(default-export MarkdownModule)
       (default-export HighlightModule)
       (default-export NotesModule)
       (default-export MathModule)
       (default-export SearchModule)
       (default-export ZoomModule)])

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
  ([root config] (create! root config nil))
  ([root config on-ready]
   (let [options (clj->js (camel-keys config))
         constructor (default-export RevealModule)
         _ (aset options "plugins" default-plugins)
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
