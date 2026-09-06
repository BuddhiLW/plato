(ns plato.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.reveal :as reveal]
            [plato.fit :as fit]
            [plato.scene-view]
            [plato.html :as html]))

(defonce ^:private roots (js/WeakMap.))

(declare entry-view)

(defn- slide-view [{:keys [content notes] :as slide}]
  (let [attrs (deck/section-attrs slide)
        aside (when notes [:aside.notes (content/render notes)])]
    (if (string? content)
      ;; data-markdown goes on a NESTED div, never on the section itself: the
      ;; plugin rewrites the element it finds it on via innerHTML, and React
      ;; must keep owning the <section>. The notes aside stays outside that div
      ;; so the rewrite cannot swallow it. The template is a <script> rather
      ;; than a <textarea> because React builds it as a DOM text node — no HTML
      ;; tokenizer, so </script> in the markdown is inert here. The exporter,
      ;; which does serialize, uses <textarea data-template> instead.
      [:section attrs
       [:div {:data-markdown ""}
        [:script {:type "text/template"} content]]
       aside]
      [:section attrs
       (content/render content)
       aside])))

(defn- stack-view [{:keys [slides] :as stack}]
  (into [:section (deck/section-attrs stack)]
        (map entry-view slides)))

(defn- entry-view [entry]
  (with-meta
    (if (= :stack (:plato/type entry))
      (stack-view entry)
      (slide-view entry))
    {:key (:id entry)}))

(defn presentation [model on-ready]
  (let [element (atom nil)
        instance (atom nil)]
    (r/create-class
     {:display-name "PlatoPresentation"
      :component-did-mount
      (fn [_]
        ;; The same :math? the exporter reads off the deck, so a deck that
        ;; never asked for math does not fetch it here either, and the same
        ;; config the exporter writes, so KaTeX is read from the same copy.
        (let [opts {:math? (boolean (:math? model))}]
          (reset! instance
                  (reveal/create! @element (html/reveal-config model opts) opts
                                  ;; Reveal has laid the deck out by now, so this
                                  ;; is the first moment a slide's size is a fact
                                  ;; rather than a guess.
                                  (fn []
                                    (fit/fitDeck)
                                    (when on-ready (on-ready)))))))
      :component-will-unmount
      (fn [_]
        (reveal/destroy! @instance)
        (reset! instance nil))
      :reagent-render
      (fn []
        [:div.reveal {:ref #(reset! element %)}
         (into [:div.slides]
               (map entry-view (:slides model)))])})))

(defn mount!
  ([element model] (mount! element model nil))
  ([element model on-ready]
   (let [model (deck/deck model)
         root (rdom/create-root element)]
     (.set roots element root)
     (rdom/render root [presentation model on-ready])
     root)))

(defn unmount! [element]
  (when-let [root (.get roots element)]
    (rdom/unmount root)
    (.delete roots element)
    true))
