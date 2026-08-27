(ns plato.css
  "CSS as data — plato's answer to Sass.

   A stylesheet is a vector of rules. A rule is

     [selector declarations? & nested-rules]

   where `selector` is a keyword, a string, or a vector of either (a selector
   group); `declarations` is a map of property -> value; and a nested rule's
   selector is joined to its parent — `&` splices the parent in place, anything
   else becomes a descendant selector.

   A selector beginning with @ is an at-rule. Its nested rules keep the
   surrounding selector context, so a media query lives beside the declarations
   it overrides. An at-rule with no body is emitted as a statement.

   Nesting and grouping are the whole of what a theme needs from Sass, and
   keeping it as data means it stays CLJC: the same code runs on the JVM, in
   the browser, and in the native CLI."
  (:require [clojure.string :as str]))

(defn value->css
  "Declaration value -> its CSS text. Keywords contribute their name and
   sequentials become a comma list, so a font stack can be a vector."
  [v]
  (cond
    (keyword? v) (name v)
    (sequential? v) (str/join ", " (map value->css v))
    :else (str v)))

(defn- selector-strings [sel]
  (mapv #(if (keyword? %) (name %) (str %))
        (if (sequential? sel) sel [sel])))

(defn- join-selector [parent child]
  (cond
    (str/blank? parent) child
    (str/includes? child "&") (str/replace child "&" parent)
    :else (str parent " " child)))

(defn- combine [parents children]
  (if (empty? parents)
    children
    (vec (for [p parents c children] (join-selector p c)))))

(defn- flatten-rules
  "Rule tree -> a flat, document-ordered seq of
   {:ats [at-rule] :selectors [string] :decls map} and
   {:ats [at-rule] :statement string}."
  [parents ats rules]
  (mapcat
   (fn [rule]
     (when (seq rule)
       (let [[sel & body] rule
             sel-strs (selector-strings sel)
             [decls children] (if (map? (first body))
                                [(first body) (rest body)]
                                [nil body])]
         (if (str/starts-with? (first sel-strs) "@")
           (if (and (empty? children) (empty? decls))
             [{:ats ats :statement (first sel-strs)}]
             (flatten-rules parents (conj ats (first sel-strs)) children))
           (let [selectors (combine parents sel-strs)]
             (concat (when (seq decls)
                       [{:ats ats :selectors selectors :decls decls}])
                     (flatten-rules selectors ats children)))))))
   rules))

(defn- pad-of [n] (apply str (repeat n "  ")))

(defn- block [{:keys [selectors decls statement]} depth]
  (let [pad (pad-of depth)]
    (if statement
      (str pad statement ";")
      (str pad (str/join ", " selectors) " {\n"
           (str/join "\n"
                     (keep (fn [[k v]]
                             (when (some? v)
                               (str pad "  " (name k) ": " (value->css v) ";")))
                           decls))
           "\n" pad "}"))))

(defn- wrap-ats [ats body]
  (if (empty? ats)
    body
    (str (str/join (map-indexed (fn [i at] (str (pad-of i) at " {\n")) ats))
         body "\n"
         (str/join (map (fn [i] (str (pad-of i) "}\n")) (reverse (range (count ats))))))))

(defn rules->css
  "Rule tree -> a CSS stylesheet body."
  [rules]
  (->> (flatten-rules [] [] rules)
       (partition-by :ats)
       (map (fn [group]
              (let [ats (:ats (first group))
                    depth (count ats)
                    body (str/join "\n\n" (map #(block % depth) group))]
                (str/trimr (wrap-ats ats body)))))
       (str/join "\n\n")))
