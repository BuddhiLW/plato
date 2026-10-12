#!/usr/bin/env node
// Print a built plato deck to a PDF that looks like the browser deck and is safe
// in any viewer: one page per fragment step, Beamer page transitions mapped from
// the deck's Reveal ones, links kept, speaker notes beside it.
//
//   node scripts/pdf.mjs dist/talk/index.html -o talk.pdf
//
// Three legs, in an order that is not free:
//   1. Chromium prints the page in Reveal's ?print-pdf mode.
//   2. Ghostscript recompresses the images. It drops /Trans, so it runs BEFORE 3.
//   3. Beamer wraps each page in a plain frame and stamps its \trans* on it.
//      (pdfpages would be simpler, and discards \pdfpageattr.)
// A viewer that knows no transitions cuts between pages; one that knows no
// .pdfpc never opens it. The worst case is a plain, correct PDF.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const USAGE = `usage: node scripts/pdf.mjs <deck.html | deck-dir> [-o out.pdf]
  -o, --out <file>      output PDF (default: <deck>.pdf beside the page)
  --no-transitions      plain page cuts
  --no-compress         skip the Ghostscript leg
  --dpi <n>             image resolution kept by Ghostscript (default 200)
  --engine <tex>        lualatex (default when installed) or pdflatex
  --keep                keep the work directory and print where it is
Writes <out>.pdf, <out>.pdfpc (notes for pdfpc) and <out>.notes.md.`;

function parseArgs(argv) {
  const o = { transitions: true, compress: true, dpi: 200, keep: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '-o' || a === '--out') o.out = argv[++i];
    else if (a === '--no-transitions') o.transitions = false;
    else if (a === '--no-compress') o.compress = false;
    else if (a === '--dpi') o.dpi = Number(argv[++i]);
    else if (a === '--engine') o.engine = argv[++i];
    else if (a === '--keep') o.keep = true;
    else if (a === '-h' || a === '--help') o.help = true;
    else if (a.startsWith('-')) throw new Error(`unknown option ${a}`);
    else o.deck = a;
  }
  return o;
}

const has = bin => spawnSync('sh', ['-c', `command -v ${bin}`]).status === 0;

function run(bin, args, opts = {}) {
  const r = spawnSync(bin, args, { encoding: 'utf8', ...opts });
  if (r.status !== 0) throw new Error(`${bin} failed (${r.status})\n${(r.stderr || r.stdout || '').slice(-2000)}`);
  return r;
}

// The npm playwright and the browsers in its cache drift apart; a deck should
// still print with whichever Chromium is there.
async function launch() {
  if (process.env.CHROME_PATH) return chromium.launch({ executablePath: process.env.CHROME_PATH });
  try {
    return await chromium.launch();
  } catch (e) {
    const cache = process.env.PLAYWRIGHT_BROWSERS_PATH || path.join(os.homedir(), '.cache', 'ms-playwright');
    const builds = fs.existsSync(cache)
      ? fs.readdirSync(cache).filter(d => /^chromium-\d+$/.test(d)).sort().reverse() : [];
    for (const b of builds) {
      for (const sub of fs.readdirSync(path.join(cache, b))) {
        const exe = path.join(cache, b, sub, 'chrome');
        if (fs.existsSync(exe)) return chromium.launch({ executablePath: exe });
      }
    }
    throw new Error(`no Chromium to print with: run \`npx playwright install chromium\` or set CHROME_PATH\n${e.message}`);
  }
}

// ---- leg 1: print -----------------------------------------------------------

