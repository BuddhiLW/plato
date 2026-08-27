# Authoring

A plato deck is a value. Three front ends produce it — Clojure data, Markdown, and Org — and every
front end lands on the same `plato.deck/deck` map, which is what the browser shell, the static HTML
exporter and the test suite all consume.

~~~
Clojure data ─┐
Markdown ─────┼──► plato.doc IR ──► plato.deck/deck ──┬──► plato.core   (Reagent + Reveal)
Org ──────────┘                                       ├──► plato.html   (standalone page)
                                                      └──► tests
~~~

## 1. Deck data

~~~clojure
(deck/deck
 {:title  "My talk"
  :config {:transition :slide :hash true}
  :slides [(deck/slide :intro [:h1 "Hello"] {:notes "Speaker-only"})
           (deck/stack :details
                       [(deck/slide :one [:p "First vertical slide"])
                        (deck/slide :two [:p "Second"])])]})
~~~

- `deck/slide` — `[id content]` or `[id content opts]`.
- `deck/stack` — `[id slides]` or `[id slides opts]`; renders as a vertical column.
- `deck/deck` — validates: at least one slide, well-formed entries, unique ids. It throws rather
  than producing a deck that renders wrong.
- `deck/leaf-slides` — every slide in presentation order, stacks flattened.

### Slide options

Any option in `plato.deck/reveal-data-keys` becomes a `data-*` attribute on the slide’s
`<section>`; `plato.deck/section-attrs` is the single definition both render targets use.

| Option | Effect |
| --- | --- |
| `:background-color` `:background-gradient` `:background-image` | slide background |
| `:background-video` `:background-video-loop` `:background-video-muted` | video background |
| `:background-iframe` `:background-interactive` | live page as background |
| `:background-opacity` `:background-size` `:background-position` `:background-repeat` | background tuning |
| `:transition` `:transition-speed` | per-slide transition |
| `:auto-animate` `:auto-animate-id` `:auto-animate-duration` `:auto-animate-easing` `:auto-animate-restart` `:auto-animate-unmatched` | Reveal auto-animate between consecutive slides |
| `:visibility` `:state` `:timing` `:preload` | Reveal slide behaviour |
| `:notes` | speaker notes (rendered as `<aside class="notes">`) |

`:notes` is a **content value**, not a string: hiccup, a `plato.content` map, or plain text all work,
and both markdown and Org parse their note bodies rather than capturing them as raw text. So a note
holding a list arrives as a list, and the two front ends produce the identical IR for the same note.

## 2. Content

A slide’s `:content` may be:

- hiccup — `[:div [:h1 "Title"]]`
- a string — becomes a **native Reveal markdown section** (`data-markdown`), so the Reveal markdown
  plugin parses it in the browser
- a Reagent component fn
- a `plato.content` value
- a `plato.desargues/scene`

`plato.content` values are plain maps tagged with `:plato/type`, rendered by the `plato.content/render`
multimethod. Content maps nested inside hiccup are expanded automatically:

~~~clojure
[:div
 (content/kicker "Q3 FY26")
 [:h2 "Revenue"]
 (content/columns [(content/image "assets/chart.png" {:caption "Booked revenue"})
                   (content/bullets ["ARR $118.4M" "NRR 119%"] {:fragments? true})])]
~~~

### Constructors

| Call | Notes |
| --- | --- |
| `(content/image src opts)` | `:alt :caption :width :height :fit :lazy?` — GIFs are images |
| `(content/video src opts)` | `:sources [{:src :type}] :poster :caption :controls? :autoplay? :loop? :muted?` |
| `(content/audio src opts)` | `:caption :controls? :autoplay? :loop?` |
| `(content/embed src opts)` | `:title :ratio :caption :lazy?` |
| `(content/code lang source opts)` | `:highlight "1|3-5|all"` for stepped highlighting, `:caption` |
| `(content/quotation text opts)` | `:cite` |
| `(content/bullets items opts)` | `:ordered? :fragments? :effect` |
| `(content/table head rows opts)` | `:caption`; `head` may be `nil` |
| `(content/columns items opts)` | `:gap :widths` |
| `(content/cards items opts)` | items are `{:title :body :icon}`; `:columns :fragments?` |
| `(content/group items opts)` | vertical sequence |
| `(content/fragment content opts)` | `:effect :index` |
| `(content/note content opts)` | `:tone :info/:warn/:ok`, `:title` |
| `(content/kicker text)` | small uppercase label |
| `(content/markdown text)` | Markdown parsed by the Reveal plugin at display time |
| `(content/html source)` | raw HTML |

