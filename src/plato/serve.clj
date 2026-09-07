(ns plato.serve
  "A development server for authoring: the deck rendered from its source on
   every request, the assets the page links, and an event the page listens
   for so a browser reloads when the source changes.

   JVM only. The native binary has no HTTP server, and the CLI reaches this
   namespace by name, so nothing else in plato loads it.

   Routing and rendering are pure and tested (`respond`, `pages`); only
   `serve!` binds a port."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [plato.cli :as cli])
  (:import (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
           (java.io File OutputStream)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

;; ── reload ──────────────────────────────────────────────────────────────────

(def events-path "/__plato/events")

(def reload-js
  "Reloads the page when the server reports a change. Reveal keeps the slide
   in the URL hash, so the page comes back where the author was."
  (str "new EventSource(" (pr-str events-path) ").onmessage = function () { location.reload(); };"))

(def reload-script [:script reload-js])

(def poll-ms
  "How often a connection looks at the watched files."
  250)

;; ── content types ───────────────────────────────────────────────────────────

(def content-types
  {"html" "text/html; charset=utf-8" "css" "text/css" "js" "text/javascript"
   "mjs" "text/javascript" "json" "application/json" "map" "application/json"
   "svg" "image/svg+xml" "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp" "ico" "image/x-icon"
   "mp4" "video/mp4" "webm" "video/webm" "mp3" "audio/mpeg" "wav" "audio/wav"
   "ogg" "audio/ogg" "woff2" "font/woff2" "woff" "font/woff" "ttf" "font/ttf"
   "txt" "text/plain" "edn" "text/plain" "md" "text/plain"})

(defn content-type [path]
  (let [ext (some-> (re-find #"\.([A-Za-z0-9]+)$" (str path)) second str/lower-case)]
    (get content-types ext "application/octet-stream")))

;; ── pages ───────────────────────────────────────────────────────────────────

(defn- file-name [path]
  (last (str/split (str path) #"/")))

(defn- error-page
  "What the author sees when the source does not build: the error, and the
   reload listener, so fixing the file brings the deck back."
  [^Throwable e]
  (str "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>plato: build failed</title>"
       "<style>body{font:15px/1.5 ui-monospace,monospace;background:#1a1010;color:#f2c;padding:2rem}"
       "pre{white-space:pre-wrap;color:#fdd}</style></head><body>"
       "<h1>plato: the deck did not build</h1><pre>"
       (-> (str (ex-message e)
                (when-let [d (ex-data e)] (str "\n" (pr-str d))))
           (str/replace "&" "&amp;") (str/replace "<" "&lt;"))
       "</pre><script>" reload-js "</script></body></html>"))

(defn pages
  "file name -> a zero-argument render for every page the job produces, built
   fresh from the source on each call. Reveal: index.html and any generated
   theme sheet. HyperFrames: the composition and the presenter as well. Every
   page carries the reload listener."
  [{:keys [input opts hyperframes?]}]
  (let [opts (-> opts
                 (assoc :out (if hyperframes? "." "index.html"))
                 (dissoc :print?)
                 (assoc :after-slides [reload-script]
                        :after-player [reload-script]))
        render (fn []
                 (let [job (cli/deck-job input opts)]
                   (into {}
                         (map (fn [{:keys [path content]}] [(file-name path) content]))
                         (:files (if hyperframes? (cli/hyperframes-job job) (cli/build-job job))))))
        names (cond-> (if hyperframes? ["index.html" "present.html"] ["index.html"])
                (:tokens opts) (conj (cli/theme-css-name (:tokens opts))))
        page-for (fn [name]
                   (fn []
                     (try (let [files (render)]
                            (or (get files name)
                                (str "<!doctype html><p>no " name " in this build</p>")))
                          (catch Exception e (error-page e)))))]
    (into {} (map (fn [n] [n (page-for n)])) names)))

;; ── routing ─────────────────────────────────────────────────────────────────

(defn- request-name [path]
  (let [path (first (str/split (str path) #"\?" 2))]
    (if (= "/" path) "index.html" (subs path 1))))

(defn respond
  "Route one request path: a page the job renders, else a file under `root`,
   else 404. A path climbing above the root is refused.
   -> {:status int :type content-type :body String-or-File}"
  [pages root path]
  (let [name (request-name path)]
    (cond
      (contains? pages name)
      {:status 200 :type (content-type name) :body ((get pages name))}

      (some #{".."} (str/split name #"/"))
      {:status 403 :type "text/plain" :body "refused"}

      :else
      (let [f (io/file root name)]
        (if (.isFile f)
          {:status 200 :type (content-type name) :body f}
          {:status 404 :type "text/plain" :body (str "not found: " path)})))))

;; ── watching ────────────────────────────────────────────────────────────────

(defn watched
  "The files a job reads: the source and the theme inputs it names."
  [{:keys [input opts]}]
  (vec (remove nil? [input (:tokens opts) (:theme-css opts)])))

(defn stamp
  "One number that changes when any watched file changes."
  [paths]
  (reduce + 0 (map (fn [p] (let [f (io/file p)] (if (.exists f) (.lastModified f) 0))) paths)))

;; ── the server ──────────────────────────────────────────────────────────────

(defn- send! [^HttpExchange ex {:keys [status type body]}]
  (.add (.getResponseHeaders ex) "Content-Type" type)
  (.add (.getResponseHeaders ex) "Cache-Control" "no-store")
  (if (instance? File body)
    (do (.sendResponseHeaders ex status (.length ^File body))
        (with-open [in (io/input-stream body) ^OutputStream out (.getResponseBody ex)]
          (io/copy in out)))
    (let [bytes (.getBytes ^String body "UTF-8")]
      (.sendResponseHeaders ex status (alength bytes))
      (with-open [^OutputStream out (.getResponseBody ex)]
        (.write out bytes)))))

(defn- stream-events!
  "Hold the connection and send `reload` whenever the watched files change.
   Ends when the browser goes away, which the next write reports."
  [^HttpExchange ex paths]
  (.add (.getResponseHeaders ex) "Content-Type" "text/event-stream")
  (.add (.getResponseHeaders ex) "Cache-Control" "no-store")
  (.sendResponseHeaders ex 200 0)
  (with-open [^OutputStream out (.getResponseBody ex)]
    (try
      (.write out (.getBytes ": plato\n\n" "UTF-8"))
      (.flush out)
      (loop [last (stamp paths) idle 0]
        (Thread/sleep (long poll-ms))
        (let [now (stamp paths)]
          (cond
            (not= now last)
            (do (.write out (.getBytes "data: reload\n\n" "UTF-8")) (.flush out)
                (recur now 0))
            (> idle 15000)
            (do (.write out (.getBytes ": ping\n\n" "UTF-8")) (.flush out)
                (recur last 0))
            :else (recur last (+ idle poll-ms)))))
      (catch java.io.IOException _ nil))))

(defn start!
  "Start serving `job` on `port`. job: {:input :opts :hyperframes? :root}.
   Returns the HttpServer; `.stop` it to end."
  [{:keys [port root] :as job}]
  (let [pages (pages job)
        paths (watched job)
        server (HttpServer/create (InetSocketAddress. (int port)) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (try
                          (if (= events-path (.getPath (.getRequestURI ex)))
                            (stream-events! ex paths)
                            (send! ex (respond pages root (.getPath (.getRequestURI ex)))))
                          (catch Exception e
                            (try (send! ex {:status 500 :type "text/plain" :body (str e)})
                                 (catch Exception _ nil)))
                          (finally (.close ex))))))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    server))

(defn serve!
  "The CLI's `serve`: start, say where, and block."
  [{:keys [input opts]}]
  (let [port (Long/parseLong (str (or (:port opts) 8090)))
        job {:input input
             :opts (dissoc opts :port :hyperframes? :assets)
             :hyperframes? (boolean (:hyperframes? opts))
             :root (or (:assets opts) "public")
             :port port}]
    (start! job)
    (println (str "plato: serving " (or input (:deck opts)) " at http://localhost:" port "/"
                  (when (:hyperframes? job) " (present.html for the HyperFrames presenter)")))
    (println (str "       assets from " (:root job) "; the page reloads when "
                  (str/join ", " (watched job)) " change"))
    @(promise)))
