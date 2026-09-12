(ns plato.highlight
  "Reveal's highlight plugin over highlight.js CORE plus the languages plato
   decks actually use, instead of the dist plugin that bundles all 190+
   languages (921 KB, and the single largest thing a visitor downloaded).

   One definition, two projections: the Reagent shell registers `plugin`
   through plato.reveal, and the :highlight shadow target compiles this same
   namespace into the standalone script a prerendered page loads, where it
   defines the `RevealHighlight` global plato.html/plugins names.

   The plugin source lives in vendor-js/reveal-highlight as a local PACKAGE
   (shadow-cljs.edn :js-package-dirs) rather than on the classpath: classpath
   JavaScript goes through :advanced, which renames the Reveal methods the
   plugin calls; a package is bundled the way node_modules are, untouched.

   A language not registered here highlights as plain text. Add one by
   requiring it and adding a row to `languages` — the deck author's language
   keyword is the hljs name."
  (:require ["reveal-highlight" :default plugin-factory]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/bash" :as bash]
            ["highlight.js/lib/languages/clojure" :as clojure]
            ["highlight.js/lib/languages/ini" :as ini]
            ["highlight.js/lib/languages/markdown" :as markdown]
            ["highlight.js/lib/languages/plaintext" :as plaintext]
            ["highlight.js/lib/languages/rust" :as rust]))

(def languages
  "hljs name -> language definition. Registered once, at load.

   `ini` is also how highlight.js parses TOML: there is no separate toml
   definition, so `:ini` is the honest language keyword for a Cargo.toml or a
   deps block. `rust` earns its place because a deck explaining a native or
   WebAssembly library shows Rust beside the Clojure that calls it, and an
   unhighlighted Rust block was the one thing the previous set could not do."
  {"bash" bash
   "clojure" clojure
   "ini" ini
   "markdown" markdown
   "plaintext" plaintext
   "rust" rust})

(doseq [[name definition] languages]
  (.registerLanguage hljs name definition))

(def plugin
  "The plugin factory Reveal.initialize takes in its :plugins list — the same
   shape the dist plugin's `RevealHighlight` global has."
  plugin-factory)

(set! (.-RevealHighlight js/window) plugin)
