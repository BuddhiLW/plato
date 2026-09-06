(ns plato.example.core
  "Browser entry point for the site deck. The deck itself is
   plato.example.deck, a .cljc, so the build step can prerender the same value
   this shell mounts in the dev loop."
  (:require [plato.core :as plato]
            [plato.example.deck :as site]))

(defn- root [] (js/document.getElementById "app"))

(defn init []
  (plato/mount! (root) site/model))

(defn ^:dev/after-load remount!
  "Shadow hot-reload hook: tear the deck down and rebuild it, so an edit to the
   deck value shows without a page refresh. Reveal is destroyed on unmount, so
   the reload leaves no second instance behind."
  []
  (let [el (root)]
    (plato/unmount! el)
    (plato/mount! el site/model)))
