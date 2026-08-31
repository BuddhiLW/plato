(ns plato.e2e
  "Browser checks for the live shell, as data.

   The JVM suite cannot see any of this: whether Reveal actually accepted the
   deck config, whether the markdown plugin left React's <section> alone,
   whether a scene starts playing when it scrolls into view. Those are
   properties of a running page, so they are measured in one.

   A scenario is a URL plus probes; a probe is a JavaScript expression and a
   predicate over its value. Adding a check means adding a map, never touching
   the driver."
  (:require ["playwright" :as pw]
            ["node:http" :as http]
            ["node:fs" :as fs]
            ["node:path" :as path]
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

   {:url "/acme.html#/release-notes"
    :settle 1200
    :probes
    [{:name "React still owns <section id=release-notes>"
      :js "!!document.querySelector('section#release-notes')"
      :ok? true?}
     {:name "data-markdown sits on a nested div, not on the section"
      :js (str "(function(){var s=document.querySelector('section#release-notes');"
               "return !!s && !!s.querySelector('div[data-markdown]')"
               " && !s.hasAttribute('data-markdown');})()")
      :ok? true?}
     {:name "the markdown was converted in place"
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
      :ok? #(pos? (elapsed %))}]}

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

(defn- serve
  "Serve `root` on `port`; returns the node server."
  [root port]
  (doto (.createServer
         http
         (fn [^js req ^js res]
           (let [url (first (str/split (.-url req) #"[?#]"))
                 file (path/join root (if (= "/" url) "/index.html" url))]
             (if (and (fs/existsSync file) (.isFile (fs/statSync file)))
               (do (.writeHead res 200 #js {"Content-Type"
                                            (get content-types (path/extname file)
                                                 "application/octet-stream")})
                   (.end res (fs/readFileSync file)))
               (do (.writeHead res 404) (.end res "not found"))))))
    (.listen port "127.0.0.1")))

;; ── driver ──────────────────────────────────────────────────────────────────

(defn- report [{:keys [ok? name detail]}]
  (println (str (if ok? "PASS  " "FAIL  ") name
                (when (seq (str detail)) (str "  — " detail)))))

(defn- probe-promise
  "One probe -> a promise of its result map."
  [^js page url {:keys [js ok? name]}]
  (-> (.evaluate page js)
      (.then (fn [v]
               (let [ok (boolean (ok? v))]
                 {:ok? ok
                  :name (str url " — " name)
                  :detail (when-not ok (pr-str v))})))))

(defn ^:async run-scenario
  "Open `url`, let the page settle, and answer every probe. The console is a
   probe too: an error there fails the scenario even when every assertion held."
  [^js ctx base {:keys [url settle probes]}]
  (js-await [^js page (.newPage ctx)]
    (let [errors (atom [])]
      (.on page "console"
           (fn [^js m] (when (= "error" (.type m)) (swap! errors conj (.text m)))))
      (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
      (js-await [_ (.goto page (str base url) #js {:waitUntil "networkidle"})]
        (js-await [_ (.waitForSelector page ".reveal .slides section"
                                       #js {:state "attached" :timeout 10000})]
          (js-await [_ (.waitForTimeout page (or settle 1000))]
            (js-await [results (js/Promise.all
                                (into-array
                                 (map #(probe-promise page url %) probes)))]
              (js-await [_ (.close page)]
                (conj (vec results)
                      {:ok? (empty? @errors)
                       :name (str url " — the console stayed clean")
                       :detail (str/join " | " (take 3 @errors))})))))))))

(defn ^:async -main [& _]
  (let [root (or (first (drop 2 (.-argv js/process))) "public")
        port 8099
        ^js server (serve root port)
        base (str "http://127.0.0.1:" port)]
    (js-await [^js browser (.launch pw/chromium)]
      (js-await [ctx (.newContext browser #js {:viewport #js {:width 1280 :height 800}})]
        (js-await [results (js/Promise.all
                            (into-array (map #(run-scenario ctx base %) scenarios)))]
          (js-await [_ (.close browser)]
            (let [flat (vec (mapcat identity results))
                  failed (remove :ok? flat)]
              (run! report flat)
              (println)
              (println (str (- (count flat) (count failed)) "/" (count flat)
                            " checks passed"))
              (.close server)
              (set! (.-exitCode js/process) (if (seq failed) 1 0)))))))))
