import { cp, mkdir } from "node:fs/promises";

const jobs = [
  ["node_modules/reveal.js/dist/reveal.css", "public/vendor/reveal.css"],
  ["node_modules/reveal.js/dist/theme/night.css", "public/vendor/theme/night.css"],
  ["node_modules/reveal.js/dist/plugin/highlight/monokai.css", "public/vendor/highlight/monokai.css"]
];

for (const [source, target] of jobs) {
  await mkdir(target.slice(0, target.lastIndexOf("/")), { recursive: true });
  await cp(source, target);
}
