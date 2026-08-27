(ns plato.json
  "Minimal JSON writer and reader.

   `write` emits nil, booleans, numbers, strings, keywords, symbols, maps,
   sequentials and sets. `parse` reads JSON text back into Clojure data.

   Both are hand-rolled rather than delegated, because this namespace has to
   run unchanged on the JVM, in ClojureScript and in the ClojureWasm build of
   the CLI — no host JSON library spans all three."
  (:require [clojure.string :as str]))

(def ^:private hex-digits "0123456789ABCDEF")

(defn- u-escape
  "Code point below 0x20 -> its \\u00XX escape."
  [i]
  (str "\\u00" (nth hex-digits (quot i 16)) (nth hex-digits (mod i 16))))

(def ^:private escapes
  (merge (into {} (map (fn [i] [(char i) (u-escape i)])) (range 0x20))
         {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
          \formfeed "\\f" \backspace "\\b" \< "\\u003C"}))

(defn escape
  "JSON-escape a string body (without the surrounding quotes). `<` becomes
   \\u003C so that a value containing </script> cannot close an inline script;
   every C0 control character becomes its \\u00XX escape."
  [s]
  (str/escape (str s) escapes))

(defn- write-string [s] (str "\"" (escape s) "\""))

(defn- key-name [key-fn k]
  (write-string (key-fn (if (or (keyword? k) (symbol? k)) (name k) (str k)))))

(defn- non-finite? [n]
  #?(:clj (and (float? n)
               (let [d (double n)]
                 (or (Double/isNaN d) (Double/isInfinite d))))
     :cljs (not (js/isFinite n))
     :default false))

(defn- write-number
  "JSON has neither ratios nor NaN/Infinity: a ratio widens to a double and a
   non-finite value emits null."
  [n]
  (let [n #?(:clj (if (ratio? n) (double n) n) :default n)]
    (if (non-finite? n) "null" (str n))))

(defn write
  "Value -> JSON string.
   opts: :key-fn (map-key string -> string, default identity)
         :indent (spaces per level; 0 or nil emits one compact line)."
  ([value] (write value {}))
  ([value {:keys [key-fn indent] :or {key-fn identity}}]
   (let [pad (when (and indent (pos? indent)) (apply str (repeat indent " ")))
         emit (fn emit [value depth]
                (let [nl (when pad (str "\n" (apply str (repeat (inc depth) pad))))
                      end (when pad (str "\n" (apply str (repeat depth pad))))
                      sep (str "," (or nl ""))]
                  (cond
                    (nil? value) "null"
                    (boolean? value) (str value)
                    (number? value) (write-number value)
                    (string? value) (write-string value)
                    (keyword? value) (write-string (name value))
                    (symbol? value) (write-string (name value))
                    (map? value)
                    (if (empty? value)
                      "{}"
                      (str "{" nl
                           (str/join sep
                                     (map (fn [[k v]]
                                            (str (key-name key-fn k) ":"
                                                 (when pad " ")
                                                 (emit v (inc depth))))
                                          value))
                           end "}"))
                    (or (sequential? value) (set? value))
                    (if (empty? value)
                      "[]"
                      (str "[" nl
                           (str/join sep (map #(emit % (inc depth)) value))
                           end "]"))
                    :else (write-string (str value)))))]
     (emit value 0))))

(defn camel-key
  "kebab-case JSON key -> camelCase, for APIs that expect JavaScript naming.
   Keywords and symbols contribute their name, never their printed form."
  [k]
  (let [s (if (or (keyword? k) (symbol? k)) (name k) (str k))
        [head & tail] (str/split s #"-")]
    (apply str head (map str/capitalize tail))))

;; ── reader ──────────────────────────────────────────────────────────────────

(def ^:private hex-value
  (zipmap "0123456789abcdefABCDEF" (concat (range 16) (range 10 16))))

(defn- hex-value! [c]
  (or (hex-value c) (throw (ex-info "Bad \\u escape in JSON string" {:char c}))))

(def ^:private whitespace #{\space \tab \newline \return})

(def ^:private string-escapes
  {\" \" \\ \\ \/ \/ \b \backspace \f \formfeed
   \n \newline \r \return \t \tab})

(defn- at [s i] (when (< i (count s)) (nth s i)))

(defn- skip-ws [s i]
  (if (contains? whitespace (at s i)) (recur s (inc i)) i))

(defn- fail! [s i what]
  (throw (ex-info (str "Invalid JSON: expected " what " at index " i)
                  {:index i :near (subs s i (min (count s) (+ i 24)))})))

(defn- read-string-token
  "[string next-index] for the JSON string opening at index i."
  [s i]
  (loop [i (inc i) buf []]
    (let [c (at s i)]
      (cond
        (nil? c) (fail! s i "a closing quote")
        (= \" c) [(str/join buf) (inc i)]
        (= \\ c)
        (let [e (at s (inc i))]
          (if (= \u e)
            (let [code (reduce (fn [acc j] (+ (* 16 acc) (hex-value! (at s j))))
                               0 (range (+ i 2) (+ i 6)))]
              (recur (+ i 6) (conj buf (char code))))
            (if-let [replacement (string-escapes e)]
              (recur (+ i 2) (conj buf replacement))
              (fail! s i "a valid string escape"))))
        :else (recur (inc i) (conj buf c))))))

(def ^:private number-chars
  (set "-+.eE0123456789"))

(defn- read-number [s i]
  (let [end (loop [j i] (if (contains? number-chars (at s j)) (recur (inc j)) j))
        text (subs s i end)
        value (or (parse-long text) (parse-double text))]
    (if (nil? value) (fail! s i "a number") [value end])))

(defn- literal [s i text value]
  (when (= text (subs s i (min (count s) (+ i (count text)))))
    [value (+ i (count text))]))

(defn- read-value [s i key-fn]
  (let [i (skip-ws s i)
        c (at s i)]
    (case c
      \" (read-string-token s i)
      \{ (loop [i (skip-ws s (inc i)) acc {}]
           (cond
             (= \} (at s i)) [acc (inc i)]
             (= \, (at s i)) (recur (skip-ws s (inc i)) acc)
             (= \" (at s i))
             (let [[k after-key] (read-string-token s i)
                   after-colon (skip-ws s after-key)
                   _ (when-not (= \: (at s after-colon)) (fail! s after-colon "':'"))
                   [v after-val] (read-value s (inc after-colon) key-fn)]
               (recur (skip-ws s after-val) (assoc acc (key-fn k) v)))
             :else (fail! s i "an object key")))
      \[ (loop [i (skip-ws s (inc i)) acc []]
           (cond
             (= \] (at s i)) [acc (inc i)]
             (= \, (at s i)) (recur (skip-ws s (inc i)) acc)
             :else (let [[v after] (read-value s i key-fn)]
                     (recur (skip-ws s after) (conj acc v)))))
      (or (literal s i "true" true)
          (literal s i "false" false)
          (literal s i "null" nil)
          (if (contains? number-chars c)
            (read-number s i)
            (fail! s i "a JSON value"))))))

(defn parse
  "JSON text -> Clojure data. Object keys become keywords unless :key-fn says
   otherwise; arrays become vectors."
  ([s] (parse s {}))
  ([s {:keys [key-fn] :or {key-fn keyword}}]
   (let [[value end] (read-value (str s) 0 key-fn)
         rest-i (skip-ws (str s) end)]
     (when (< rest-i (count (str s)))
       (fail! (str s) rest-i "end of input"))
     value)))
