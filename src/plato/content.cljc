(ns plato.content
  "Slide-content model: data constructors and an open `render` multimethod
   from a content value to hiccup.

   A content value is hiccup, a string, a component fn, a seq, or a map tagged
   with :plato/type. Add a content kind with a `render` defmethod."
  (:require [clojure.string :as str]))

(defn kind
  "Dispatch value of `render`: :plato/type for tagged maps, else the shape."
  [content]
  (cond
    (nil? content) :nil
    (map? content) (get content :plato/type :map)
    (string? content) :string
    (vector? content) :hiccup
    (fn? content) :component
    (seq? content) :seq
    :else :value))

(defmulti render
  "Content value -> hiccup."
  kind)

(declare expand)

(defn- expand-children [node]
  (cond
    (and (map? node) (contains? node :plato/type)) (expand (render node))
    ;; A vector whose head is a fn is a component call: its rest are arguments,
    ;; not children. Descending would re-expand a tagged map the component was
    ;; handed back, which never terminates.
    (and (vector? node) (fn? (first node))) node
    (vector? node) (mapv expand-children node)
    (seq? node) (map expand-children node)
    :else node))

(defn expand
  "Recursively replace tagged content maps nested inside hiccup with their
   rendered hiccup."
  [node]
  (expand-children node))

(defmethod render :nil [_] nil)
(defmethod render :string [content] content)
(defmethod render :value [content] (str content))
(defmethod render :component [content] [content])
(defmethod render :hiccup [content] (expand content))
(defmethod render :seq [content] (into [:<>] (map expand) content))
(defmethod render :map [content] (expand content))

;; ── media ───────────────────────────────────────────────────────────────────

(defn image
  "Image (or animated GIF) content. opts: :alt :caption :width :height :fit :class."
  ([src] (image src {}))
  ([src opts] (merge {:plato/type :image :src src} opts)))

(defn- css-length
  "Sizing value -> a CSS length. Bare numbers are pixels."
  [v]
  (if (number? v) (str v "px") (str v)))

(defmethod render :image [{:keys [src alt caption width height fit class lazy?]}]
  ;; Sizing is emitted as inline style as well as attributes: the stylesheet
  ;; sets img dimensions, so an attribute alone lands only in the export.
  (let [style (cond-> nil
                width (assoc :width (css-length width))
                height (assoc :height (css-length height))
                fit (assoc :object-fit (name fit)))]
    [:figure.plato-figure {:class class}
     [:img (cond-> {:alt (or alt caption "")}
             lazy? (assoc :data-src src)
             (not lazy?) (assoc :src src)
             width (assoc :width width)
             height (assoc :height height)
             style (assoc :style style))]
     (when caption [:figcaption.plato-caption caption])]))

(defn video
  "Video content. opts: :poster :sources :caption :controls? :autoplay? :loop?
   :muted? :class. :autoplay? defers playback to Reveal (data-autoplay)."
  ([src] (video src {}))
  ([src opts] (merge {:plato/type :video :src src :controls? true} opts)))

(defmethod render :video
  [{:keys [src sources poster caption controls? autoplay? loop? muted? class width]}]
  [:figure.plato-figure {:class class}
   (into [:video (cond-> {:class "plato-video" :playsInline true}
                   (and src (empty? sources)) (assoc :src src)
                   poster (assoc :poster poster)
                   width (assoc :width width :style {:width (css-length width)})
                   controls? (assoc :controls true)
                   autoplay? (assoc :data-autoplay true)
                   loop? (assoc :loop true)
                   (or muted? autoplay?) (assoc :muted true))]
         (map (fn [{:keys [src type]}] [:source {:src src :type type}]) sources))
   (when caption [:figcaption.plato-caption caption])])

(defn audio
  "Audio content. opts: :caption :controls? :autoplay? :loop? :class."
  ([src] (audio src {}))
  ([src opts] (merge {:plato/type :audio :src src :controls? true} opts)))

(defmethod render :audio [{:keys [src caption controls? autoplay? loop? class]}]
  [:figure.plato-figure {:class class}
   [:audio (cond-> {:src src :class "plato-audio"}
             controls? (assoc :controls true)
             autoplay? (assoc :data-autoplay true)
             loop? (assoc :loop true))]
   (when caption [:figcaption.plato-caption caption])])

(defn embed
  "Iframe content. opts: :title :ratio :caption :lazy? :interactive? :class."
  ([src] (embed src {}))
  ([src opts] (merge {:plato/type :embed :src src :ratio "16 / 9"} opts)))

(defmethod render :embed [{:keys [src title ratio caption lazy? class]}]
  [:figure.plato-figure {:class class}
   [:div.plato-embed {:style {:aspect-ratio ratio}}
    [:iframe (cond-> {:title (or title caption "Embedded content")
                      :loading "lazy"
                      :allowFullScreen true
                      :frameBorder "0"}
               lazy? (assoc :data-src src)
               (not lazy?) (assoc :src src))]]
   (when caption [:figcaption.plato-caption caption])])

(defn code
  "Source-code content. opts: :highlight (Reveal step spec) :caption :class."
  ([lang source] (code lang source {}))
  ([lang source opts]
   (merge {:plato/type :code :lang lang :source source} opts)))

(defmethod render :code [{:keys [lang source highlight caption class]}]
  [:figure.plato-figure {:class class}
   [:pre.plato-code
    [:code (cond-> {:class (str "language-" (name (or lang :text)))
                    :data-trim true}
             highlight (assoc :data-line-numbers highlight))
     source]]
   (when caption [:figcaption.plato-caption caption])])

