(ns plato.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [plato.deck :as deck]
            [plato.reveal :as reveal]
            [plato.scene-view :as scene-view]))

(def ^:private reveal-data-keys
  #{:auto-animate
    :background-color
    :background-image
    :background-opacity
    :background-position
    :background-repeat
    :background-size
    :background-video
    :background-video-loop
    :background-video-muted
    :state
    :transition
    :transition-speed
    :visibility})

(defonce ^:private roots (js/WeakMap.))

(defn- js-value [value]
  (cond
    (true? value) ""
    (keyword? value) (name value)
    :else value))

(defn- section-attrs [slide]
  (reduce (fn [attrs key]
            (if (contains? slide key)
              (assoc attrs
                     (keyword (str "data-" (name key)))
                     (js-value (get slide key)))
              attrs))
          {:id (name (:id slide))}
          reveal-data-keys))

(declare entry-view)

(defn- content-view [content]
  (cond
    (= :desargues (:plato/type content)) [scene-view/scene-view content]
    (fn? content) [content]
    :else content))

(defn- slide-view [{:keys [content notes] :as slide}]
  (let [attrs (section-attrs slide)]
    (if (string? content)
      [:section (assoc attrs :data-markdown "")
       [:script {:type "text/template"} content]
       (when notes [:aside.notes notes])]
      [:section attrs
       (content-view content)
       (when notes [:aside.notes notes])])))

(defn- stack-view [{:keys [id slides]}]
  (into [:section {:id (name id)}]
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
        (reset! instance
                (reveal/create! @element (:config model) on-ready)))
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
