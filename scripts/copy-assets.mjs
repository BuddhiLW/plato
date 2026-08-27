import { cp, mkdir } from "node:fs/promises";

const dist = "node_modules/reveal.js/dist";
const plugins = ["markdown", "highlight", "notes", "math", "search", "zoom"];

const jobs = [
  // reveal.js 6 ships reset.css separately from reveal.css; without it the
  // page inherits the browser's default margins.
  [`${dist}/reset.css`, "public/vendor/reset.css"],
  [`${dist}/reveal.css`, "public/vendor/reveal.css"],
  [`${dist}/theme/night.css`, "public/vendor/theme/night.css"],
  [`${dist}/plugin/highlight/monokai.css`, "public/vendor/highlight/monokai.css"],
  [`${dist}/reveal.js`, "public/vendor/reveal.js"],
  ...plugins.map((name) => [
    `${dist}/plugin/${name}.js`,
    `public/vendor/plugin/${name}.js`
  ])
];

for (const [source, target] of jobs) {
  await mkdir(target.slice(0, target.lastIndexOf("/")), { recursive: true });
  await cp(source, target);
}