`:autoplay?` on video and audio emits Reveal’s `data-autoplay`, so playback starts when the slide is
shown rather than when the page loads.

### Adding a content kind

~~~clojure
(defmethod content/render :timeline [{:keys [events]}]
  (into [:ol.acme-timeline] (map (fn [e] [:li (:label e)])) events))

(defn timeline [events] {:plato/type :timeline :events events})
~~~

That is the whole extension point: one `defmethod`, no edit to the deck model or the browser shell.

## 3. Markdown

~~~markdown
---
title: Acme Corp — Q3 Product Review
author: Ada Lovelace
---

# Q3 Product Review

<!-- .slide: data-background-image="assets/acme/hero.jpg" data-transition="fade" -->

Revenue, **delivery**, and the *three bets* that carry us into [Q4](https://acme.example/q4).

Note: Ninety seconds of framing.

## Revenue Chart

![Bar chart](assets/acme/chart.png "Booked revenue, FY26")
~~~

| Construct | Meaning |
| --- | --- |
| `---` front matter at the top | `key: value` pairs; `title` becomes the deck title |
| `# Heading` | opens a horizontal slide |
| `## Heading` | opens a vertical slide inside the preceding horizontal one |
| `###`+ | a heading *inside* the current slide |
| a lone `---` / `--` line | explicit horizontal / vertical separator (Reveal convention) |
| `<!-- .slide: data-x="y" -->` | slide options for the slide it appears in |
| `Note:` | speaker notes to the end of the slide |
| `- item`, `1. item` | lists |
| ```` ```clojure ```` fence | a code block; separators inside a fence do **not** split slides |
| `> quote` | block quote |
| `\| a \| b \|` with a `\|---\|` row | table |
| `![alt](src "caption")` on its own line | image; `.mp4/.webm/.ogv` become video, `.mp3/.wav/.ogg` audio |
| `**bold**` `*em*` `` `code` `` `~~strike~~` `[text](href)` | inline formatting |

## 4. Org

The same deck, in Org:

~~~org
#+TITLE: Acme Corp — Q3 Product Review
#+AUTHOR: Ada Lovelace

* Q3 Product Review
:PROPERTIES:
:BACKGROUND_IMAGE: assets/acme/hero.jpg
:TRANSITION: fade
:END:

Revenue, *delivery*, and the /three bets/ that carry us into [[https://acme.example/q4][Q4]].

:NOTES:
Ninety seconds of framing.
:END:

** Revenue Chart

#+CAPTION: Booked revenue, FY26
[[file:assets/acme/chart.png][Bar chart]]
~~~

| Construct | Meaning |
| --- | --- |
| `#+TITLE:` | deck title; other `#+KEY:` lines become document metadata |
| `* Headline` / `** Headline` | horizontal / vertical slide; TODO keywords and `:tags:` are stripped |
| `:PROPERTIES: … :END:` | slide options (`:BACKGROUND_COLOR:`, `:TRANSITION:`, `:CUSTOM_ID:`, …) |
| `:NOTES: … :END:` or `#+BEGIN_NOTES` | speaker notes |
| `#+BEGIN_SRC lang … #+END_SRC` | code block |
| `#+BEGIN_QUOTE`, `#+BEGIN_EXAMPLE`, `#+BEGIN_EXPORT html` | quote, example, raw HTML |
| `#+CAPTION:` / `#+ATTR_PLATO: :key value` | caption and extra options for the next block |
| `- item`, `1. item`, `- [X] item` | lists, including checkboxes |
| `\| a \| b \|` with a `\|--\|` row | table |
| `[[file:…]]`, `[[href][text]]` | media and links |
| `*bold*` `/italic/` `=verbatim=` `~code~` `+strike+` | inline formatting |

Both front ends expose the same API:

~~~clojure
(markdown/parse text)   ; -> document IR
(markdown/->deck text)  ; -> validated deck
(org/parse text)
(org/->deck text)
~~~

`docs/acme.md` and `docs/acme.org` are the same deck written twice; the test suite asserts they
compile to the same slide ids, and both render byte-identical HTML.

## 5. Theming

Colors, spacing and type live in one EDN file, not in the stylesheet:

~~~clojure
;; theme/acme.tokens.edn
{:meta  {:prefix "plato" :name "Acme Amber" :reveal-theme "night"}
 :color {:bg "#12100e" :accent "#f59e0b" :teal "#35C1A0" …}
 :scale {:radius "0.5rem" :media-max "420px" …}
 :type  {:sans "…" :mono "…"}
 :scene {:palette [:teal :gold …] :background :bg :fallback :grey}}
~~~

Each leaf key becomes one CSS custom property — `--plato-accent`, `--plato-media-max` — and the
`:scene` palette is what `plato.color` resolves scene-graph color keywords against. One source, so a
color cannot drift between the stylesheet and the animation.

~~~bash
plato theme theme/acme.tokens.edn -o public/css/acme-theme.css
npm run theme     # regenerates the default theme's css, json manifest and plato.theme namespace
~~~

Link the generated file after `plato.css` and the deck is restyled — nothing in the deck data changes.
Sizes are given in **slide-box pixels**, not viewport units: Reveal transform-scales the slide
(960×700 by default), so `vh` does not describe the space a slide actually has.

### Extending a theme

A theme may `:extends` another and state only what differs. Everything it does not mention —
colors, scales, type, scene palette — is inherited:

~~~clojure
;; theme/acme-print.tokens.edn
{:extends "acme.tokens.edn"
 :meta  {:name "Acme Print" :reveal-theme "white"}
 :color {:bg "#ffffff" :fg "#1a1714" :muted "#6b6257"}}
~~~

A relative `:extends` resolves against the file that declares it, and chains: a theme may extend a
theme that extends another. `plato theme` resolves the chain and generates one stylesheet.

### Rules, not just variables

Custom properties cover most theming, but a theme sometimes needs real rules. `:rules` is CSS as
data — nesting, `&`, selector groups and at-rules, which is what a theme wanted Sass for:

~~~clojure
:rules
[[:.reveal {:background [:token :bg] :color [:token :fg]}
  [:h1 {:color [:token :fg]}]
  [:.plato-kicker {:color [:token :accent] :letter-spacing "0.08em"}]]

 ["@media print"
  [:.reveal {:font-size "11pt"}
   [:.plato-transport {:display "none"}]]]]
~~~

`[:token k]` stands for the `var()` reference to token `k`, so a theme names tokens and never the
generated custom-property spelling. The rules are emitted after the `:root` block, in source order —
order is the cascade, so the emitter never reorders. `plato.css/rules->css` is the whole language.

### JSON sources

A token file may be JSON instead of EDN; the shape is identical, so a design tool can own the file:

~~~bash
plato theme brand.tokens.json -o dist/brand.css
~~~

The extension picks the reader. Scene color references come back as keywords either way.

## 6. Building

~~~bash
clojure -M:cli build docs/acme.md -o dist/acme.html --tokens theme/acme.tokens.edn --assets public
clojure -M:cli build --deck plato.acme.deck/model --out dist/acme-static.html
./dist/plato build talk.org -o talk.html --theme-css house.css   # native binary
~~~

`--assets <dir>` copies the `vendor/` and `css/` trees the page links from `<dir>`, which is what
makes the export standalone — without it the page links assets it does not carry. `--math` opts into
the math plugin, off by default because the vendored build fetches KaTeX from a CDN.

`--deck` loads a deck from a var, so a Clojure-authored deck exports the same way a Markdown one does.
It needs the deck’s sources on the classpath, which the JVM (`clojure -M:cli`) and `cljw -cp` provide;
the prebuilt binary carries only plato’s own namespaces.

In a static export a Desargues scene renders as its **final frame** — the same scene is a scrub-able
player in the browser. Both projections come from one `plato.render/scene-svg`.
