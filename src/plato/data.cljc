(ns plato.data
  "The deck model itself as a source format.

   Markdown and Org are front ends that COMPILE to a deck; this one is the deck
   written down. It exists so a deck authored as Clojure data is a first-class
   CLI source like any other file, rather than something only reachable through
   `--deck` and a classpath."
  (:require [clojure.edn :as edn]
            [plato.deck :as deck]
            [plato.source :as source]))

(defn ->deck
  "EDN text holding a deck map -> a validated deck. The shape is exactly what
   `plato.deck/deck` accepts, so `pr-str` of a deck is a valid source file."
  [text]
  (deck/deck (edn/read-string text)))

(source/register-extensions! :edn ["edn"])

(defmethod source/->deck :edn [_ text] (->deck text))
