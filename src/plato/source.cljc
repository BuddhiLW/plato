(ns plato.source
  "Source text -> deck, open across front ends.

   The set of things a deck can be written in is open: Markdown, Org and EDN
   ship with plato, and nothing here knows their names. A front end declares
   itself by adding a `->deck` method and registering the extensions it reads,
   so adding one is a new namespace rather than an edit to this one or to the
   CLI.

   The same shape as `plato.content/render`: one multimethod, N self-registering
   projections."
  (:require [clojure.string :as str]))

(defonce ^:private extensions (atom {}))

(defn register-extensions!
  "Declare that files ending in `exts` are read by `kind`. Extensions are given
   without the dot and matched case-insensitively."
  [kind exts]
  (swap! extensions into (zipmap (map str/lower-case exts) (repeat kind)))
  kind)

(defn known-extensions
  "Registered extension -> kind, as data."
  []
  @extensions)

(defn kind
  "Source path -> the registered kind, or nil when nothing reads that
   extension."
  [path]
  (let [p (str/lower-case (str path))]
    (some (fn [[ext k]] (when (str/ends-with? p (str "." ext)) k))
          @extensions)))

(defmulti ->deck
  "Source text of `kind` -> a validated deck."
  (fn [kind _text] kind))

(defmethod ->deck :default [kind _text]
  (throw (ex-info (str "No front end reads " (pr-str kind))
                  {:kind kind :known (sort (vals @extensions))})))

(defmulti ->document
  "Source text of `kind` -> a plato.doc document.

   Optional: a front end implements this only if its source parses to a
   document IR. Throws for a kind that does not."
  (fn [kind _text] kind))

(defn document-kinds
  "Kinds that have a document IR, as data. A subset of `known-extensions`."
  []
  (sort (remove #{:default} (keys (methods ->document)))))

(defmethod ->document :default [kind _text]
  (throw (ex-info (str "No document IR for " (pr-str kind)
                       " — it reads straight to a deck.")
                  {:kind kind :known (document-kinds)})))
