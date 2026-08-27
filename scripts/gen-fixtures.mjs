#!/usr/bin/env node
/**
 * Regenerates every fixture asset under public/assets/acme.
 *
 *   node scripts/gen-fixtures.mjs      (or: npm run fixtures)
 *
 * The target directory is wiped and rebuilt from scratch, so it holds exactly
 * what this script produces. Output is deterministic: all art is computed from
 * fixed constants, and every encode is run bit-exact with metadata stripped.
 *
 * Requires: rsvg-convert (librsvg), convert (ImageMagick), ffmpeg, ffprobe.
 */

import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readdir, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "public", "assets", "acme");
const BUDGET = 1_500_000;

/* palette — mirrors public/css/plato.css */
const C = {
  bg: "#0b0e13",
  panel: "#141a22",
  accent: "#f0ac5f",
  teal: "#5cd0b3",
  muted: "#9aa7b5",
  text: "#edf2f7",
  line: "#273140",
  hair: "#1c242f"
};

const RASTER_FONT = "DejaVu Sans, Liberation Sans, sans-serif";
const WEB_FONT = "Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif";

const TAU = Math.PI * 2;

/* ------------------------------------------------------------------ tooling */

const TOOLS = [
  ["rsvg-convert", ["--version"], "librsvg (package: librsvg2-bin / librsvg)"],
  ["convert", ["-version"], "ImageMagick (package: imagemagick)"],
  ["ffmpeg", ["-version"], "FFmpeg (package: ffmpeg)"],
  ["ffprobe", ["-version"], "FFmpeg (package: ffmpeg)"]
];

function requireTools () {
  const missing = [];
  for (const [bin, args, hint] of TOOLS) {
    try { execFileSync(bin, args, { stdio: "ignore" }); }
    catch { missing.push(`  ${bin} is not on PATH — install ${hint}`); }
  }
  if (missing.length) throw new Error(`missing required binaries:\n${missing.join("\n")}`);
}

function run (bin, args) {
  try {
    return execFileSync(bin, args, { stdio: ["ignore", "pipe", "pipe"], maxBuffer: 1 << 26 });
  } catch (err) {
    const tail = String(err.stderr ?? "").trim().split("\n").slice(-10).join("\n");
    throw new Error(`${bin} exited ${err.status ?? "?"}\n  ${bin} ${args.join(" ")}\n${tail}`);
  }
}

/** ffmpeg output options that strip encoder/date metadata so runs are byte-stable. */
const BITEXACT = ["-fflags", "+bitexact", "-flags:v", "+bitexact", "-map_metadata", "-1"];

const ffmpeg = (args) => run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y", ...args]);

/* ------------------------------------------------------------- svg plumbing */

const n = (v) => Number(v.toFixed(3));
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const clamp = (v, lo = 0, hi = 1) => (v < lo ? lo : v > hi ? hi : v);

const svg = (w, h, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`;

function mix (a, b, t) {
  const parts = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const [ar, ag, ab] = parts(a);
  const [br, bg, bb] = parts(b);
  const ch = (x, y) => Math.round(x + (y - x) * t).toString(16).padStart(2, "0");
  return `#${ch(ar, br)}${ch(ag, bg)}${ch(ab, bb)}`;
}

function text (x, y, s, { size = 16, fill = C.text, font = RASTER_FONT, weight = "normal", anchor = "start", spacing = 0 } = {}) {
  const ls = spacing ? ` letter-spacing="${spacing}"` : "";
  return `<text x="${n(x)}" y="${n(y)}" font-family="${font}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}"${ls}>${esc(s)}</text>`;
}

/* ------------------------------------------------------- the ACME identity */
/* Glyphs live on a 40 x 56 cell with a 56-unit advance; stroke width is in those
   local units, so a whole wordmark scales as one piece. No font dependency. */

const GLYPH = {
  A: "M0,56 L20,0 L40,56 M5,42 L35,42",
  C: "M38,13 C34,4 28,0 20,0 C9,0 0,12 0,28 C0,44 9,56 20,56 C28,56 34,52 38,43",
  M: "M0,56 L0,0 L20,30 L40,0 L40,56",
  E: "M38,0 L0,0 L0,56 L38,56 M0,28 L30,28"
};

function wordmark ({ x, y, scale = 1, color = C.text, weight = 9 }) {
  return ["A", "C", "M", "E"]
    .map((ch, i) =>
      `<path d="${GLYPH[ch]}" transform="translate(${n(x + i * 56 * scale)},${n(y)}) scale(${n(scale)})" ` +
      `fill="none" stroke="${color}" stroke-width="${weight}" stroke-linecap="square" stroke-linejoin="miter"/>`)
    .join("");
}

const wordmarkWidth = (scale) => (3 * 56 + 40) * scale;

