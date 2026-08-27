# Plato

**[See it explain itself →](https://buddhilw.github.io/plato/)** — plato’s own
introduction, as a deck plato builds. ([the Acme
demo](https://buddhilw.github.io/plato/acme.html) exercises every content kind.)

Plato is a data-driven ClojureScript presentation engine built on Reveal.js. A deck is an ordinary Clojure value, so the same source renders as a live presentation in the browser, as a standalone HTML file from the JVM or a native binary, and can be asserted in a test suite.

It adds a Clojure-native deck model, an open slide-content model, live playback for scene graphs emitted by the [Desargues](https://github.com/mentat-collective/desargues) animation engine, Markdown and Org front ends, and a token-driven theming pipeline.

Reveal’s navigation, overview, speaker notes, Markdown, syntax highlighting, math, search, fragments, PDF export, and deep links all keep working.

## What it adds above Reveal.js

- **Deck DSL** — immutable maps for decks, horizontal slides, and vertical stacks.
- **Content model** — images, GIFs, video, audio, iframes, code, tables, quotes, lists, columns, card grids, callouts and fragments are data, rendered through one open multimethod.
- **Open set of front ends** — Markdown, Org and EDN ship; a format joins by adding one `plato.source/->deck` method, with no edit to the CLI.
- **Two render targets** — Reagent in the browser, an HTML string on the JVM. One definition of slide attributes serves both.
- **Desargues playback** — compile RecordingBackend scene graphs into live, scrub-able SVG; the same scene renders as static SVG when there is no browser.
- **Theming as data** — one EDN token file generates the CSS custom properties, the Clojure palette, and a JSON manifest.
- **A CLI** — build a deck from Markdown or Org, on the JVM or as a self-contained native binary.

## Run the demos

Requirements: JDK 17+, Clojure CLI, Node.js 20+.

~~~bash
npm install
npm run dev
~~~

- <http://localhost:8080/> — plato explaining itself: the deck model, the open content and front-end registries, theming, and two live Desargues scenes. This is what ships to [buddhilw.github.io/plato](https://buddhilw.github.io/plato/), and the browser suite drives it, so a slide that stops rendering fails CI.
- <http://localhost:8080/acme.html> — **Acme Corp — Q3 Product Review**, a 25-slide demo that exercises every content kind: background image and background video, an animated GIF, an inline video with poster and multiple sources, audio, an interactive iframe, stepped code highlighting, a native Reveal markdown slide, math, a metrics table, a card grid, a pull quote, a callout, an auto-animate pair, a vertical stack, and a live Desargues scene.

Production build (both decks): `npm run build`. Full check: `npm run check`.

The demo’s media are fixtures generated into `public/assets/acme/`; `npm run fixtures` regenerates them byte-for-byte from `scripts/gen-fixtures.mjs` (needs ffmpeg, ImageMagick and rsvg-convert).

## Author a deck

### As Clojure data

~~~clojure
(ns talk.core
  (:require [plato.core :as plato]
            [plato.content :as content]
            [plato.deck :as deck]
            [plato.desargues :as desargues]))

(def model
  (deck/deck
   {:title "My talk"
    :slides
    [(deck/slide :intro
                 [:div (content/kicker "Acme Corp") [:h1 "Hello"]]
                 {:background-image "assets/hero.jpg"
                  :notes "Speaker-only note"})

     (deck/slide :clip
                 (content/video nil {:sources [{:src "assets/demo.webm" :type "video/webm"}
                                               {:src "assets/demo.mp4"  :type "video/mp4"}]
                                     :poster "assets/poster.jpg"
                                     :caption "Five seconds of replay"}))

     (deck/slide :model (desargues/scene graph) {:transition :fade})

     (deck/stack :details
                 [(deck/slide :markdown "# Markdown\n\n- Native Reveal plugin")
                  (deck/slide :code (content/code :clojure source {:highlight "1|3-5"}))])]}))

(defn init []
  (plato/mount! (js/document.getElementById "app") model))
~~~

A slide’s `:content` may be hiccup, a Reagent component fn, a Markdown string (which becomes a native Reveal markdown section), a `plato.content` value, or a `plato.desargues/scene`. Reveal slide attributes are passed as slide options — `:background-color`, `:background-image`, `:background-video`, `:background-gradient`, `:transition`, `:auto-animate`, `:visibility` and the rest.

### Content kinds

| Constructor | Renders |
| --- | --- |
| `content/image` | `<figure>` with `<img>` and an optional caption (also GIFs) |
| `content/video` | `<video>` with poster, multiple `<source>`s, Reveal `data-autoplay` |
| `content/audio` | `<audio>` with controls |
| `content/embed` | aspect-ratio-boxed `<iframe>`, optionally lazy |
| `content/code` | `<pre><code>` with a language class and stepped `data-line-numbers` |
| `content/quotation` | `<blockquote>` with a citation footer |
| `content/bullets` | ordered/unordered list, optionally one fragment per item |
| `content/table` | `<table>` with an optional head row and caption |
| `content/columns` | responsive grid of content values |
| `content/cards` | card grid, optionally fragmented |
| `content/group` | vertical sequence of content values |
| `content/fragment` | Reveal fragment wrapper with effect and index |
| `content/note` | callout box with `:info` / `:warn` / `:ok` tones |
| `content/kicker` | small uppercase label above a heading |
| `content/markdown` | Markdown handed to the Reveal markdown plugin |
| `content/html` | raw HTML escape hatch |

Content maps nested inside hiccup are expanded automatically, so `[:div [:h2 "Title"] (content/bullets [...])]` works. Add a kind with one `defmethod plato.content/render`.

### As Markdown or Org

~~~markdown
---
title: Acme Q3
---

# Revenue

Up **86%** year over year.

- New logos: 42
- Churn: 1.8%

Note: mention the pipeline change

## Detail

![Quarterly revenue](assets/acme/chart.png "USD millions")
~~~

`#` opens a horizontal slide, `##` a vertical one; `---` and `--` are Reveal’s explicit separators, `Note:` starts speaker notes, and `<!-- .slide: data-background-color="#111" -->` sets slide options. A standalone image line whose target is a video or audio file becomes a `content/video` or `content/audio`.

Org is the same model: `*` / `**` headlines, `#+TITLE:`, `:PROPERTIES:` drawers for slide options, `#+BEGIN_SRC` / `#+BEGIN_QUOTE` / `#+BEGIN_NOTES` blocks, `#+CAPTION:` and `#+ATTR_PLATO:` lines, tables, lists and `[[file:…]]` links.

Both parse to the same document IR (`plato.doc`) and compile to the same deck value. See [docs/authoring.md](docs/authoring.md) for the full syntax.

### As EDN, or as a format that does not exist yet

A `.edn` file holding a deck map is a source like any other, so `pr-str` of a deck is a valid input file:

~~~bash
plato build deck.edn -o dist/talk.html
~~~

Which formats exist is not decided in the CLI. `plato.source` holds one multimethod and a registry, and a front end declares itself:

~~~clojure
(ns talk.latex
  (:require [plato.source :as source]))

(source/register-extensions! :latex ["tex"])

(defmethod source/->deck :latex [_ text] ...)
~~~

Requiring that namespace is all the CLI needs — the same open/closed shape as `plato.content/render`.

## The CLI

~~~bash
# JVM
clojure -M:cli build docs/acme.md -o dist/acme.html --tokens theme/acme.tokens.edn

# self-contained native binary (ClojureWasm)
npm run cli:native          # -> dist/plato
./dist/plato build talk.org -o talk.html --theme-css house.css
./dist/plato theme theme/acme.tokens.edn -o public/css/acme-theme.css
~~~

`plato build` accepts `.md`, `.markdown` and `.org`, and takes `--out`, `--title`, `--theme`, `--theme-css`, `--tokens`, `--asset-base` and `--print`. `plato theme` regenerates a theme’s CSS, JSON manifest and Clojure namespace.

The native binary is built by `scripts/build-cli.sh` with [ClojureWasm](https://github.com/clojurewasm) — `cljw build -m plato.cli` — and needs no JVM at run time.

The native binary produces output byte-identical to the JVM’s — same HTML, same
stylesheets, same copied asset trees.

**Two caveats on ClojureWasm 1.11.0.** Build `cljw` from source: the published
1.11.0 crashes on `plato theme`, and the fix is already on the ClojureWasm
`staging` branch. And set `CLJW_GC_THRESHOLD_MB` when running the binary:

~~~bash
CLJW_GC_THRESHOLD_MB=4096 ./dist/plato build talk.md -o talk.html
~~~

Without it a document past a few hundred blocks fails with an `ArityError`
carrying a nonsensical arity, or a segfault. The cause is a garbage-collection
timing bug in ClojureWasm’s VM call path, not in plato — a function value is
swept while a call to it is in flight, so both never collecting
(`CLJW_GC_THRESHOLD_MB=4096`) and collecting constantly (`=1`) avoid it, and
which input sizes trip it depends on heap layout. Tracked in ClojureWasm.

## Theming

`theme/plato.tokens.edn` is the source of truth for colors, scale and type. One command projects it to every consumer:

| Artifact | Consumer |
| --- | --- |
| `public/css/plato-theme.css` | `:root` custom properties, imported by `public/css/plato.css` |
| `src/plato/theme.cljc` | the tokens as Clojure data, `:rules` included — `plato.color`’s palette is derived from it |
| `theme/plato.tokens.json` | language-neutral manifest (value + CSS variable per token) |

~~~bash
npm run theme      # regenerate the default theme's artifacts
~~~

Generated artifacts are committed, and `plato.tokens-test` fails when they drift from the source.

A deck can carry its own theme the way a LaTeX document carries a style file: `theme/acme.tokens.edn` is the same contract with different values, generated to `public/css/acme-theme.css` and linked after `plato.css`. Swapping it restyles the deck without touching deck data.

Scene colors and CSS colors come from the same tokens: a scene graph names `:teal`, `plato.color` resolves it, and `--plato-teal` carries the identical value into the stylesheet.

## Static export

Any deck renders to a standalone Reveal page with no shadow-cljs and no browser. One CLI does
it, and the same CLI generates themes:

~~~bash
plato build talk.md -o dist/talk.html --tokens theme/acme.tokens.edn --assets public
plato theme theme/acme-print.tokens.edn -o public/css/acme-print-theme.css
~~~

`--assets <dir>` copies the `vendor/` and `css/` trees the page links, which is what makes the
output actually standalone. `--math` opts into the math plugin; it is off by default because the
vendored build fetches KaTeX from a CDN, and a page that never asked for math should not phone
home. From the REPL the same thing is a function call:

~~~clojure
(require '[plato.html :as html] '[plato.markdown :as markdown])

(spit "dist/talk.html"
      (html/deck->html (markdown/->deck (slurp "talk.md"))
                       {:asset-base "." :theme "night" :stylesheets ["acme-theme.css"]}))
~~~

`plato.snapshot/write-svg!` renders a Desargues scene’s final state to a standalone SVG.

## Layers

| Layer | Namespace | Runtime |
| --- | --- | --- |
| Deck + content model | `plato.deck`, `plato.content` | CLJ/CLJS/cljw |
| Front ends | `plato.source`, `plato.doc`, `plato.markdown`, `plato.org`, `plato.data` | CLJ/CLJS/cljw |
| Animation core | `plato.timeline`, `plato.tween`, `plato.clock` | CLJ/CLJS/cljw |
| Render core | `plato.render`, `plato.geometry`, `plato.hiccup`, `plato.snapshot` | CLJ/CLJS/cljw |
| Text primitives | `plato.text`, `plato.nfd` | CLJ/CLJS/cljw |
| Theming | `plato.tokens`, `plato.css`, `plato.theme`, `plato.json` | CLJ/CLJS/cljw |
| Static export + CLI | `plato.html`, `plato.cli` | CLJ/cljw |
| Browser shell | `plato.core`, `plato.reveal`, `plato.scene-view`, `plato.player` | CLJS |

See [docs/architecture.md](docs/architecture.md).

## Desargues compatibility

Plato depends on Desargues’ data contract, not its Manim implementation, and accepts both RecordingBackend outputs:

- imperative scenes from <code>desargues.scene/render!</code>
- declarative layout scenes from <code>desargues.scene/render-layout!</code>

Add a scene node kind with a `plato.render/node->hiccup` method; add an animation with a `plato.tween/build-span` method and its channel entry.

## License

MIT.
