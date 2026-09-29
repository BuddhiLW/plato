(ns plato.video.encode
  "JVM boundary for `plato.video`: frame plan -> SVG files -> raster -> MP4.

  Measured 2026-09-29 on a 15 s 1080x1920 ad (450 frames, 22 cores, a loaded
  box): HyperFrames 16.5 s wall / 47 s CPU; this path with :librsvg 2.5 s wall
  / 31 s CPU, with :resvg about 5 s wall. SVG generation is ~0.1 ms a frame, so
  the whole cost is rasterising and encoding, and that is what is pluggable.

  Rasterisers are an OPEN set, dispatched on `:raster`:

    :librsvg  ffmpeg's own SVG decoder, fonts through fontconfig exactly as
              Chrome resolves them. One ffmpeg per frame range, run in
              parallel, joined by the concat demuxer with no re-encode.
    :resvg    the resvg CLI, one process per frame, then one encode. For an
              ffmpeg built without librsvg.

  A frame's images are inlined as data: URIs before anything is written, so
  every SVG is self-contained: ffmpeg hands librsvg the document from memory,
  where a relative href cannot resolve."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [plato.cli :as cli]
            [plato.snapshot :as snap]
            [plato.video :as video])
  (:import [java.util Base64]
           [java.util.concurrent Executors TimeUnit]))

;; ── processes ──────────────────────────────────────────────────────────────

(defn- exec!
  "Run argv in `dir`, returning its exit code. stdout is discarded and stderr
   inherited, so a failing ffmpeg says why on the terminal that asked."
  [dir argv]
  (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str argv))
             (.directory (io/file dir))
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/DISCARD))
             (.redirectError (java.lang.ProcessBuilder$Redirect/INHERIT)))]
    (.waitFor (.start pb))))

(defn- run-all!
  "Run every argv in `jobs` on `n` threads; throws if any exits non-zero."
  [n dir jobs]
  (let [pool (Executors/newFixedThreadPool (int n))
        futs (mapv (fn [argv] (.submit pool ^Callable (fn [] [argv (exec! dir argv)]))) jobs)
        bad (keep (fn [f] (let [[argv code] (.get f)] (when-not (zero? code) [code argv]))) futs)]
    (.shutdown pool)
    (.awaitTermination pool 1 TimeUnit/MINUTES)
    (when (seq bad)
      (throw (ex-info "a render process failed" {:failed (vec (take 3 bad))})))))

(defn- cores [] (.availableProcessors (Runtime/getRuntime)))

;; ── assets ─────────────────────────────────────────────────────────────────