function hexMark ({ cx, cy, r, weight = 8, ring = C.accent, chevron = C.teal, dot = C.accent, rotate = 0 }) {
  const pts = [90, 150, 210, 270, 330, 30]
    .map((a) => {
      const t = ((a + rotate) * Math.PI) / 180;
      return `${n(cx + r * Math.cos(t))},${n(cy - r * Math.sin(t))}`;
    })
    .join(" ");
  return (
    `<polygon points="${pts}" fill="none" stroke="${ring}" stroke-width="${n(weight)}" stroke-linejoin="round"/>` +
    `<path d="M${n(cx - 0.36 * r)},${n(cy - 0.4 * r)} L${n(cx + 0.14 * r)},${n(cy)} L${n(cx - 0.36 * r)},${n(cy + 0.4 * r)}" ` +
    `fill="none" stroke="${chevron}" stroke-width="${n(weight)}" stroke-linecap="round" stroke-linejoin="round"/>` +
    `<circle cx="${n(cx + 0.44 * r)}" cy="${n(cy)}" r="${n(0.11 * r)}" fill="${dot}"/>`
  );
}

/* ----------------------------------------------------------------- logo.svg */

function logoSvg () {
  const W = 340, H = 120;
  const scale = 0.78;
  const wx = 136;
  const wy = 60 - (56 * scale) / 2;
  const end = wx + wordmarkWidth(scale);
  const body =
    `<rect width="${W}" height="${H}" fill="none"/>` +
    `<g>${hexMark({ cx: 60, cy: 60, r: 44, weight: 8 })}</g>` +
    `<g>${wordmark({ x: wx, y: wy, scale, color: C.text, weight: 9 })}</g>` +
    `<rect x="${n(end + 12)}" y="${n(60 + (56 * scale) / 2 - 13)}" width="13" height="13" fill="${C.teal}"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="ACME">\n  <title>ACME</title>\n  ${body}\n</svg>\n`;
}

/* ----------------------------------------------------------------- hero.jpg */

function heroSvg () {
  const W = 1600, H = 900;
  const defs = [];
  const body = [`<rect width="${W}" height="${H}" fill="${C.bg}"/>`];

  const blobs = [
    ["b0", C.panel, 1, 800, 470, 940],
    ["b1", C.accent, 0.38, 1210, 195, 720],
    ["b2", C.teal, 0.3, 295, 775, 780],
    ["b3", C.accent, 0.16, 430, 200, 380]
  ];
  for (const [id, color, o, cx, cy, r] of blobs) {
    defs.push(
      `<radialGradient id="${id}">` +
      `<stop offset="0" stop-color="${color}" stop-opacity="${o}"/>` +
      `<stop offset="0.5" stop-color="${color}" stop-opacity="${n(o * 0.34)}"/>` +
      `<stop offset="1" stop-color="${color}" stop-opacity="0"/></radialGradient>`
    );
    body.push(`<circle cx="${cx}" cy="${cy}" r="${r}" fill="url(#${id})"/>`);
  }

  /* perspective fan + concentric arcs about a single vanishing point */
  const vx = 1320, vy = 84;
  const fan = [];
  for (let i = 0; i <= 26; i++) fan.push(`M${vx},${vy} L${-620 + i * 112},${H + 60}`);
  body.push(`<path d="${fan.join(" ")}" fill="none" stroke="${C.text}" stroke-opacity="0.05" stroke-width="1.4"/>`);

  const rings = [];
  for (let i = 1; i <= 9; i++) rings.push(`<circle cx="${vx}" cy="${vy}" r="${i * 168}"/>`);
  body.push(`<g fill="none" stroke="${C.accent}" stroke-opacity="0.06" stroke-width="1.6">${rings.join("")}</g>`);

  /* oversized identity watermark, barely above the noise floor */
  body.push(`<g opacity="0.07">${hexMark({ cx: 1210, cy: 470, r: 300, weight: 11 })}</g>`);

  defs.push(
    `<linearGradient id="floor" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="${C.bg}" stop-opacity="0"/>` +
    `<stop offset="1" stop-color="${C.bg}" stop-opacity="0.93"/></linearGradient>`
  );
  body.push(`<rect x="0" y="${n(H * 0.42)}" width="${W}" height="${n(H * 0.58)}" fill="url(#floor)"/>`);

  defs.push(
    `<radialGradient id="vig" cx="0.5" cy="0.5" r="0.76">` +
    `<stop offset="0.4" stop-color="#000000" stop-opacity="0"/>` +
    `<stop offset="1" stop-color="#000000" stop-opacity="0.66"/></radialGradient>`
  );
  body.push(`<rect width="${W}" height="${H}" fill="url(#vig)"/>`);
  body.push(`<rect width="${W}" height="${H}" fill="${C.bg}" opacity="0.16"/>`);

  return svg(W, H, `<defs>${defs.join("")}</defs>${body.join("")}`);
}

/* ---------------------------------------------------------------- chart.png */

