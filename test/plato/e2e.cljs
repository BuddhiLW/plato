(ns plato.e2e
  "Browser checks for the live shell and the prerendered site, as data.

   The JVM suite cannot see any of this: whether Reveal actually accepted the
   deck config, whether the markdown plugin left React's <section> alone,
   whether a scene starts playing when it scrolls into view. Those are
   properties of a running page, so they are measured in one.

   A scenario is a URL plus probes; a probe is a JavaScript expression and a
   predicate over its value. Adding a check means adding a map, never touching
   the driver.

   Two trees answer: public/ holds the Reagent dev shells, dist/site the
   prerendered pages `bb build` publishes. A scenario names which with
   :target (:shell, :site, or :both, the default); the driver takes the root
   and the target as its two arguments."
  (:require ["playwright" :as pw]
            ["node:http" :as http]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:zlib" :as zlib]
            [clojure.string :as str]
            [shadow.cljs.modern :refer (js-await)]))

;; ── the checks ──────────────────────────────────────────────────────────────

(defn- elapsed
  "Leading seconds of a scene transport read-out, \"3.11 / 6.90 s\" -> 3.11."
  [text]
  (or (some-> text not-empty (str/split #"/") first str/trim js/parseFloat) 0))

(def scenarios
  [{:url "/acme.html#/arr-scene"
    :settle 1500
    :probes
    [{:name ":slide-number reached Reveal, so the config was camelCased"
      :js "!!document.querySelector('.reveal .slide-number')"
      :ok? true?}
     {:name "the Desargues scene autoplayed once it became visible"
      :js "(document.querySelector('section#arr-scene output')||{}).textContent||''"
      :ok? #(pos? (elapsed %))}]}

   ;; The shell's markdown container is a React decision: the plugin rewrites
   ;; the element it finds data-markdown on, so it must never be the <section>
   ;; React owns. The exporter serializes and has no such owner, so it puts the
   ;; attribute on the section itself — see plato.core/slide-view.
   {:url "/acme.html#/release-notes"
    :settle 1200
    :target :shell
    :probes
    [{:name "React still owns <section id=release-notes>"
      :js "!!document.querySelector('section#release-notes')"
      :ok? true?}
     {:name "data-markdown sits on a nested div, not on the section"
      :js (str "(function(){var s=document.querySelector('section#release-notes');"
               "return !!s && !!s.querySelector('div[data-markdown]')"
               " && !s.hasAttribute('data-markdown');})()")
      :ok? true?}]}

   {:url "/acme.html#/release-notes"
    :settle 1200
    :probes
    [{:name "the markdown was converted in place"
      :js (str "(function(){var s=document.querySelector('section#release-notes');"
               "return !!s && /<h2|<ul|<li/i.test(s.innerHTML);})()")
      :ok? true?}
     {:name "the speaker notes survived beside the markdown"
      :js "!!document.querySelector('section#release-notes aside.notes')"
      :ok? true?}]}

   {:url "/index.html#/animation"
    :settle 1500
    :probes
    [{:name "the site deck's scene autoplayed too"
      :js "(document.querySelector('section#animation output')||{}).textContent||''"
      :ok? #(pos? (elapsed %))}
     ;; On the prerendered page this is the island hydrating the static SVG
     ;; the moment the slide came on screen; on the shell it is the same
     ;; island mounted by the Reagent wrapper. One DOM shape either way.
     {:name "the scene is live: one transport inside the scene element"
      :js "document.querySelectorAll('section#animation .plato-scene .plato-transport').length"
      :ok? #(= 1 %)}]}

   ;; ── the prerendered site ────────────────────────────────────────────────
   ;; What ships is HTML the build wrote, plus Reveal, its plugins and two
   ;; islands. The Reagent shell's bundle must not be on the page at all: the
   ;; whole point of prerendering is that nothing paints late waiting for it.
   {:url "/index.html"
    :settle 1200
    :target :site
    :probes
    [{:name "the site deck is prerendered: slides are in the HTML the server sent, before any script"
      ;; The served bytes, not the live DOM: the driver itself injects a
      ;; script into the head, and Reveal adds elements of its own.
      :js (str "fetch(location.pathname).then(function(r){return r.text();})"
               ".then(function(h){return h.indexOf('<section') < h.indexOf('<script');})")
      :ok? true?}
     {:name "no application bundle is loaded, only Reveal, its plugins and the islands"
      :js (str "Array.from(document.scripts).map(function(s){return s.src;})"
               ".filter(Boolean).filter(function(s){return !/\\/vendor\\//.test(s);}).length")
      :ok? zero?}
     {:name "math stays off a deck that never asked for it, so the page does not phone home"
      :js "!!Array.from(document.scripts).find(function(s){return /plugin\\/math\\.js/.test(s.src);})"
      :ok? false?}]}

   ;; The number the whole build step exists for, measured the way Lighthouse's
   ;; mobile preset measures it (4x CPU, 1.6 Mbps, 150 ms RTT) rather than on a
   ;; warm localhost. FCP and LCP are the paints; TBT is the sum of long-task
   ;; time past 50 ms after first paint. The values are printed on every run,
   ;; and the thresholds are the gate: tighten them as the bundles shrink.
   ;;
   ;; Calibration, 2026-09-06, over this HTTP/1.1 server: the client-rendered
   ;; shell measured FCP 14.2 s here; the prerendered site with the slim
   ;; highlight plugin and self-hosted fonts measured 4.4 s, TBT 35 ms
   ;; (Lighthouse's own simulation of the same page: FCP 3.1 s, score 86).
   ;; What is left is Reveal laying out every slide, which no bundle change
   ;; moves. So the gate is set just above the measurement, and a regression
   ;; of a second is what it catches.
   {:url "/index.html"
    :settle 2500
    :target :site
    :throttle {:cpu 4}
    :probes
    [{:name "paint and blocking under mobile throttling {fcp lcp tbt} ms"
      :show? true
      :js (str "(function(){var p=window.__plato_perf||{lcp:0,long:[]};"
               "var fcp=(performance.getEntriesByName('first-contentful-paint')[0]||{}).startTime||0;"
               "var tbt=p.long.filter(function(x){return x[0]+x[1]>fcp;})"
               ".reduce(function(a,x){return a+Math.max(0,x[1]-50);},0);"
               "return JSON.stringify({fcp:Math.round(fcp),lcp:Math.round(p.lcp),tbt:Math.round(tbt)});})()")
      :ok? #(let [{:strs [fcp lcp tbt]} (js->clj (js/JSON.parse %))]
              (and (< 0 fcp 5000) (< 0 lcp 5500) (< tbt 300)))}
     {:name "script bytes on the wire (gzip), every script the page loads"
      :show? true
      :js (str "performance.getEntriesByType('resource')"
               ".filter(function(r){return r.initiatorType==='script';})"
               ".reduce(function(a,r){return a+(r.transferSize||r.encodedBodySize||0);},0)")
      :ok? #(< % 250000)}]}

   {:url "/acme.html"
    :settle 1200
    :target :site
    :probes
    [{:name "the Acme deck declared :math?, so its page carries the plugin"
      :js "!!Array.from(document.scripts).find(function(s){return /plugin\\/math\\.js/.test(s.src);})"
      :ok? true?}
     {:name "the backlink to the engine page is on the Acme page"
      :js "!!document.querySelector('a.plato-backlink[href=\"./index.html\"]')"
      :ok? true?}]}

   ;; The published site is a fixture, not just a page: these are the slides a
   ;; visitor is most likely to land on, so a content kind that stops rendering
   ;; fails the build rather than the demo.
   {:url "/index.html#/content-is-open"
    :settle 1200
    :probes
    [{:name "the card grid rendered every card"
      :js "document.querySelectorAll('section#content-is-open .plato-card').length"
      :ok? #(>= % 4)}]}

   {:url "/index.html#/front-ends"
    :settle 1200
    :probes
    [{:name "declared column widths reached the browser as one CSS width shape"
      :js (str "Array.from(document.querySelectorAll("
               "'section#front-ends .plato-column')).map(function(n){"
               "return n.style.width;}).join(',')")
      :ok? #(= "100%,100%" %)}]}

   {:url "/index.html#/a-deck-is-a-value"
    :settle 1200
    :probes
    [{:name "the code block is language-tagged for the highlighter"
      :js (str "!!document.querySelector('section#a-deck-is-a-value "
               "code.language-clojure')")
      :ok? true?}
     {:name "stepped highlighting reached the DOM as data-line-numbers"
      :js (str "(document.querySelector('section#a-deck-is-a-value code')||{})"
               ".getAttribute?document.querySelector('section#a-deck-is-a-value code')"
               ".getAttribute('data-line-numbers'):null")
      :ok? #(boolean (seq (str %)))}]}

   {:url "/index.html#/tested"
    :settle 1200
    :probes
    [{:name "columns and their nested bullets both rendered"
      :js (str "(function(){var s=document.querySelector('section#tested');"
               "return !!s && s.querySelectorAll('.plato-list li').length;})()")
      :ok? #(>= % 6)}]}

   ;; ── fit ─────────────────────────────────────────────────────────────────
   ;; Reveal lays a deck out in a fixed box and CSS-scales that box to the
   ;; window, so whether a slide overflows is a property of the build rather
   ;; than of the viewer's screen — which is what makes it gateable at all.
   ;; plato.fit does the measuring inside the page; these probes only carry the
   ;; verdict out, so the rule lives in one place and ships with the engine.
   {:url "/index.html"
    :settle 1500
    :probes
    [{:name "every slide of the site deck fits its slide box"
      :js "plato.fit.checkDeck()"
      :ok? nil?}
     {:name "no slide waives an overflow it no longer has"
      :js "plato.fit.checkWaivers()"
      :ok? nil?}]}

   {:url "/acme.html"
    :settle 1800
    :probes
    [{:name "every slide of the Acme deck fits its slide box"
      :js "plato.fit.checkDeck()"
      :ok? nil?}
     {:name "no Acme slide waives an overflow it no longer has"
      :js "plato.fit.checkWaivers()"
      :ok? nil?}]}

   ;; ── shrinking ───────────────────────────────────────────────────────────
   ;; The JVM suite proves the arithmetic and that plato.css names the property
   ;; plato.fit writes. What only a browser can answer is whether the CSS
   ;; `scale` those two agree on actually paints the slide smaller — Reveal
   ;; writes `transform` on the same element, and the two composing rather than
   ;; clobbering is the whole reason the mechanism works. So this drives the
   ;; chain end to end on a slide made to overflow on purpose.
   {:url "/index.html"
    :settle 1500
    :probes
    ;; Only ^:export names are addressable here — this runs against the
    ;; :advanced build, where anything else has been renamed.
    [{:name "a slide declaring {:overflow :shrink} is painted smaller than it measures"
      :js (str "(function(){"
               ;; A leaf slide by id: the first `section` in the DOM may be a
               ;; vertical stack, and a stack is a container plato.fit never
               ;; measures.
               "var s=document.querySelector('section#welcome');"
               "var filler=document.createElement('div');"
               ;; Enough to break the slide, not so much that fitting it would
               ;; need a scale under the readable floor — a 900px filler put
               ;; #welcome 776px over, which is a :too-small, not a :shrunk.
               "filler.style.height='300px';"
               "s.appendChild(filler);"
               "var undeclared=plato.fit.checkDeck();"
               "s.setAttribute('data-plato-overflow','shrink');"
               "var declared=plato.fit.checkDeck();"
               "plato.fit.fitDeck();"
               "var painted=getComputedStyle(s).scale;"
               ;; Dropping the attribute unmatches the rule, so the property
               ;; left behind on the element cannot affect anything after this.
               "s.removeChild(filler); s.removeAttribute('data-plato-overflow');"
               "return JSON.stringify({undeclared:undeclared||'',"
               "                       declared:declared||'',"
               "                       painted:painted,"
               "                       restored:plato.fit.checkDeck()||''});"
               "})()")
      ;; Four outcomes in one probe, so none of them can be vacuous: the filler
      ;; really did break the slide, declaring :shrink really did answer for it,
      ;; Reveal's own transform on that same element did not eat the scale, and
      ;; taking the filler away puts the deck back where it started.
      :ok? #(let [{:strs [undeclared declared painted restored]}
                  (js->clj (js/JSON.parse %))]
              (and (re-find #"taller than the slide box" undeclared)
                   (= "" declared)
                   (< 0.0 (js/parseFloat painted) 1.0)
                   (= "" restored)))}]}

   ;; ── the static export ───────────────────────────────────────────────────
   ;; A `plato build` export is plain Reveal HTML with no ClojureScript in it,
   ;; so it used to be the one artifact plato could not ask about fit — and it
   ;; is the artifact a user actually ships. A deck that declares :shrink now
   ;; carries the standalone plato.fit bundle, which is the SAME namespace the
   ;; Reagent shell loads. Built from test/fixtures/shrink.edn by `npm run
   ;; e2e:export`, whose slide overflows on purpose.
   {:url "/e2e/shrink-export.html"
    :settle 1200
    :target :shell
    :probes
    [{:name "an exported deck shrinks the slide that asked to be shrunk"
      :js (str "(function(){"
               "var s=document.querySelector('section#too-tall');"
               "var painted=getComputedStyle(s).scale;"
               "var declared=plato.fit.checkDeck()||'';"
               ;; Taking the declaration away must resurrect the overflow. If it
               ;; does not, the slide fitted all along and the rest proves
               ;; nothing.
               "s.removeAttribute('data-plato-overflow');"
               "var undeclared=plato.fit.checkDeck()||'';"
               "s.setAttribute('data-plato-overflow','shrink');"
               "return JSON.stringify({painted:painted,declared:declared,"
               "                       undeclared:undeclared});"
               "})()")
      :ok? #(let [{:strs [painted declared undeclared]} (js->clj (js/JSON.parse %))
                  scale (js/parseFloat painted)]
              (and (< 0.6 scale 1.0)
                   (= "" declared)
                   (re-find #"too-tall" undeclared)))}]}])

;; ── static server ───────────────────────────────────────────────────────────

(def content-types
  {".html" "text/html" ".js" "text/javascript" ".css" "text/css"
   ".json" "application/json" ".svg" "image/svg+xml" ".png" "image/png"
   ".jpg" "image/jpeg" ".gif" "image/gif" ".mp4" "video/mp4" ".mp3" "audio/mpeg"})

(def compressible
  "Types served gzipped, as GitHub Pages serves them — so a byte or timing
   probe here measures what a visitor pays, not the raw file."
  #{".html" ".js" ".css" ".json" ".svg"})

(defn- serve
  "Serve `root` on `port`; returns the node server."
  [root port]
  (doto (.createServer
         http
         (fn [^js req ^js res]
           (let [url (first (str/split (.-url req) #"[?#]"))
                 file (path/join root (if (= "/" url) "/index.html" url))
                 ext (path/extname file)]
             (if (and (fs/existsSync file) (.isFile (fs/statSync file)))
               (let [body (fs/readFileSync file)
                     gzip? (and (contains? compressible ext)
                                (str/includes? (or (aget (.-headers req) "accept-encoding") "")
                                               "gzip"))]
                 (.writeHead res 200
                             (cond-> #js {"Content-Type" (get content-types ext
                                                              "application/octet-stream")}
                               gzip? (doto (aset "Content-Encoding" "gzip"))))
                 (.end res (if gzip? (zlib/gzipSync body) body)))
               (do (.writeHead res 404) (.end res "not found"))))))
    (.listen port "127.0.0.1")))

;; ── driver ──────────────────────────────────────────────────────────────────

(defn- report [{:keys [ok? name detail]}]
  (println (str (if ok? "PASS  " "FAIL  ") name
                (when (seq (str detail)) (str "  — " detail)))))

(defn- probe-promise
  "One probe -> a promise of its result map. A probe with :show? reports the
   value it saw even when it passed, so a run is also a measurement."
  [^js page url {:keys [js ok? name show?]}]
  (-> (.evaluate page js)
      (.then (fn [v]
               (let [ok (boolean (ok? v))]
                 {:ok? ok
                  :name (str url " — " name)
                  :detail (when (or show? (not ok)) (pr-str v))})))))

(def perf-init
  "Installed before the page's own scripts: buffers the largest contentful
   paint and every long task, which no probe could observe after the fact."
  (str "window.__plato_perf={lcp:0,long:[]};"
       "new PerformanceObserver(function(l){l.getEntries().forEach(function(e){"
       "window.__plato_perf.lcp=e.startTime;});})"
       ".observe({type:'largest-contentful-paint',buffered:true});"
       "new PerformanceObserver(function(l){l.getEntries().forEach(function(e){"
       "window.__plato_perf.long.push([e.startTime,e.duration]);});})"
       ".observe({type:'longtask',buffered:true});"))

(defn ^:async throttle!
  "Slow the page down the way Lighthouse's mobile preset does: a 4x CPU and a
   1.6 Mbps / 150 ms round-trip network, through the DevTools protocol."
  [^js ctx ^js page {:keys [cpu latency download upload]}]
  (js-await [^js cdp (.newCDPSession ctx page)]
    (js-await [_ (.send cdp "Emulation.setCPUThrottlingRate" #js {:rate (or cpu 4)})]
      (js-await [_ (.send cdp "Network.emulateNetworkConditions"
                          #js {:offline false
                               :latency (or latency 150)
                               :downloadThroughput (or download 204800)
                               :uploadThroughput (or upload 84000)})]
        cdp))))

(defn ^:async run-scenario
  "Open `url`, let the page settle, and answer every probe. The console is a
   probe too: an error there fails the scenario even when every assertion held.
   A scenario with :throttle runs under Lighthouse-like mobile conditions."
  [^js ctx base {:keys [url settle probes throttle]}]
  (js-await [^js page (.newPage ctx)]
    (let [errors (atom [])]
      (.on page "console"
           (fn [^js m] (when (= "error" (.type m)) (swap! errors conj (.text m)))))
      (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
      (js-await [_ (.addInitScript page perf-init)]
        (js-await [_ (if throttle (throttle! ctx page throttle) (js/Promise.resolve nil))]
          (js-await [_ (.goto page (str base url) #js {:waitUntil "networkidle"})]
            (js-await [_ (.waitForSelector page ".reveal .slides section"
                                           #js {:state "attached" :timeout 30000})]
              ;; The fit gate is a build check, not something a visitor should
              ;; download, so a prerendered page does not carry plato.fit. The
              ;; probes still need it: inject the same bundle the shell has.
              (js-await [has-fit? (.evaluate page "typeof plato!=='undefined'&&!!plato.fit")]
                (js-await [_ (if has-fit?
                               (js/Promise.resolve nil)
                               (.addScriptTag page #js {:url (str base "/vendor/plato-fit/main.js")}))]
                  (js-await [_ (.waitForTimeout page (or settle 1000))]
                    (js-await [results (js/Promise.all
                                        (into-array
                                         (map #(probe-promise page url %) probes)))]
                      (js-await [_ (.close page)]
                        (conj (vec results)
                              {:ok? (empty? @errors)
                               :name (str url " — the console stayed clean")
                               :detail (str/join " | " (take 3 @errors))})))))))))))))

(defn scenarios-for
  "The scenarios that apply to `target` (:shell or :site). A scenario without
   a :target applies to both."
  [target]
  (filter #(contains? #{:both target} (:target % :both)) scenarios))

(defn ^:async -main [& _]
  (let [[root target] (drop 2 (.-argv js/process))
        root (or root "public")
        target (keyword (or target "shell"))
        port 8099
        ^js server (serve root port)
        base (str "http://127.0.0.1:" port)]
    (println (str "driving " root " as " (name target)))
    (js-await [^js browser (.launch pw/chromium)]
      (js-await [ctx (.newContext browser #js {:viewport #js {:width 1280 :height 800}})]
        (js-await [results (js/Promise.all
                            (into-array (map #(run-scenario ctx base %)
                                             (scenarios-for target))))]
          (js-await [_ (.close browser)]
            (let [flat (vec (mapcat identity results))
                  failed (remove :ok? flat)]
              (run! report flat)
              (println)
              (println (str (- (count flat) (count failed)) "/" (count flat)
                            " checks passed"))
              (.close server)
              (set! (.-exitCode js/process) (if (seq failed) 1 0)))))))))
