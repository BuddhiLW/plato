(ns plato.tokens
  "Theme tokens: one source, N generated artifacts.

   A token map is
     {:extends path                            ; optional base theme
      :meta  {:prefix string :name string :reveal-theme string}
      :color {leaf-key hex}
      :scale {leaf-key css-length}
      :type  {leaf-key css-font-stack}
      :scene {:palette [color-key ...] :background color-key :fallback color-key}
      :rules [plato.css rule ...]}             ; optional stylesheet body
   Leaf keys must be unique across :color, :scale and :type — each becomes one
   CSS custom property --<prefix>-<leaf-key>.

   The source may be EDN or JSON: the shape is the same, so a design tool can
   own the file. `:extends` names a base theme whose groups this one layers
   over, which is what makes a theme cheap to write — state what differs, not
   everything. Inside `:rules`, `[:token k]` stands for the var() reference to
   token k, so a theme never spells the generated custom-property name."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [plato.css :as css]
            [plato.json :as json]))

(def var-groups
  "Token groups projected to CSS custom properties, in emission order."
  [:color :scale :type])

(def token-groups
  "Groups merged key by key when one theme extends another."
  [:meta :color :scale :type :scene])

(defn- keywordize-scene
  "Scene colour references are keywords whatever the source dialect spelled
   them — JSON has no keyword, so it hands us strings."
  [tokens]
  (cond-> tokens
    (map? (:scene tokens))
    (update :scene
            (fn [{:keys [palette background fallback] :as scene}]
              (cond-> scene
                (seq palette) (assoc :palette (mapv keyword palette))
                background (assoc :background (keyword background))
                fallback (assoc :fallback (keyword fallback)))))))

(defn parse
  "Token source text -> a token map. `.json` is read as JSON, anything else as
   EDN; both produce the same shape."
  [path text]
  (keywordize-scene
   (if (str/ends-with? (str/lower-case (str path)) ".json")
     (json/parse text)
     (edn/read-string text))))

(defn merge-tokens
  "`child` layered over `base`, group by group. Only the keys the child states
   are replaced, so extending a theme to change one colour keeps every other
   one. `:rules` accumulate instead, base first, because a stylesheet is a
   sequence and the child must be able to override what the base declared."
  [base child]
  (let [rules (into (vec (:rules base)) (:rules child))]
    (cond-> (reduce (fn [acc group]
                      (let [merged (merge (get base group) (get child group))]
                        (if (seq merged) (assoc acc group merged) acc)))
                    (dissoc base :rules :extends)
                    token-groups)
      (seq rules) (assoc :rules rules))))

(defn compose
  "Ordered token maps, base first -> the one token map they describe."
  [maps]
  (reduce merge-tokens {} maps))

(defn prefix [tokens] (get-in tokens [:meta :prefix] "plato"))

(defn- group-entries [tokens group]
  (map (fn [[k v]] [(name k) v]) (sort-by key (get tokens group))))

(defn entries
  "Ordered [leaf-name value] pairs for every CSS-projected token."
  [tokens]
  (mapcat #(group-entries tokens %) var-groups))

(defn duplicate-keys
  "Leaf keys declared in more than one CSS-projected group."
  [tokens]
  (->> (entries tokens)
       (map first)
       frequencies
       (keep (fn [[k n]] (when (> n 1) k)))
       vec))

(defn assert-tokens!
  "Return `tokens` when well-formed; throw ex-info otherwise."
  [tokens]
  (let [dupes (duplicate-keys tokens)
        palette (get-in tokens [:scene :palette])
        missing (remove #(contains? (:color tokens) %) palette)]
    (when-not (map? (:color tokens))
      (throw (ex-info "Tokens require a :color map" {:tokens tokens})))
    (when (seq dupes)
      (throw (ex-info "Duplicate token leaf keys" {:keys dupes})))
    (when (seq missing)
      (throw (ex-info "Scene palette names unknown colors" {:keys (vec missing)})))
    tokens))

(defn var-name
  "CSS custom-property name for a leaf key."
  [tokens key]
  (str "--" (prefix tokens) "-" (name key)))

(defn var-ref
  "CSS var() reference for a leaf key."
  [tokens key]
  (str "var(" (var-name tokens key) ")"))

(defn palette
  "Semantic color keyword -> hex, for the scene layer."
  [tokens]
  (select-keys (:color tokens) (get-in tokens [:scene :palette])))

(defn- banner [tokens source comment-start comment-end]
  (str comment-start " " (get-in tokens [:meta :name] "Plato theme")
       " — GENERATED from " source ", do not edit.\n"
       "   Regenerate: plato theme " source " " comment-end "\n"))

(defn resolve-refs
  "Replace every [:token k] inside `rules` with the CSS var() reference for k."
  [tokens rules]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (= 2 (count x)) (= :token (first x)) (keyword? (second x)))
       (var-ref tokens (second x))
       x))
   rules))

(defn css
  "Token map -> a CSS file body: every token as a :root custom property,
   followed by the theme's own `:rules`."
  ([tokens] (css tokens "theme/plato.tokens.edn"))
  ([tokens source]
   (str (banner tokens source "/*" "*/")
        ":root {\n"
        (str/join "\n"
                  (map (fn [[k v]] (str "  " (var-name tokens k) ": " v ";"))
                       (entries tokens)))
        "\n}\n"
        (when (seq (:rules tokens))
          (str "\n" (css/rules->css (resolve-refs tokens (:rules tokens))) "\n")))))

(defn manifest
  "Token map -> the language-neutral manifest value."
  [tokens]
  {:name (get-in tokens [:meta :name])
   :prefix (prefix tokens)
   :reveal-theme (get-in tokens [:meta :reveal-theme])
   :tokens (into {} (map (fn [[k v]] [k {:value v :var (var-name tokens k)}]))
                 (entries tokens))
   :scene (:scene tokens)})

(defn json
  "Token map -> the manifest as pretty-printed JSON."
  [tokens]
  (str (json/write (manifest tokens) {:indent 2}) "\n"))

(defn- emit-map [m indent]
  (let [pad (apply str (repeat indent " "))]
    (str "{"
         (str/join (str "\n" pad)
                   (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) m))
         "}")))

(defn- emit-rules [rules indent]
  (let [pad (apply str (repeat indent " "))]
    (str "[" (str/join (str "\n" pad) (map pr-str rules)) "]")))

(defn cljc
  "Token map -> the source of a generated namespace holding it as data.

   Carries `:rules` when the theme declares any, so the namespace is the whole
   token map and not the part of it that happens to be leaf values."
  ([tokens] (cljc tokens 'plato.theme "theme/plato.tokens.edn"))
  ([tokens ns-sym source]
   (let [rules (:rules tokens)]
     (str "(ns " ns-sym "\n"
          "  \"GENERATED from " source ". Do not edit; run `plato theme " source "`.\")\n\n"
          "(def tokens\n"
          "  \"Theme tokens as data: :meta, :color, :scale, :type, :scene"
          (when (seq rules) ", :rules") ".\"\n"
          "  {:meta " (emit-map (:meta tokens) 10) "\n"
          "   :color " (emit-map (:color tokens) 11) "\n"
          "   :scale " (emit-map (:scale tokens) 11) "\n"
          "   :type " (emit-map (:type tokens) 10) "\n"
          "   :scene " (emit-map (:scene tokens) 11)
          (when (seq rules) (str "\n   :rules " (emit-rules rules 11)))
          "})\n"))))