function chartSvg () {
  const W = 1200, H = 700;
  const data = [["Q1 2026", 18.4], ["Q2 2026", 22.1], ["Q3 2026", 27.6], ["Q4 2026", 34.2]];
  const max = 40;
  const plot = { x: 132, y: 170, w: 1008, h: 396 };
  const base = plot.y + plot.h;
  const yOf = (v) => plot.y + plot.h * (1 - v / max);
  const slot = plot.w / data.length;
  const barW = 130;

  const body = [`<rect width="${W}" height="${H}" fill="${C.bg}"/>`];

  body.push(text(60, 74, "Acme Corp — quarterly revenue", { size: 36, weight: "bold", fill: C.text }));
  body.push(text(60, 110, "USD millions, fiscal year 2026", { size: 20, fill: C.muted }));

  for (let v = 0; v <= max; v += 10) {
    const y = yOf(v);
    body.push(`<line x1="${plot.x}" y1="${n(y)}" x2="${n(plot.x + plot.w)}" y2="${n(y)}" stroke="${C.line}" stroke-width="1"/>`);
    body.push(text(plot.x - 20, n(y + 7), String(v), { size: 19, fill: C.muted, anchor: "end" }));
  }
  body.push(`<line x1="${plot.x}" y1="${plot.y}" x2="${plot.x}" y2="${n(base)}" stroke="#3a4657" stroke-width="2"/>`);
  body.push(`<line x1="${plot.x}" y1="${n(base)}" x2="${n(plot.x + plot.w)}" y2="${n(base)}" stroke="#3a4657" stroke-width="2"/>`);

  data.forEach(([label, v], i) => {
    const cx = plot.x + slot * (i + 0.5);
    const x = cx - barW / 2;
    const y = yOf(v);
    const fill = i === data.length - 1 ? C.accent : mix(C.teal, C.accent, i * 0.16);
    body.push(`<rect x="${n(x)}" y="${n(y)}" width="${barW}" height="${n(base - y)}" rx="8" fill="${fill}"/>`);
    body.push(`<rect x="${n(x)}" y="${n(y)}" width="${barW}" height="6" rx="3" fill="${C.text}" opacity="0.18"/>`);
    body.push(text(cx, n(y - 18), v.toFixed(1), { size: 24, weight: "bold", fill: C.text, anchor: "middle" }));
    body.push(text(cx, n(base + 38), label, { size: 21, fill: C.muted, anchor: "middle" }));
  });

  body.push(text(60, 648, "Fixture data — Acme Corp is fictional.", { size: 17, fill: C.muted }));
  body.push(text(W - 60, 648, "+86% year over year", { size: 17, fill: C.teal, anchor: "end" }));

  return svg(W, H, body.join(""));
}

/* ------------------------------------------------------------- pipeline.svg */

const STAGES = [
  ["Source", "edn"],
  ["Parse", "spec"],
  ["Deck", "model"],
  ["Render", "hiccup"],
  ["Export", "html"]
];