(def ^:private mime
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp" "svg" "image/svg+xml"})

(defn- data-uri [^java.io.File f]
  (let [ext (str/lower-case (last (str/split (.getName f) #"\.")))]
    (str "data:" (mime ext "application/octet-stream") ";base64,"
         (.encodeToString (Base64/getEncoder)
                          (java.nio.file.Files/readAllBytes (.toPath f))))))

(defn inline-images
  "`graph` with every :image node's relative :content replaced by a data: URI
   read from `asset-dir`. Absolute URLs and data: URIs are left alone, and a
   file that is not there is left as it was, so the rasteriser reports it."
  [graph asset-dir]
  (update graph :nodes
          (fn [nodes]
            (into {}
                  (map (fn [[k nd]]
                         (let [href (:content nd)
                               f (when (and (= :image (:node nd)) (string? href)
                                            (not (re-find #"^(data:|[a-z]+://)" href)))
                                   (io/file asset-dir href))]
                           [k (if (and f (.isFile f)) (assoc nd :content (data-uri f)) nd)])))
                  nodes))))

(defn- inline-plan
  "Inline each distinct graph's images once, not once per frame."
  [plan asset-dir]
  (let [done (memoize #(inline-images % asset-dir))]
    (mapv #(update % :graph done) plan)))

;; ── stages ─────────────────────────────────────────────────────────────────

(defn write-svgs!
  "Write each planned frame as `frame-NNNNN.svg` under `dir`, in parallel.
   Returns the frame count."
  [plan dir]
  (.mkdirs (io/file dir))
  (dorun (pmap (fn [i fr] (spit (io/file dir (snap/frame-name i)) (video/frame-svg fr)))
               (range) plan))
  (count plan))

(def encoders
  "Encoder name -> ffmpeg arguments. :x264 is the portable default; :nvenc
   hands the encode to an NVIDIA GPU; :fast is the preview setting."
  {:x264  ["-c:v" "libx264" "-preset" "veryfast" "-crf" "20"]
   :fast  ["-c:v" "libx264" "-preset" "ultrafast" "-crf" "26"]
   :nvenc ["-c:v" "h264_nvenc" "-preset" "p4" "-cq" "21"]})

(defmulti rasterize!
  "Turn the SVG frames in (:svg-dir job) into the MP4 at (:out job)."
  :raster)

(defmethod rasterize! :librsvg
  [{:keys [svg-dir out frames width height fps encoder jobs]}]
  ;; Each segment opens its own encoder session. NVENC sessions share the
  ;; card's memory with whatever else runs on it: measured 2026-09-29 on an
  ;; RTX 4070 laptop, four at once already failed "CreateInputBuffer failed:
  ;; out of memory". Here the raster is the cost, not the encode, so :nvenc
  ;; keeps two segments rather than competing for the card.
  (let [n (max 1 (min jobs (quot frames 8) (if (= :nvenc encoder) 2 jobs)))
        per (long (Math/ceil (/ (double frames) n)))
        seg (io/file svg-dir "seg")
        parts (for [i (range n) :let [start (* i per)] :when (< start frames)]
                [(io/file seg (format "part-%03d.mp4" i)) start])]
    (.mkdirs seg)
    (run-all! n svg-dir
              (for [[part start] parts]
                (concat ["ffmpeg" "-v" "error" "-y" "-framerate" fps "-start_number" start
                         "-width" width "-height" height "-c:v" "librsvg"
                         "-i" "frame-%05d.svg" "-frames:v" per]
                        (encoders encoder) ["-threads" "2" "-pix_fmt" "yuv420p" (str part)])))
    (spit (io/file seg "list.txt")
          (str/join (map (fn [[part]] (str "file '" (.getAbsolutePath ^java.io.File part) "'\n")) parts)))
    (run-all! 1 svg-dir [["ffmpeg" "-v" "error" "-y" "-f" "concat" "-safe" "0"
                          "-i" (str (io/file seg "list.txt")) "-c" "copy" (str out)]])))

(defmethod rasterize! :resvg
  [{:keys [svg-dir out frames width height fps encoder jobs font resvg-bin]}]
  (let [bin (or resvg-bin (System/getenv "RESVG") "resvg")]
    (run-all! jobs svg-dir
              (for [i (range frames) :let [svg (snap/frame-name i)]]
                [bin "--sans-serif-family" font "-w" width "-h" height
                 svg (str/replace svg #"\.svg$" ".png")]))
    (run-all! 1 svg-dir [(concat ["ffmpeg" "-v" "error" "-y" "-framerate" fps "-i" "frame-%05d.png"]
                                 (encoders encoder) ["-pix_fmt" "yuv420p" (str out)])])))

;; ── the whole render ───────────────────────────────────────────────────────

(def defaults
  {:fps 30 :width 1080 :raster :librsvg :encoder :x264 :font "Noto Sans"})

(def preview
  "Half size and the fastest encode: for looking, not for shipping."
  {:width 540 :encoder :fast})

(defn render!
  "Render `deck` to an MP4. opts: :out (required) :width :fps :raster :encoder
   :jobs :assets (directory relative image hrefs resolve against) :work (the
   frame directory, a temp one by default) :font (resvg's sans-serif face).
   Returns {:out :frames :width :height :ms} with the stage timings."
  [deck opts]
  (when-let [bad (seq (video/unplayable deck))]
    (throw (ex-info (str "slides that are not one scene cannot be rendered without a browser: "
                         (str/join ", " bad) " (use `plato hyperframes`)")
                    {:slides (vec bad)})))
  (let [{:keys [out fps width assets work] :as o} (merge defaults {:jobs (cores)} opts)
        t0 (System/nanoTime)
        ms #(/ (- (System/nanoTime) %) 1e6)
        plan (cond-> (video/deck-frames deck fps) assets (inline-plan assets))
        [w h] (video/frame-size (:graph (first plan)) width)
        dir (or work (str (java.nio.file.Files/createTempDirectory
                           "plato-video" (make-array java.nio.file.attribute.FileAttribute 0))))
        _ (write-svgs! plan dir)
        t1 (System/nanoTime)
        _ (.mkdirs (.getAbsoluteFile (.getParentFile (.getAbsoluteFile (io/file out)))))
        _ (rasterize! (assoc o :svg-dir dir :frames (count plan) :width w :height h
                             :out (.getAbsolutePath (io/file out))))]
    {:out (str out) :frames (count plan) :width w :height h :dir dir
     :ms {:svg (/ (- t1 t0) 1e6) :raster+encode (ms t1) :total (ms t0)}}))

;; ── command line ───────────────────────────────────────────────────────────

;; ── looking at it ──────────────────────────────────────────────────────────

(def ^:private player-socket
  (str (System/getProperty "java.io.tmpdir") "/plato-preview.sock"))

(defn- tell-player!
  "Ask the mpv already listening on `player-socket` to load `path` in place.
   False when no player is listening, which is the cue to start one."
  [path]
  (try
    (with-open [ch (java.nio.channels.SocketChannel/open
                    (java.net.UnixDomainSocketAddress/of ^String player-socket))]
      (.write ch (java.nio.ByteBuffer/wrap
                  (.getBytes (str "{\"command\":[\"loadfile\",\"" path "\",\"replace\"]}\n")
                             "UTF-8")))
      true)
    ;; A refused or missing socket is the answer to "is a player open?", not
    ;; a failure: the caller starts one.
    (catch java.io.IOException _no-player-listening false)))

(defn show!
  "Show the video at `path` in one looping mpv window. The first call opens it;
   every later call swaps the new render into the same window, so iterating on
   a storyboard is edit, eval, watch, with nothing to close."
  [path]
  (let [path (.getAbsolutePath (io/file path))]
    (when-not (tell-player! path)
      (.start (doto (ProcessBuilder. ["mpv" "--really-quiet" "--loop-file=inf" "--keep-open=yes"
                                      (str "--input-ipc-server=" player-socket) path])
                (.redirectOutput (java.lang.ProcessBuilder$Redirect/DISCARD))
                (.redirectError (java.lang.ProcessBuilder$Redirect/DISCARD)))))
    path))

(defn preview!
  "Render `deck` at preview size and show it: the REPL loop for trying a view.
   opts are `render!`'s; :out defaults to one reused file in the temp dir."
  ([deck] (preview! deck {}))
  ([deck opts]
   (let [r (render! deck (merge preview
                                {:out (str (System/getProperty "java.io.tmpdir") "/plato-preview.mp4")}
                                opts))]
     (show! (:out r))
     r)))

(def usage
  "plato video --deck ns/var -o out.mp4 [--width 1080] [--fps 30]
            [--raster librsvg|resvg] [--encoder x264|nvenc|fast]
            [--assets DIR] [--preview] [--play]")

(defn parse-args [args]
  (loop [[a b & more :as xs] args acc {}]
    (cond
      (empty? xs) acc
      (= a "--preview") (recur (rest xs) (merge acc preview))
      (= a "--play") (recur (rest xs) (assoc acc :play? true))
      :else
      (let [k ({"--deck" :deck "-o" :out "--out" :out "--width" :width "--fps" :fps
                "--raster" :raster "--encoder" :encoder "--assets" :assets
                "--jobs" :jobs "--work" :work "--font" :font} a)]
        (if-not k
          (throw (ex-info (str "unknown option " a "\n" usage) {:arg a}))
          (recur more (assoc acc k (case k
                                     (:width :fps :jobs) (parse-long b)
                                     (:raster :encoder) (keyword b)
                                     b))))))))

(defn -main [& args]
  (let [{:keys [deck out play?] :as o} (parse-args args)]
    (when-not (and deck out)
      (println usage)
      (System/exit 2))
    (let [r (try
              (render! (cli/deck-from-var deck) (dissoc o :deck :play?))
              ;; A refused deck or a failed ffmpeg is a user-facing answer,
              ;; not a crash: say it on one line and exit non-zero.
              (catch clojure.lang.ExceptionInfo refused-or-failed
                (binding [*out* *err*] (println "plato video:" (ex-message refused-or-failed)))
                (System/exit 1)))]
      (println (format "%s  %d frames %dx%d  svg %.0f ms  raster+encode %.0f ms  total %.0f ms"
                       (:out r) (:frames r) (:width r) (:height r)
                       (get-in r [:ms :svg]) (get-in r [:ms :raster+encode]) (get-in r [:ms :total])))
      (when play? (show! (:out r)))
      (shutdown-agents))))
