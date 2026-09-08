(ns gen-responsive
  "Generate public/css/plato-responsive.css from the decks plato ships.

   The same shape as scripts/gen_nfd.clj: a JVM-only generator whose OUTPUT is
   a portable artifact. `plato.responsive` holds the rules and the projection
   and loads on every host; `hive-cljs.derive` runs them and is needed only
   here, so a deck never carries the engine that produced its stylesheet.

   The input that makes this more than a table of guesses is the FIT ESTIMATE:
   the tallest slide plato actually ships is what decides how far the type
   scale may be raised before the deck stops fitting its box. A deck that grows
   a taller slide gets a different stylesheet, and the header says why."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.derive :as derive]
            [plato.acme.deck :as acme]
            [plato.estimate :as est]
            [plato.example.deck :as site]
            [plato.metrics :as metrics]
            [plato.responsive :as responsive]
            [plato.tokens :as tokens]))

(def output "public/css/plato-responsive.css")

(def shipped
  [{:deck site/model :tokens "theme/plato.tokens.edn" :name "site"}
   {:deck acme/model :tokens "theme/acme.tokens.edn" :name "acme"}])

(defn- theme [path] (tokens/parse path (slurp path)))

(defn worst-demand
  "The tallest slide any shipped deck lays out, in slide-box pixels.

   The binding constraint on the whole derivation, so it is measured rather
   than assumed. Its id is reported: when the derivation says legibility is
   unsatisfiable, this is the slide to cut."
  [decks]
  (->> decks
       (mapcat (fn [{:keys [deck tokens name]}]
                 (map #(vector (str name "/" (:fit/id %))
                               (:h (:fit/extent %)))
                      (est/measure-deck deck (theme tokens)))))
       (sort-by second >)
       first))

(defn derivation
  "Run plato's rules over the facts the shipped decks produce."
  [box demand]
  (derive/run-strata responsive/strata (responsive/facts box demand)))

(defn -main [& _]
  (let [box (:box (metrics/metrics (theme "theme/plato.tokens.edn")))
        [worst-id demand] (worst-demand shipped)
        fx (derivation box demand)]
    (println "plato.responsive over" (count (:facts fx)) "facts"
             "(" (count (:derived fx)) "derived )")
    (println "  tallest shipped slide:" worst-id
             (str (long (Math/round (double demand))) "px")
             "in a" (str (long (:h box)) "px") "box")
    (doseq [line (responsive/report fx)] (println line))
    (io/make-parents output)
    (spit output (responsive/stylesheet fx))
    (println "wrote" output)))