function pipelineSvg () {
  const W = 1200, H = 300;
  const nw = 184, nh = 92, gap = 45;
  const total = STAGES.length * nw + (STAGES.length - 1) * gap;
  const x0 = (W - total) / 2;
  const cy = 190;
  const top = cy - nh / 2;

  const defs = `<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${C.accent}"/></marker>`;

  const body = [
    `<rect width="${W}" height="${H}" fill="${C.bg}"/>`,
    text(x0, 60, "Acme content pipeline", { size: 28, weight: "bold", fill: C.text, font: WEB_FONT }),
    text(x0, 92, "one source of truth, five deterministic stages", { size: 18, fill: C.muted, font: WEB_FONT })
  ];

  STAGES.forEach(([label, sub], i) => {
    const x = x0 + i * (nw + gap);
    const tint = mix(C.teal, C.accent, i / (STAGES.length - 1));
    body.push(`<rect x="${n(x)}" y="${n(top)}" width="${nw}" height="${nh}" rx="14" fill="${C.panel}" stroke="${C.line}" stroke-width="1.5"/>`);
    body.push(`<rect x="${n(x + 18)}" y="${n(top + 10)}" width="${nw - 36}" height="4" rx="2" fill="${tint}"/>`);
    body.push(text(x + nw / 2, n(cy + 2), label, { size: 24, weight: "bold", fill: C.text, anchor: "middle", font: WEB_FONT }));
    body.push(text(x + nw / 2, n(cy + 28), sub, { size: 15, fill: C.muted, anchor: "middle", font: WEB_FONT }));
    if (i < STAGES.length - 1) {
      const a = x + nw + 10;
      const b = x + nw + gap - 12;
      body.push(`<line x1="${n(a)}" y1="${n(cy)}" x2="${n(b)}" y2="${n(cy)}" stroke="${C.accent}" stroke-width="2.5" marker-end="url(#arrow)"/>`);
    }
  });

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="Acme content pipeline: Source, Parse, Deck, Render, Export">\n  <title>Acme content pipeline</title>\n  <defs>${defs}</defs>\n  ${body.join("\n  ")}\n</svg>\n`;
}

/* ----------------------------------------------------------------- loop.gif */
/* Seamless: every animated quantity is a function of sin/cos(TAU*t), and the
   rotations complete exactly one symmetry period of the shape they drive. */

const LOOP = { w: 480, h: 270, frames: 25, fps: 12.5 };

function loopFrame (i) {
  const t = i / LOOP.frames;
  const { w: W, h: H } = LOOP;
  const cx = W / 2, cy = H / 2;
  const pulse = Math.sin(TAU * t);
  const body = [`<rect width="${W}" height="${H}" fill="${C.bg}"/>`];

  const ring = (r, rot, stroke, weight) => {
    const pts = [90, 150, 210, 270, 330, 30]
      .map((a) => {
        const rad = ((a + rot) * Math.PI) / 180;
        return `${n(cx + r * Math.cos(rad))},${n(cy - r * Math.sin(rad))}`;
      })
      .join(" ");
    return `<polygon points="${pts}" fill="none" stroke="${stroke}" stroke-width="${weight}" stroke-linejoin="round"/>`;
  };

  body.push(ring(116, 0, C.hair, 2));
  body.push(ring(n(58 + 4 * pulse), -60 * t, C.accent, 6));

  for (let k = 0; k < 6; k++) {
    const a = ((k * 60 + 120 * t) * Math.PI) / 180;
    const r = 94 + 6 * pulse;
    body.push(
      `<circle cx="${n(cx + r * Math.cos(a))}" cy="${n(cy - r * Math.sin(a))}" r="${k % 2 ? 6 : 8}" fill="${k % 2 ? C.teal : C.accent}"/>`
    );
  }

  const s = 1 + 0.09 * pulse;
  body.push(
    `<g transform="translate(${n(cx)},${n(cy)}) scale(${n(s)})">` +
    `<path d="M-16,-20 L10,0 L-16,20" fill="none" stroke="${C.teal}" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>` +
    `<circle cx="22" cy="0" r="5" fill="${C.text}"/></g>`
  );

  return svg(W, H, body.join(""));
}

/* ------------------------------------------------------- demo.mp4 / .webm */

const DEMO = { w: 960, h: 540, fps: 24, seconds: 5 };
const DEMO_CAPTIONS = [
  "reading the deck source",
  "validating slide specs",
  "building the deck model",
  "rendering hiccup",
  "writing static html"
];

function demoFrame (i) {
  const t = i / DEMO.fps;
  const { w: W, h: H } = DEMO;
  const defs = [
    `<radialGradient id="glow"><stop offset="0" stop-color="${C.accent}" stop-opacity="0.20"/><stop offset="1" stop-color="${C.accent}" stop-opacity="0"/></radialGradient>`,
    `<radialGradient id="glow2"><stop offset="0" stop-color="${C.teal}" stop-opacity="0.16"/><stop offset="1" stop-color="${C.teal}" stop-opacity="0"/></radialGradient>`
  ];
  const body = [
    `<rect width="${W}" height="${H}" fill="${C.bg}"/>`,
    `<circle cx="835" cy="60" r="420" fill="url(#glow)"/>`,
    `<circle cx="120" cy="500" r="380" fill="url(#glow2)"/>`
  ];

  /* header */
  body.push(hexMark({ cx: 60, cy: 60, r: 27, weight: 5 }));
  body.push(wordmark({ x: 104, y: 46, scale: 0.5, color: C.text, weight: 9 }));
  body.push(text(W - 48, 66, "quarterly product review", { size: 18, fill: C.muted, anchor: "end" }));
  body.push(`<line x1="48" y1="104" x2="${W - 48}" y2="104" stroke="${C.line}" stroke-width="1.5"/>`);

  /* pipeline */
  const nw = 150, nh = 78, gap = 34;
  const total = STAGES.length * nw + (STAGES.length - 1) * gap;
  const x0 = (W - total) / 2;
  const cy = 296;
  const top = cy - nh / 2;
  const t0 = 0.5, step = 0.72;
  const actAt = (k) => t0 + k * step;

  /* connectors first, so nodes paint over the pulse */
  for (let k = 0; k < STAGES.length - 1; k++) {
    const a = x0 + k * (nw + gap) + nw;
    const b = x0 + (k + 1) * (nw + gap);
    const lit = t >= actAt(k + 1);
    body.push(`<line x1="${n(a + 6)}" y1="${cy}" x2="${n(b - 6)}" y2="${cy}" stroke="${lit ? C.accent : C.line}" stroke-width="2.5"/>`);
    const p = clamp((t - actAt(k) - 0.1) / (step - 0.1));
    if (p > 0 && p < 1) {
      body.push(`<circle cx="${n(a + 6 + (b - a - 12) * p)}" cy="${cy}" r="6" fill="${C.accent}"/>`);
    }
  }

  STAGES.forEach(([label, sub], k) => {
    const x = x0 + k * (nw + gap);
    const on = t >= actAt(k);
    const age = clamp((t - actAt(k)) / 0.3);
    const tint = mix(C.teal, C.accent, k / (STAGES.length - 1));
    if (on) {
      body.push(`<rect x="${n(x - 5)}" y="${n(top - 5)}" width="${nw + 10}" height="${nh + 10}" rx="16" fill="${tint}" opacity="${n(0.1 + 0.07 * (1 - age))}"/>`);
    }
    body.push(`<rect x="${n(x)}" y="${n(top)}" width="${nw}" height="${nh}" rx="12" fill="${C.panel}" stroke="${on ? tint : C.line}" stroke-width="${on ? 2 : 1.5}"/>`);
    body.push(`<rect x="${n(x + 16)}" y="${n(top + 9)}" width="${n((nw - 32) * (on ? 1 : 0.22))}" height="4" rx="2" fill="${on ? tint : C.line}"/>`);
    body.push(text(x + nw / 2, n(cy + 2), label, { size: 21, weight: "bold", fill: on ? C.text : C.muted, anchor: "middle" }));
    body.push(text(x + nw / 2, n(cy + 25), sub, { size: 13, fill: C.muted, anchor: "middle" }));
  });

  /* caption + progress */
  const active = Math.max(0, Math.min(STAGES.length - 1, Math.floor((t - t0) / step)));
  const caption = t < t0 ? "waiting for input" : t > actAt(4) + 0.7 ? "deck published" : DEMO_CAPTIONS[active];
  body.push(text(W / 2, 404, caption, { size: 20, fill: t > actAt(4) + 0.7 ? C.teal : C.muted, anchor: "middle" }));

  const prog = clamp((t - t0) / (actAt(4) + 0.5 - t0));
  const bx = x0, bw = total;
  body.push(`<rect x="${n(bx)}" y="448" width="${n(bw)}" height="6" rx="3" fill="${C.line}"/>`);
  body.push(`<rect x="${n(bx)}" y="448" width="${n(bw * prog)}" height="6" rx="3" fill="${C.accent}"/>`);
  body.push(text(bx, 486, `${Math.round(prog * 100)}%`, { size: 15, fill: C.muted }));
  body.push(text(bx + bw, 486, "silent fixture clip", { size: 15, fill: C.muted, anchor: "end" }));

  return svg(W, H, `<defs>${defs.join("")}</defs>${body.join("")}`);
}

/* -------------------------------------------------------------- ambient.mp4 */

const AMBIENT = { w: 1280, h: 720, fps: 24, seconds: 6 };

function ambientFrame (i) {
  const t = i / (AMBIENT.fps * AMBIENT.seconds);
  const { w: W, h: H } = AMBIENT;
  const a = TAU * t;

  const defs = [];
  const body = [`<rect width="${W}" height="${H}" fill="${C.bg}"/>`];

  const blobs = [
    ["a0", C.panel, 1, 640 + 120 * Math.cos(a), 360 + 70 * Math.sin(a), 700],
    ["a1", C.accent, 0.26, 880 + 240 * Math.cos(a + 0.4), 250 + 120 * Math.sin(a + 0.4), 540],
    ["a2", C.teal, 0.24, 380 + 220 * Math.cos(a + 2.4), 500 + 130 * Math.sin(a + 2.4), 560],
    ["a3", C.accent, 0.12, 300 + 160 * Math.cos(a + 4.1), 200 + 90 * Math.sin(a + 4.1), 340]
  ];
  for (const [id, color, o, cx, cy, r] of blobs) {
    defs.push(
      `<radialGradient id="${id}">` +
      `<stop offset="0" stop-color="${color}" stop-opacity="${o}"/>` +
      `<stop offset="0.5" stop-color="${color}" stop-opacity="${n(o * 0.34)}"/>` +
      `<stop offset="1" stop-color="${color}" stop-opacity="0"/></radialGradient>`
    );
    body.push(`<circle cx="${n(cx)}" cy="${n(cy)}" r="${r}" fill="url(#${id})"/>`);
  }

  /* drifting hairlines — the drift period equals the line spacing, so it loops */
  const spacing = 48;
  const dy = spacing * t;
  const lines = [];
  for (let k = -1; k <= H / spacing + 1; k++) lines.push(`M0,${n(k * spacing + dy)} L${W},${n(k * spacing + dy)}`);
  body.push(`<path d="${lines.join(" ")}" fill="none" stroke="${C.text}" stroke-opacity="0.035" stroke-width="1"/>`);

  const dx = spacing * (1 - t);
  const cols = [];
  for (let k = -1; k <= W / spacing + 1; k++) cols.push(`M${n(k * spacing + dx)},0 L${n(k * spacing + dx)},${H}`);
  body.push(`<path d="${cols.join(" ")}" fill="none" stroke="${C.text}" stroke-opacity="0.022" stroke-width="1"/>`);

  defs.push(
    `<radialGradient id="avig" cx="0.5" cy="0.5" r="0.74">` +
    `<stop offset="0.35" stop-color="#000000" stop-opacity="0"/>` +
    `<stop offset="1" stop-color="#000000" stop-opacity="0.6"/></radialGradient>`
  );
  body.push(`<rect width="${W}" height="${H}" fill="url(#avig)"/>`);
  body.push(`<rect width="${W}" height="${H}" fill="${C.bg}" opacity="0.2"/>`);

  return svg(W, H, `<defs>${defs.join("")}</defs>${body.join("")}`);
}

/* ---------------------------------------------------------------- embed.html */

function embedHtml () {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Acme deploy status</title>
<style>
  :root {
    --bg: ${C.bg};
    --panel: ${C.panel};
    --accent: ${C.accent};
    --teal: ${C.teal};
    --muted: ${C.muted};
    --text: ${C.text};
    --line: ${C.line};
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; margin: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font: 15px/1.45 ${WEB_FONT};
    padding: clamp(12px, 2.5vw, 24px);
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  header { display: flex; align-items: center; gap: 12px; }
  header svg { width: 34px; height: 34px; flex: none; }
  h1 { font-size: clamp(16px, 2.6vw, 22px); margin: 0; letter-spacing: -0.02em; font-weight: 700; }
  .sub { color: var(--muted); font-size: 12px; margin-top: 2px; }
  .pill {
    margin-left: auto; display: inline-flex; align-items: center; gap: 7px;
    padding: 5px 12px; border-radius: 999px; font-size: 12px; font-weight: 600;
    background: rgba(92, 208, 179, 0.13); color: var(--teal); border: 1px solid rgba(92, 208, 179, 0.3);
    white-space: nowrap;
  }
  .pill[data-state="busy"] { background: rgba(240, 172, 95, 0.14); color: var(--accent); border-color: rgba(240, 172, 95, 0.34); }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
  .pill[data-state="busy"] .dot { animation: blink 0.8s steps(2, end) infinite; }
  @keyframes blink { 50% { opacity: 0.25; } }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(112px, 1fr)); gap: 10px; }
  .card { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
  .card .k { color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.07em; }
  .card .v { font-size: clamp(20px, 4vw, 28px); font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.15; }
  .card .v.accent { color: var(--accent); }
  .card .v.teal { color: var(--teal); }
  .bar { height: 6px; border-radius: 3px; background: var(--line); overflow: hidden; }
  .bar > i { display: block; height: 100%; width: 0%; background: var(--accent); transition: width 0.18s linear; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  button {
    font: inherit; font-weight: 600; cursor: pointer;
    border: 1px solid var(--line); border-radius: 999px; padding: 7px 16px;
    background: var(--panel); color: var(--text);
  }
  button:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
  button.primary { background: var(--accent); border-color: var(--accent); color: #10141a; }
  button.primary:hover:not(:disabled) { background: #f6bd7c; color: #10141a; }
  button:disabled { opacity: 0.45; cursor: not-allowed; }
  button:focus-visible { outline: 2px solid var(--teal); outline-offset: 2px; }
  ol { list-style: none; margin: 0; padding: 0; flex: 1; min-height: 0; overflow: auto;
       background: var(--panel); border: 1px solid var(--line); border-radius: 12px; }
  li { display: flex; gap: 10px; padding: 7px 12px; font-size: 12.5px; border-bottom: 1px solid var(--line); }
  li:last-child { border-bottom: 0; }
  li time { color: var(--muted); font-variant-numeric: tabular-nums; flex: none; }
  li b { color: var(--teal); font-weight: 600; flex: none; }
  li b.warn { color: var(--accent); }
  li span { color: var(--text); opacity: 0.85; }
</style>
</head>
<body>
<header>
  <svg viewBox="0 0 120 120" aria-hidden="true">${hexMark({ cx: 60, cy: 60, r: 46, weight: 9 })}</svg>
  <div>
    <h1>Acme deploy status</h1>
    <div class="sub">edge fleet &middot; region eu-west</div>
  </div>
  <span class="pill" id="pill" data-state="ok"><span class="dot"></span><span id="pill-label">Healthy</span></span>
</header>

<div class="grid">
  <div class="card"><div class="k">Deploys today</div><div class="v accent" id="deploys">18</div></div>
  <div class="card"><div class="k">Build queue</div><div class="v" id="queue">0</div></div>
  <div class="card"><div class="k">Uptime</div><div class="v teal" id="uptime">99.98%</div></div>
  <div class="card"><div class="k">Release</div><div class="v" id="release" style="font-size:20px">v4.2.<span id="patch">7</span></div></div>
</div>

<div class="bar"><i id="bar"></i></div>

<div class="row">
  <button class="primary" id="deploy">Deploy</button>
  <button id="rollback">Roll back</button>
  <button id="reset">Reset</button>
</div>

<ol id="log" aria-live="polite"></ol>

<script>
(function () {
  var START = { deploys: 18, patch: 7 };
  var el = function (id) { return document.getElementById(id); };
  var state = { deploys: START.deploys, patch: START.patch, busy: false, clock: 0 };

  function stamp () {
    state.clock += 7 + (state.deploys % 5);
    var m = Math.floor(state.clock / 60) % 60;
    var s = state.clock % 60;
    return ("0" + (9 + Math.floor(state.clock / 3600))).slice(-2) + ":" +
           ("0" + m).slice(-2) + ":" + ("0" + s).slice(-2);
  }

  function log (kind, msg) {
    var li = document.createElement("li");
    var t = document.createElement("time");
    t.textContent = stamp();
    var b = document.createElement("b");
    if (kind === "warn") b.className = "warn";
    b.textContent = kind === "warn" ? "WARN" : "OK";
    var s = document.createElement("span");
    s.textContent = msg;
    li.appendChild(t); li.appendChild(b); li.appendChild(s);
    var list = el("log");
    list.insertBefore(li, list.firstChild);
    while (list.children.length > 24) list.removeChild(list.lastChild);
  }

  function paint () {
    el("deploys").textContent = state.deploys;
    el("patch").textContent = state.patch;
    el("queue").textContent = state.busy ? 1 : 0;
    el("pill").dataset.state = state.busy ? "busy" : "ok";
    el("pill-label").textContent = state.busy ? "Deploying" : "Healthy";
    el("deploy").disabled = state.busy;
    el("rollback").disabled = state.busy || state.patch <= 0;
  }

  function march (done) {
    state.busy = true; paint();
    var pct = 0;
    var timer = setInterval(function () {
      pct += 7;
      el("bar").style.width = Math.min(pct, 100) + "%";
      if (pct >= 100) {
        clearInterval(timer);
        setTimeout(function () {
          el("bar").style.width = "0%";
          state.busy = false;
          done();
          paint();
        }, 220);
      }
    }, 45);
  }

  el("deploy").addEventListener("click", function () {
    march(function () {
      state.deploys += 1;
      state.patch += 1;
      el("uptime").textContent = "99.9" + (8 - (state.deploys % 3)) + "%";
      log("ok", "rolled out v4.2." + state.patch + " to 6 edge nodes");
    });
  });

  el("rollback").addEventListener("click", function () {
    march(function () {
      state.patch = Math.max(0, state.patch - 1);
      log("warn", "reverted to v4.2." + state.patch + ", draining connections");
    });
  });

  el("reset").addEventListener("click", function () {
    state.deploys = START.deploys;
    state.patch = START.patch;
    state.clock = 0;
    el("uptime").textContent = "99.98%";
    el("log").innerHTML = "";
    log("ok", "console reset, fleet nominal");
    paint();
  });

  log("ok", "health probe green on 6/6 nodes");
  log("ok", "cache warmed, 42 slides precompiled");
  paint();
})();
</script>
</body>
</html>
`;
}