async function print(deckHtml, outPdf) {
  const browser = await launch();
  try {
    const page = await browser.newPage();
    page.on('pageerror', e => console.error('page error:', e.message));
    // One PDF page per step: overflow is fitted below, never spilled onto a page of its own.
    await page.addInitScript(() => {
      let R;
      Object.defineProperty(window, 'Reveal', {
        configurable: true,
        get: () => R,
        set: v => {
          const init = v.initialize.bind(v);
          v.initialize = o => {
            // Print mode flattens the stacks; a slide remembers where it stood.
            document.querySelectorAll('.reveal .slides > section').forEach((col, h) => {
              const leaves = col.querySelectorAll(':scope > section');
              (leaves.length ? [...leaves] : [col]).forEach((s, v) => {
                s.setAttribute('data-plato-h', h);
                s.setAttribute('data-plato-v', v);
              });
            });
            return init(Object.assign({}, o, { pdfMaxPagesPerSlide: 1, pdfSeparateFragments: true }));
          };
          R = v;
        }
      });
    });
    await page.goto(pathToFileURL(deckHtml).href + '?print-pdf', { waitUntil: 'load' });
    await page.waitForFunction(() => window.Reveal && Reveal.isReady && Reveal.isReady(), null, { timeout: 60000 });
    await page.waitForFunction(() => document.querySelectorAll('.pdf-page').length > 0, null, { timeout: 60000 });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForFunction(() => [...document.images].every(i => i.complete), null, { timeout: 60000 });

    const meta = await page.evaluate(() => {
      const cfg = Reveal.getConfig();
      let fitted = 0;
      // The notes are display:none, where innerText forgets every line break.
      const noteText = el => {
        const blocks = [...el.querySelectorAll('p, li')].filter(b => !b.querySelector('p, li'));
        const line = b => (b.tagName === 'LI' ? '- ' : '') + b.textContent.replace(/\s+/g, ' ').trim();
        return (blocks.length ? blocks.map(line).join('\n\n') : el.textContent.trim()).replace(/\n\n- /g, '\n- ');
      };
      const pages = [...document.querySelectorAll('.pdf-page')].map(p => {
        const s = p.querySelector('section');
        if (!s) return { transition: cfg.transition, notes: '', links: [] };
        // A box that scrolls on screen cannot scroll on paper: let it grow, then fit the slide.
        for (const el of s.querySelectorAll('*')) {
          if (el.scrollHeight > el.clientHeight + 2 &&
              /auto|scroll|hidden/.test(getComputedStyle(el).overflowY)) {
            el.style.overflow = 'visible';
            el.style.maxHeight = 'none';
            el.style.height = 'auto';
          }
        }
        const box = p.getBoundingClientRect();
        const avail = p.clientHeight - s.offsetTop;
        if (s.scrollHeight > avail + 2) {
          // reveal.css pins transform:none!important in print; zoom is untouched.
          // The slide keeps its width on the page (wider in its own units), so the
          // text reflows into longer lines and needs less shrinking than a ratio says:
          // search for the largest zoom that fits rather than computing one.
          const width = s.clientWidth;
          const fitsAt = k => {
            s.style.zoom = k;
            s.style.width = `${width / k}px`;
            const r = s.getBoundingClientRect();
            return r.right <= box.right + 1 && s.scrollHeight * k <= avail + 2;
          };
          let lo = 0.4, hi = 1;
          for (let i = 0; i < 12; i++) {
            const mid = (lo + hi) / 2;
            if (fitsAt(mid)) lo = mid; else hi = mid;
          }
          fitsAt(lo);
          fitted++;
        }
        const notes = s.querySelector('aside.notes');
        const links = [...s.querySelectorAll('a[href]')].flatMap(a => {
          if (a.closest('aside.notes')) return [];
          const cs = getComputedStyle(a);
          if (cs.visibility === 'hidden' || cs.display === 'none') return [];
          return [...a.getClientRects()].filter(r => r.width > 1 && r.height > 1).map(r => ({
            href: a.getAttribute('href'),
            x: r.left - box.left, y: r.top - box.top, w: r.width, h: r.height
          }));
        });
        return {
          id: s.id || null,
          h: Number(s.getAttribute('data-plato-h')),
          v: Number(s.getAttribute('data-plato-v')),
          transition: s.getAttribute('data-transition') || cfg.transition,
          notes: notes ? noteText(notes) : '',
          title: (s.querySelector('h1,h2,h3') || {}).innerText || '',
          zoom: s.style.zoom ? Number(s.style.zoom) : 1,
          links
        };
      });
      const r = document.querySelector('.pdf-page').getBoundingClientRect();
      const broken = [...new Set([...document.images].filter(i => !i.naturalWidth).map(i => i.getAttribute('src')))];
      return { width: r.width, height: r.height, fitted, broken, pages };
    });

    await page.pdf({ path: outPdf, preferCSSPageSize: true, printBackground: true,
                     margin: { top: 0, right: 0, bottom: 0, left: 0 } });
    return meta;
  } finally {
    await browser.close();
  }
}

