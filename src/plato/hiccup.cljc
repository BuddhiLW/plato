(ns plato.hiccup
  "Hiccup to HTML string.

   Supports tag shorthand (:div#id.a.b), class vectors, style maps, boolean
   attributes, seq/fragment children, void elements, raw-text elements
   (script/style), :dangerouslySetInnerHTML, and component functions."
  (:require [clojure.string :as str]))

(def void-elements
  #{"area" "base" "br" "col" "embed" "hr" "img" "input"
    "link" "meta" "param" "source" "track" "wbr"})

(def raw-text-elements
  #{"script" "style"})

(defn escape
  "XML-escape `value` for use in text or an attribute value."
  [value]
  (str/escape (str value)
              {\& "&amp;"
               \< "&lt;"
               \> "&gt;"
               \" "&quot;"
               \' "&#39;"}))

(defn parse-tag
  "Hiccup tag keyword/string -> [tag-name id classes]. Tag defaults to \"div\"."
  [tag]
  (let [s (name tag)
        [head & classes] (str/split s #"\.")
        [tag-name id] (str/split (or head "") #"#")]
    [(if (str/blank? tag-name) "div" tag-name)
     (when-not (str/blank? id) id)
     (vec (remove str/blank? classes))]))

(defn class-string
  "Normalize a hiccup :class value (string, keyword, or collection) to a string."
  [value]
  (cond
    (nil? value) nil
    (string? value) (when-not (str/blank? value) value)
    (keyword? value) (name value)
    (map? value) (->> value (keep (fn [[k v]] (when v (name k)))) (str/join " ") not-empty)
    (coll? value) (->> value (keep class-string) (str/join " ") not-empty)
    :else (str value)))

(defn style-string
  "Normalize a hiccup :style value (map or string) to a CSS declaration string."
  [value]
  (if (map? value)
    (->> value
         (keep (fn [[k v]]
                 (when (some? v)
                   (str (name k) ":" (if (keyword? v) (name v) v)))))
         (str/join ";"))
    (str value)))

(def react-only-props
  "Props React consumes itself; they are not HTML attributes and must never
   reach a serialized document."
  #{:key :ref :dangerouslySetInnerHTML})

(defn- attr-string [k v]
  (let [n (name k)]
    (cond
      (or (nil? v) (false? v)) nil
      (= :class k) (when-let [c (class-string v)] (str " " n "=\"" (escape c) "\""))
      (true? v) (str " " n "=\"\"")
      (= :style k) (str " " n "=\"" (escape (style-string v)) "\"")
      (keyword? v) (str " " n "=\"" (escape (name v)) "\"")
      :else (str " " n "=\"" (escape v) "\""))))

(def ^:private leading-attrs
  "Attributes emitted first, in this order; everything else follows by name."
  {:id 0 :class 1})

(defn attrs->string
  "Attribute map -> serialized HTML attribute string (leading space per attr).

   Order is imposed here rather than inherited from the map: no Clojure map
   preserves insertion order past a handful of entries, and which order it falls
   back to is a property of the host. Sorting makes the same element serialize
   to the same bytes on the JVM, in the browser and in the native binary."
  [attrs]
  (->> attrs
       (remove (fn [[k _]] (contains? react-only-props k)))
       (sort-by (fn [[k _]] [(get leading-attrs k 2) (name k)]))
       (keep (fn [[k v]] (attr-string k v)))
       (apply str)))

(defn- merge-attrs [attrs id classes]
  (cond-> attrs
    (and id (not (:id attrs))) (assoc :id id)
    (seq classes) (assoc :class (class-string (into classes [(:class attrs)])))))

(declare ->html)

(defn neutralize-close
  "Break every `</` in raw text so it cannot terminate the enclosing
   script/style element. `<\\/` is inert in JS and CSS alike."
  [s]
  (str/replace (str s) "</" "<\\/"))

(defn- children->html [raw? children]
  (apply str (map #(if (and raw? (string? %)) (neutralize-close %) (->html %)) children)))

(defn- attrs-map? [value]
  (and (map? value) (not (contains? value :plato/raw))))

(defn- element->html [[tag & body]]
  (let [[tag-name id classes] (parse-tag tag)
        [attrs children] (if (attrs-map? (first body))
                           [(first body) (rest body)]
                           [{} body])
        attrs (merge-attrs attrs id classes)
        inner (get-in attrs [:dangerouslySetInnerHTML :__html])]
    (cond
      (= "<>" tag-name) (children->html false children)
      (void-elements tag-name) (str "<" tag-name (attrs->string attrs) ">")
      inner (str "<" tag-name (attrs->string attrs) ">" inner "</" tag-name ">")
      :else
      (str "<" tag-name (attrs->string attrs) ">"
           (children->html (raw-text-elements tag-name) children)
           "</" tag-name ">"))))

(defn ->html
  "Hiccup value -> HTML string. nil, booleans, and empty seqs render as \"\"."
  [node]
  (cond
    (nil? node) ""
    (boolean? node) ""
    (string? node) (escape node)
    (number? node) (str node)
    (keyword? node) (escape (name node))
    (and (map? node) (contains? node :plato/raw)) (str (:plato/raw node))
    (vector? node)
    (let [head (first node)]
      (cond
        (or (keyword? head) (string? head)) (element->html node)
        (fn? head) (->html (apply head (rest node)))
        :else (children->html false node)))
    (seq? node) (children->html false node)
    (fn? node) (->html (node))
    :else (escape (str node))))

(defn raw
  "Marker whose string is emitted verbatim by `->html`."
  [html]
  {:plato/raw html})