/* ------------------------------------------------------------------ pipeline */

const pad = (i) => String(i).padStart(4, "0");

async function renderFrames (dir, count, make, width, height) {
  for (let i = 0; i < count; i++) {
    const s = join(dir, `f-${pad(i)}.svg`);
    const p = join(dir, `f-${pad(i)}.png`);
    await writeFile(s, make(i));
    run("rsvg-convert", ["-w", String(width), "-h", String(height), "-b", C.bg, "-o", p, s]);
  }
}

async function rasterize (svgSource, tmp, name, width, height, out, magickArgs) {
  const src = join(tmp, `${name}.svg`);
  const png = join(tmp, `${name}.png`);
  await writeFile(src, svgSource);
  run("rsvg-convert", ["-w", String(width), "-h", String(height), "-b", C.bg, "-o", png, src]);
  run("convert", [png, ...magickArgs, "-strip", out]);
}

async function main () {
  requireTools();
  await rm(OUT, { recursive: true, force: true });
  await mkdir(OUT, { recursive: true });
  const tmp = await mkdtemp(join(tmpdir(), "plato-fixtures-"));

  try {
    process.stdout.write("logo.svg ");
    await writeFile(join(OUT, "logo.svg"), logoSvg());

    process.stdout.write("pipeline.svg ");
    await writeFile(join(OUT, "pipeline.svg"), pipelineSvg());

    process.stdout.write("embed.html ");
    await writeFile(join(OUT, "embed.html"), embedHtml());

    process.stdout.write("hero.jpg ");
    await rasterize(heroSvg(), tmp, "hero", 1600, 900, join(OUT, "hero.jpg"),
      ["-quality", "88", "-sampling-factor", "4:2:0", "-interlace", "Plane"]);

    process.stdout.write("chart.png ");
    await rasterize(chartSvg(), tmp, "chart", 1200, 700, join(OUT, "chart.png"),
      ["-define", "png:compression-level=9"]);

    process.stdout.write("loop.gif ");
    const loopDir = join(tmp, "loop");
    await mkdir(loopDir);
    await renderFrames(loopDir, LOOP.frames, loopFrame, LOOP.w, LOOP.h);
    ffmpeg(["-framerate", String(LOOP.fps), "-i", join(loopDir, "f-%04d.png"),
      "-vf", "palettegen=max_colors=64:stats_mode=diff", "-update", "1", join(tmp, "loop-pal.png")]);
    ffmpeg(["-framerate", String(LOOP.fps), "-i", join(loopDir, "f-%04d.png"), "-i", join(tmp, "loop-pal.png"),
      "-lavfi", "paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
      "-loop", "0", ...BITEXACT, join(OUT, "loop.gif")]);

    process.stdout.write("demo.mp4 ");
    const demoDir = join(tmp, "demo");
    await mkdir(demoDir);
    await renderFrames(demoDir, DEMO.fps * DEMO.seconds, demoFrame, DEMO.w, DEMO.h);
    const demoIn = ["-framerate", String(DEMO.fps), "-i", join(demoDir, "f-%04d.png")];
    ffmpeg([...demoIn, "-an", "-c:v", "libx264", "-preset", "veryslow", "-crf", "21",
      "-pix_fmt", "yuv420p", "-profile:v", "high", "-movflags", "+faststart",
      ...BITEXACT, join(OUT, "demo.mp4")]);

    process.stdout.write("demo.webm ");
    ffmpeg([...demoIn, "-an", "-c:v", "libvpx-vp9", "-crf", "31", "-b:v", "0",
      "-row-mt", "1", "-deadline", "good", "-cpu-used", "2", "-pix_fmt", "yuv420p",
      ...BITEXACT, join(OUT, "demo.webm")]);

    process.stdout.write("demo-poster.jpg ");
    ffmpeg(["-ss", "3.5", "-i", join(OUT, "demo.mp4"), "-frames:v", "1",
      "-q:v", "4", ...BITEXACT, join(OUT, "demo-poster.jpg")]);

    process.stdout.write("ambient.mp4 ");
    const ambDir = join(tmp, "ambient");
    await mkdir(ambDir);
    await renderFrames(ambDir, AMBIENT.fps * AMBIENT.seconds, ambientFrame, AMBIENT.w, AMBIENT.h);
    ffmpeg(["-framerate", String(AMBIENT.fps), "-i", join(ambDir, "f-%04d.png"), "-an",
      "-c:v", "libx264", "-preset", "veryslow", "-crf", "25", "-pix_fmt", "yuv420p",
      "-profile:v", "high", "-movflags", "+faststart", ...BITEXACT, join(OUT, "ambient.mp4")]);

    process.stdout.write("chime.mp3\n");
    const notes = [[220.0, 0.0, 0.14], [440.0, 0.0, 0.3], [554.37, 0.26, 0.26], [659.25, 0.52, 0.24], [880.0, 0.78, 0.2]];
    const inputs = [];
    const chains = [];
    notes.forEach(([hz, delay, gain], k) => {
      inputs.push("-f", "lavfi", "-i", `sine=frequency=${hz}:sample_rate=44100:duration=3`);
      const ms = Math.round(delay * 1000);
      chains.push(
        `[${k}:a]volume=${gain},adelay=${ms}|${ms},` +
        `afade=t=in:st=${delay}:d=0.015,` +
        `afade=t=out:st=${n(delay + 0.05)}:d=${n(2.85 - delay)}:curve=exp[n${k}]`
      );
    });
    const graph = `${chains.join(";")};${notes.map((_, k) => `[n${k}]`).join("")}amix=inputs=${notes.length}:normalize=0,` +
      `volume=1.1,afade=t=out:st=2.75:d=0.25,atrim=0:3,aformat=sample_fmts=s16p:sample_rates=44100:channel_layouts=mono[out]`;
    ffmpeg([...inputs, "-filter_complex", graph, "-map", "[out]",
      "-c:a", "libmp3lame", "-b:a", "96k", "-ar", "44100", "-ac", "1",
      "-write_xing", "0", "-id3v2_version", "0", ...BITEXACT, join(OUT, "chime.mp3")]);
  } finally {
    if (!process.env.PLATO_KEEP_TMP) await rm(tmp, { recursive: true, force: true });
    else console.log(`\n(kept intermediates in ${tmp})`);
  }

  const names = (await readdir(OUT)).sort();
  let total = 0;
  console.log(`\nwrote ${names.length} files to public/assets/acme`);
  for (const name of names) {
    const { size } = await stat(join(OUT, name));
    total += size;
    console.log(`  ${name.padEnd(20)}${String(size).padStart(9)} B`);
  }
  console.log(`  ${"TOTAL".padEnd(20)}${String(total).padStart(9)} B  (${(total / 1024).toFixed(1)} KiB, budget ${(BUDGET / 1024).toFixed(0)} KiB)`);
  if (total > BUDGET) throw new Error(`fixture directory is ${total} B, over the ${BUDGET} B budget — lower the encode quality`);
}

main().catch((err) => {
  console.error(`\ngen-fixtures failed: ${err.message}`);
  process.exit(1);
});
