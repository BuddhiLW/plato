# Plato

Plato is a data-driven ClojureScript presentation engine built on Reveal.js. It adds a Clojure-native deck model and live playback for scene graphs emitted by the [Desargues](https://github.com/mentat-collective/desargues) animation engine.

The result keeps Reveal’s navigation, overview, speaker notes, Markdown, syntax highlighting, math, search, fragments, PDF export, and deep links while making presentations ordinary Clojure data.

## What it adds above Reveal.js

- Deck DSL: immutable maps for decks, horizontal slides, and vertical stacks.
- ClojureScript views: use Hiccup or any Reagent component as slide content.
- Desargues playback: compile RecordingBackend scene graphs into live, scrub-able SVG.
- Shared CLJC core: timeline, tween, renderer, geometry, deck validation, and snapshots run on JVM Clojure too.
- Open rendering: node renderers use a multimethod; playback and render targets use protocols.
- Static export: render a scene’s final state to standalone SVG.
- Reveal plugins: Markdown, highlight, notes, math, search, and zoom are enabled.

## Run the example

Requirements: JDK 17+, Clojure CLI, Node.js 20+.

~~~bash
npm install
npm run dev
~~~

Open <http://localhost:8080>. Production build:

~~~bash
npm run check
~~~

## Author a deck

~~~clojure
(ns talk.core
  (:require [plato.core :as plato]
            [plato.deck :as deck]
            [plato.desargues :as desargues]))

(def graph
  {:scene :demo
   :nodes {1 {:id 1 :node :circle :at [0 0]
              :opts {:radius 1 :color :gold}}}
   :steps [{:step :play
            :anims [{:anim :appear :target 1
                     :opts {:run-time 1}}]}]})

(def model
  (deck/deck
   {:title "My talk"
    :slides
    [(deck/slide :intro [:h1 "Hello"]
                 {:notes "Speaker-only note"})
     (deck/slide :model
                 (desargues/scene graph)
                 {:transition :fade})
     (deck/stack
      :details
      [(deck/slide :markdown "# Markdown\n\n- Native Reveal plugin")
       (deck/slide :hiccup [:h2.fragment "Vertical slide"])])]}))

(defn init []
  (plato/mount! (js/document.getElementById "app") model))
~~~

A slide’s content may be:

- Hiccup
- a Reagent component function
- a Markdown string
- a value returned by <code>plato.desargues/scene</code>

Reveal slide attributes are passed as slide options, including <code>:background-color</code>, <code>:background-image</code>, <code>:transition</code>, <code>:auto-animate</code>, and <code>:visibility</code>.

## Desargues compatibility

Plato accepts both Desargues RecordingBackend outputs:

- imperative scenes from <code>desargues.scene/render!</code>
- declarative layout scenes from <code>desargues.scene/render-layout!</code>

The browser understands Desargues’ <code>:scene</code>, <code>:nodes</code>, and <code>:steps</code> graph. Supported animation descriptors include <code>:appear</code>, <code>:vanish</code>, <code>:draw</code>, <code>:recolor</code>, <code>:count-to</code>, <code>:glide</code>, <code>:emphasize</code>, <code>:group</code>, and <code>:stagger</code>. Layout graphs using <code>:target :scene</code> plus <code>:ids</code> are expanded into node spans.

Generate a graph on the JVM with Desargues’ pure RecordingBackend, save it as EDN or CLJC data, and pass it to <code>plato.desargues/scene</code> in the deck.

## Public API

- <code>plato.deck/deck</code>, <code>slide</code>, <code>stack</code>, <code>leaf-slides</code>
- <code>plato.desargues/graph?</code>, <code>assert-graph!</code>, <code>scene</code>
- <code>plato.core/mount!</code>, <code>unmount!</code>
- <code>plato.timeline/compile-timeline</code>, <code>frame</code>
- <code>plato.snapshot/scene->svg</code>, <code>write-svg!</code>
- <code>plato.render/node->hiccup</code> multimethod
- <code>plato.protocols/IRenderTarget</code>, <code>ITween</code>, <code>IPlayer</code>, <code>IDeck</code>

## Verification

~~~bash
npm test
npm run build
~~~

The suite runs the pure CLJC engine on JVM Clojure. The production build compiles the browser engine with advanced Closure optimizations.

## License

MIT
