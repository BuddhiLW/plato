// The pure half of scripts/pdf.mjs: what a printed page's metadata becomes in
// the Beamer wrapper. The printing half needs a browser and is exercised by
// running the script on a deck.
//   node --test test/pdf.test.mjs
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { labelled, transitionOf, wrapper } from '../scripts/pdf.mjs';

const page = (id, h, v, more = {}) => ({ id, h, v, transition: 'slide', notes: '', links: [], ...more });

test('the first page enters with no transition', () => {
  assert.equal(transitionOf(page('a', 0, 0), undefined), '');
});

test('a fragment step of the same slide dissolves, whatever the slide declares', () => {
  const a = page('a', 0, 0, { transition: 'zoom' });
  assert.equal(transitionOf(a, a), '\\transdissolve[duration=0.3]');
});

test('a new column pushes in from the right, a slide down a stack rises from below', () => {
  assert.match(transitionOf(page('b', 1, 0), page('a', 0, 0)), /transpush\[direction=180/);
  assert.match(transitionOf(page('b', 0, 1), page('a', 0, 0)), /transpush\[direction=90/);
});

test('each Reveal style has a Beamer one, and an unknown style falls to slide', () => {
  const prev = page('a', 0, 0);
  const of = t => transitionOf(page('b', 1, 0, { transition: t }), prev);
  assert.match(of('fade'), /transdissolve\[duration=0.6/);
  assert.match(of('convex'), /transcover/);
  assert.match(of('concave'), /transuncover/);
  assert.match(of('zoom'), /transboxout/);
  assert.equal(of('none'), '\\transreplace');
  assert.match(of('fade-in slide-out'), /transdissolve/);
  assert.match(of('sparkle'), /transpush/);
});

test('slides without ids are told apart by where they stood', () => {
  assert.match(transitionOf(page(null, 0, 0), page(null, 0, 0)), /transdissolve\[duration=0.3/);
  assert.match(transitionOf(page(null, 1, 0), page(null, 0, 0)), /transpush/);
});

test('fragment steps share a slide label and count up as overlays', () => {
  const got = labelled({ pages: [page('a', 0, 0), page('a', 0, 0), page('b', 1, 0)] });
  assert.deepEqual(got.map(p => [p.slide, p.overlay]), [[1, 0], [1, 1], [2, 0]]);
});

test('the wrapper has one plain frame per page, at the printed page size', () => {
  const tex = wrapper({ width: 960, height: 700, pages: [page('a', 0, 0), page('b', 1, 0)] },
                      '/tmp/p.pdf', { transitions: true });
  assert.equal(tex.match(/\\begin\{frame\}\[plain\]/g).length, 2);
  assert.match(tex, /paperwidth=720bp,paperheight=525bp/);
  assert.match(tex, /page=2\]\{\/tmp\/p\.pdf\}/);
  assert.equal(tex.match(/\\trans/g).length, 1);
});

test('--no-transitions leaves every page a plain cut', () => {
  const tex = wrapper({ width: 960, height: 700, pages: [page('a', 0, 0), page('b', 1, 0)] },
                      '/tmp/p.pdf', { transitions: false });
  assert.doesNotMatch(tex, /\\trans/);
});

test('a web link keeps its place, measured from the bottom of the page', () => {
  const links = [{ href: 'https://acme.example/q4?a=1&b=2#x_y', x: 100, y: 200, w: 40, h: 20 }];
  const tex = wrapper({ width: 960, height: 700, pages: [page('a', 0, 0, { links })] },
                      '/tmp/p.pdf', { transitions: true });
  // 700px tall -> 525bp; the box's bottom edge is at (200 + 20) * 0.75 = 165bp from the top.
  assert.match(tex, /\\put\(75\.00,360\.00\)\{\\href\{https:\/\/acme\.example\/q4\?a=1\\&b=2\\#x\\_y\}/);
  assert.match(tex, /\\rule\{30\.00bp\}\{15\.00bp\}/);
});

test("a link to another slide jumps to that slide's first page; a dead one is dropped", () => {
  const links = [{ href: '#/b', x: 0, y: 0, w: 10, h: 10 }, { href: '#/nowhere', x: 0, y: 0, w: 10, h: 10 }];
  const tex = wrapper({ width: 960, height: 700,
                        pages: [page('a', 0, 0, { links }), page('b', 1, 0), page('b', 1, 0)] },
                      '/tmp/p.pdf', { transitions: true });
  assert.equal(tex.match(/\\hyperlink\{pg2\}/g).length, 1);
  assert.doesNotMatch(tex, /nowhere/);
});