// ---- leg 3: the Beamer wrapper ----------------------------------------------

// Reveal transition -> Beamer. An unknown style falls to the deck's default, and
// a viewer that knows none of them cuts: a wrong guess costs an effect, never a page.
const HORIZONTAL = {
  fade:    '\\transdissolve[duration=0.6]',
  slide:   '\\transpush[direction=180,duration=0.6]',
  convex:  '\\transcover[direction=180,duration=0.6]',
  concave: '\\transuncover[direction=180,duration=0.6]',
  zoom:    '\\transboxout[duration=0.6]',
  none:    '\\transreplace'
};
// A fragment step on the same slide: a short dissolve, like Reveal's fade-up.
const STEP = '\\transdissolve[duration=0.3]';

export function transitionOf(page, prev) {
  if (!prev) return '';
  if (page.id ? page.id === prev.id : (page.h === prev.h && page.v === prev.v)) return STEP;
  const style = (page.transition || 'slide').split(/[\s-]/)[0];
  const t = HORIZONTAL[style] || HORIZONTAL.slide;
  // Down a vertical stack the new slide rises from below, as it does in Reveal.
  return page.h === prev.h && page.v > prev.v ? t.replace('direction=180', 'direction=90') : t;
}

const texUrl = u => u.replace(/([#%&_{}\\~^])/g, m =>
  m === '\\' ? '/' : m === '~' ? '\\string~' : m === '^' ? '\\string^' : '\\' + m);

const bp = px => (px * 0.75).toFixed(2);

export function wrapper(meta, printedPdf, { transitions }) {
  const W = meta.width * 0.75, H = meta.height * 0.75;
  // A deck link to another slide ("#/id") becomes a jump to that slide's first page.
  const firstPage = new Map();
  meta.pages.forEach((p, i) => { if (p.id && !firstPage.has(p.id)) firstPage.set(p.id, i + 1); });
  const link = l => {
    const box = `\\phantom{\\rule{${bp(l.w)}bp}{${bp(l.h)}bp}}`;
    const at = `\\put(${bp(l.x)},${(H - (l.y + l.h) * 0.75).toFixed(2)})`;
    const inner = /^#\/?(.+)$/.exec(l.href);
    if (inner) {
      const target = firstPage.get(decodeURIComponent(inner[1]));
      return target ? `${at}{\\hyperlink{pg${target}}{${box}}}` : '';
    }
    return /^(https?:|mailto:)/.test(l.href) ? `${at}{\\href{${texUrl(l.href)}}{${box}}}` : '';
  };
  const frames = meta.pages.map((p, i) => {
    const bg = `\\setlength{\\unitlength}{1bp}\\begin{picture}(${W.toFixed(2)},${H.toFixed(2)})` +
      `\\put(0,0){\\includegraphics[width=\\paperwidth,height=\\paperheight,page=${i + 1}]{${printedPdf}}}` +
      p.links.map(link).join('') + '\\end{picture}';
    const trans = transitions ? transitionOf(p, meta.pages[i - 1]) : '';
    return `{\\usebackgroundtemplate{${bg}}\\begin{frame}[plain]\\hypertarget{pg${i + 1}}{}${trans}\\end{frame}}`;
  });
  return [
    // PDF 1.5, the level that defines Push/Cover/Uncover; LuaTeX spells the knob differently.
    '\\ifdefined\\pdfvariable\\pdfvariable minorversion=5 \\else\\pdfminorversion=5 \\fi',
    '\\documentclass{beamer}',
    `\\geometry{paperwidth=${W}bp,paperheight=${H}bp}`,
    '\\setbeamertemplate{navigation symbols}{}',
    '\\hypersetup{pdfborder={0 0 0}}',
    '\\begin{document}',
    ...frames,
    '\\end{document}',
    ''
  ].join('\n');
}

// ---- notes ------------------------------------------------------------------

// Fragment steps of one slide share a label and count up as overlays, which is
// how pdfpc knows they are one slide.
export function labelled(meta) {
  let slide = 0, overlay = 0;
  return meta.pages.map((p, i) => {
    const prev = meta.pages[i - 1];
    const same = prev && (p.id ? p.id === prev.id : (p.h === prev.h && p.v === prev.v));
    if (same) overlay++; else { slide++; overlay = 0; }
    return { ...p, slide, overlay };
  });
}

function writeNotes(meta, base) {
  const pages = labelled(meta);
  // pdfpc reads <name>.pdfpc beside <name>.pdf; other viewers never look at it.
  fs.writeFileSync(base + '.pdfpc', JSON.stringify({
    pdfpcFormat: 2,
    disableMarkdown: true,
    pages: pages.map((p, i) => ({ idx: i, label: String(p.slide), overlay: p.overlay,
                                  forcedOverlay: false, note: p.notes || undefined }))
  }, null, 1));
  // And a plain script, for a venue with no presenter console at all.
  const md = pages.filter(p => p.overlay === 0).map(p => {
    const page = pages.indexOf(p) + 1;
    return `## ${p.slide}. ${p.title.replace(/\s+/g, ' ').trim() || p.id || ''} (p. ${page})\n\n${p.notes || '_no notes_'}\n`;
  });
  fs.writeFileSync(base + '.notes.md', md.join('\n'));
  return pages;
}

// ---- main -------------------------------------------------------------------

async function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.help || !o.deck) { console.log(USAGE); process.exit(o.help ? 0 : 2); }
  let deck = path.resolve(o.deck);
  if (fs.statSync(deck).isDirectory()) deck = path.join(deck, 'index.html');
  const out = path.resolve(o.out || deck.replace(/\.html?$/, '') + '.pdf');
  const base = out.replace(/\.pdf$/, '');
  // LuaTeX shares one copy of the fonts across the included pages; pdfTeX repeats
  // them on every page (2.8 MB against 12 MB on a 150-page deck).
  o.engine = o.engine || ['lualatex', 'pdflatex'].find(has);
  if (!o.engine || !has(o.engine)) throw new Error('no lualatex or pdflatex: the transitions leg needs a TeX install with beamer');

  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'plato-pdf-'));
  const printed = path.join(work, 'printed.pdf');
  const meta = await print(deck, printed);
  fs.writeFileSync(path.join(work, 'meta.json'), JSON.stringify(meta, null, 1));

  let body = printed;
  if (o.compress && has('gs')) {
    body = path.join(work, 'small.pdf');
    // RGB, or pdfTeX re-embeds Ghostscript's ICC profile as an empty stream.
    run('gs', ['-q', '-dNOPAUSE', '-dBATCH', '-dSAFER', '-sDEVICE=pdfwrite', '-dCompatibilityLevel=1.5',
               '-dPDFSETTINGS=/printer', '-sColorConversionStrategy=RGB', '-dDownsampleColorImages=true',
               `-dColorImageResolution=${o.dpi}`, `-sOutputFile=${body}`, printed]);
  } else if (o.compress) {
    console.error('gs not found: images stay at their source size');
  }

  fs.writeFileSync(path.join(work, 'deck.tex'), wrapper(meta, body, o));
  // Twice: \hyperlink targets resolve on the second pass.
  for (let i = 0; i < 2; i++) run(o.engine, ['-interaction=batchmode', '-halt-on-error', 'deck.tex'], { cwd: work });
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.copyFileSync(path.join(work, 'deck.pdf'), out);
  const pages = writeNotes(meta, base);

  const slides = pages[pages.length - 1].slide;
  const links = meta.pages.reduce((n, p) => n + p.links.length, 0);
  console.log(`wrote ${out} (${(fs.statSync(out).size / 1e6).toFixed(1)} MB, ${slides} slides, ${pages.length} pages, ` +
              `${meta.fitted} fitted, ${links} links, ${pages.filter(p => p.notes).length} pages with notes)`);
  // A picture that did not load prints as its alt text: say so, it is the one
  // thing a deck on a stick most often gets wrong.
  for (const src of meta.broken) console.error(`warning: image did not load: ${src}`);
  if (o.keep) console.log(`work directory: ${work}`);
  else fs.rmSync(work, { recursive: true, force: true });
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => { console.error(e.message); process.exit(1); });
}
