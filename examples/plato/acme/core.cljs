(ns plato.acme.core
  (:require [plato.core :as plato]
            [plato.acme.deck :as acme]))

(defn- root [] (js/document.getElementById "app"))

(defn init []
  (plato/mount! (root) acme/model))

(defn ^:dev/after-load remount!
  "Shadow hot-reload hook: tear the deck down and rebuild it, so an edit to the
   deck value shows without a page refresh. Reveal is destroyed on unmount, so
   the reload leaves no second instance behind."
  []
  (let [el (root)]
    (plato/unmount! el)
    (plato/mount! el acme/model)))
