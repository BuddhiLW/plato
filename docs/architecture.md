# Architecture

Plato has four runtime layers.

| Layer | Namespace | Runtime | Contract |
|---|---|---|---|
| Deck model | <code>plato.deck</code> | CLJ/CLJS | Validated deck, slide, and vertical-stack data |
| Animation core | <code>plato.timeline</code>, <code>plato.tween</code>, <code>plato.clock</code> | CLJ/CLJS | Desargues steps to deterministic frames |
| Render core | <code>plato.render</code>, <code>plato.geometry</code>, <code>plato.snapshot</code> | CLJ/CLJS | Scene nodes to SVG/Hiccup or standalone SVG |
| Browser shell | <code>plato.core</code>, <code>plato.reveal</code>, <code>plato.scene-view</code>, <code>plato.player</code> | CLJS | Reagent mounting, Reveal lifecycle, requestAnimationFrame playback |

## Desargues boundary

Plato depends on Desargues’ data contract, not its Manim implementation:

~~~clojure
{:scene keyword-or-string
 :nodes {id {:id id :node keyword ...}}
 :steps [{:step :play :anims [...]}
         {:step :hold :seconds number}]}
~~~

Imperative nodes carry <code>:at</code> and <code>:opts</code>. Declarative layout nodes carry <code>:box</code>, <code>:style</code>, and <code>:content</code>. Scene-level layout reveals carry <code>:target :scene</code> and <code>:ids</code>; timeline compilation expands them to one span per node.

## Extension points

Add a scene node with a <code>plato.render/node->hiccup</code> method. Add an animation descriptor with a <code>plato.tween/build-span</code> method and its channel entry. Alternate visual targets implement <code>IRenderTarget</code>; alternate clocks implement <code>IPlayer</code>.
