# Beamer PDFs from a plato deck, via AutoPDF

plato parses Markdown and Org into `plato.doc` and renders a Reveal deck.
[AutoPDF](https://github.com/BuddhiLW/AutoPDF) compiles LaTeX and owns a live
preview pipeline. Together, one `.org` or `.md` source produces both a browser
deck and a Beamer PDF.

```bash
plato spec talk.org -o talk.json     # plato: parse and project
autopdf spec talk.json -o talk.pdf   # AutoPDF: compile
```

## Where the seam is

`plato.doc` is the IR both front ends already parse to, and it is the join. plato
has always had `doc/document->deck`; `plato.spec/document->spec` is its sibling,
projecting the same IR to AutoPDF's `DocumentSpec`.

```
talk.org ──markdown/org──> plato.doc ──┬── doc/document->deck ──> Reveal deck
                                        └── spec/document->spec ─> DocumentSpec JSON ──> PDF
```

The seam is deliberately *not* `plato.source/->deck` — a deck is Reveal-shaped —
and *not* `plato.content/render`, whose output is hiccup. Projecting from the IR
is what keeps one parse feeding two targets.

`plato.spec` is pure and lives in `.cljc`, so it runs on the JVM, in the browser
and in the native ClojureWasm binary like the rest of the deck model. Nothing in
plato depends on Go, and nothing in AutoPDF parses Org.

## The vocabulary

The kind names, their props, and how each degrades in print are specified once,
in AutoPDF's [`docs/plato-integration.md`](https://github.com/BuddhiLW/AutoPDF/blob/main/docs/plato-integration.md).
That document is the contract for both sides; this page does not restate it,
because two copies of a vocabulary is how a vocabulary drifts.

What matters on the plato side:

- Every `plato.content` constructor has a kind, so authoring does not change.
- `content/group` flattens into its parent; `content/fragment` becomes an
  overlay annotation on what it wraps rather than a node of its own.
- `:video`, `:audio` and `:embed` have no PDF equivalent that works outside
  Adobe Reader. They project to a visible placeholder — poster, caption, URL —
  never to silence.
- Desargues scenes use the SVG `plato.snapshot/write-svg!` already produces.

## Theming stays single-source

`theme/plato.tokens.edn` already projects to CSS custom properties, a Clojure
namespace and a language-neutral JSON manifest. The Beamer style file is a
fourth projection from the same tokens, so a deck and its printed form carry
identical colors — the same discipline `plato.tokens-test` already enforces for
the other three.

## Live preview

AutoPDF's preview session keeps TeX auxiliary state warm, cancels superseded
builds, and transmits only pages that actually changed. Editing the source
updates the PDF pane without a full rebuild.

It is not as fast as the shadow-cljs reload, and it cannot be: a Beamer deck
compiles in roughly one to three seconds, and cross-references need a second
pass. Use the browser deck as the instant surface and the PDF pane as the one
that trails by a compile.