;; ── prose ───────────────────────────────────────────────────────────────────

(defn quotation
  "Block quote. opts: :cite :class."
  ([text] (quotation text {}))
  ([text opts] (merge {:plato/type :quote :text text} opts)))

(defmethod render :quote [{:keys [text cite class]}]
  [:blockquote.plato-quote {:class class}
   (expand text)
   (when cite [:footer.plato-cite cite])])

(defn bullets
  "List content. opts: :ordered? :fragments? :effect :class."
  ([items] (bullets items {}))
  ([items opts] (merge {:plato/type :bullets :items (vec items)} opts)))

(defmethod render :bullets [{:keys [items ordered? fragments? effect class]}]
  (into [(if ordered? :ol.plato-list :ul.plato-list) {:class class}]
        (map (fn [item]
               [:li (when fragments?
                      {:class (str "fragment " (name (or effect :fade-in)))})
                (expand item)]))
        items))

(defn table
  "Table content. opts: :caption :class. `head` may be nil."
  ([head rows] (table head rows {}))
  ([head rows opts]
   (merge {:plato/type :table :head (vec head) :rows (mapv vec rows)} opts)))

(defmethod render :table [{:keys [head rows caption class]}]
  [:table.plato-table {:class class}
   (when caption [:caption caption])
   (when (seq head)
     [:thead (into [:tr] (map (fn [cell] [:th (expand cell)])) head)])
   (into [:tbody]
         (map (fn [row] (into [:tr] (map (fn [cell] [:td (expand cell)])) row)))
         rows)])

;; ── layout ──────────────────────────────────────────────────────────────────

(defn columns
  "Side-by-side content. opts: :gap :widths :class."
  ([items] (columns items {}))
  ([items opts] (merge {:plato/type :columns :items (vec items)} opts)))

(defmethod render :columns [{:keys [items gap widths class]}]
  (into [:div.plato-columns
         {:class class
          :style (cond-> {}
                   gap (assoc :gap gap)
                   (seq widths) (assoc :grid-template-columns (str/join " " widths)))}]
        (map (fn [item] [:div.plato-column (expand item)]))
        items))

(defn cards
  "Card grid. Items are {:title :body :icon}. opts: :columns :fragments? :class."
  ([items] (cards items {}))
  ([items opts] (merge {:plato/type :cards :items (vec items)} opts)))

(defmethod render :cards [{:keys [items columns fragments? class]}]
  (into [:div.plato-grid
         {:class class
          :style (when columns
                   {:grid-template-columns (str "repeat(" columns ", 1fr)")})}]
        (map (fn [{:keys [title body icon]}]
               [:div.plato-card {:class (when fragments? "fragment")}
                (when icon [:div.plato-card-icon icon])
                (when title [:h3 (expand title)])
                (when body [:p (expand body)])]))
        items))

(defn group
  "Vertical sequence of content values. opts: :class."
  ([items] (group items {}))
  ([items opts] (merge {:plato/type :group :items (vec items)} opts)))

(defn collect
  "One content value for `items`: the lone item when there is one and no opts
   are given, else a group. Front ends use it to turn a parsed block list into
   a single value without wrapping every singleton in a div."
  ([items] (collect items {}))
  ([items opts]
   (let [items (vec items)]
     (if (and (= 1 (count items)) (empty? opts))
       (first items)
       (group items opts)))))

(defmethod render :group [{:keys [items class]}]
  (into [:div.plato-group {:class class}] (map expand) items))

(defn fragment
  "Fragment wrapper. opts: :effect :index :class."
  ([content] (fragment content {}))
  ([content opts] (merge {:plato/type :fragment :content content} opts)))

(defmethod render :fragment [{:keys [content effect index class]}]
  [:div {:class (str/join " " (remove str/blank?
                                      ["fragment" (name (or effect :fade-in)) class]))
         :data-fragment-index index}
   (expand content)])

(defn note
  "Callout box. opts: :tone (:info :warn :ok) :title :class."
  ([content] (note content {}))
  ([content opts] (merge {:plato/type :note :content content} opts)))

(defmethod render :note [{:keys [content title tone class]}]
  [:div.plato-note {:class (str/join " " (remove str/blank?
                                                 [(when tone (str "plato-note-" (name tone)))
                                                  class]))}
   (when title [:div.plato-note-title title])
   (expand content)])

(defn kicker
  "Small uppercase label above a heading."
  [text]
  {:plato/type :kicker :text text})

(defmethod render :kicker [{:keys [text]}] [:div.plato-kicker text])

;; ── escape hatches ──────────────────────────────────────────────────────────

(defn markdown
  "Markdown handed to the Reveal markdown plugin at render time."
  [text]
  {:plato/type :markdown :text text})

(defmethod render :markdown [{:keys [text]}]
  ;; <textarea data-template> is Reveal's RCDATA container: the browser escapes
  ;; its content, so markdown holding </script> cannot break out of the page.
  [:div {:data-markdown ""}
   [:textarea {:data-template ""} text]])

(defn html
  "Raw HTML content."
  [source]
  {:plato/type :html :html source})

(defmethod render :html [{:keys [html class]}]
  [:div.plato-html {:class class
                    :dangerouslySetInnerHTML {:__html html}}])

(defmethod render :default [content]
  [:div.plato-unknown {:data-content-type (str (kind content))}])
