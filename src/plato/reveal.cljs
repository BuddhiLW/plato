(ns plato.reveal
  (:require ["reveal.js" :as RevealModule]
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

(defn create!
  ([root config] (create! root config nil))
  ([root config on-ready]
   (let [options (clj->js config)
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
  (.configure instance (clj->js config))
  instance)

(defn on! [instance event-name handler]
  (.on instance event-name handler)
  instance)
