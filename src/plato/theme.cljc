(ns plato.theme
  "GENERATED from theme/plato.tokens.edn. Do not edit; run `plato theme theme/plato.tokens.edn`.")

(def tokens
  "Theme tokens as data: :meta, :color, :scale, :type, :scene."
  {:meta {:prefix "plato"
          :name "Plato Midnight"
          :reveal-theme "night"}
   :color {:orange "#FF862F"
           :gray "#8A8F98"
           :muted "#9aa7b5"
           :white "#FFFFFF"
           :row-alt "rgba(255, 255, 255, 0.028)"
           :yellow "#FFFF3B"
           :fg "#edf2f7"
           :warn "#f0ac5f"
           :green "#83C167"
           :line-strong "#2a3544"
           :gold "#F0AC5F"
           :panel "#141a22"
           :line "#273140"
           :red "#FC6255"
           :ok "#5fd39a"
           :blue "#58C4DD"
           :info "#5fb4f0"
           :pink "#FF69B4"
           :teal "#5CD0B3"
           :purple "#9A72AC"
           :bg "#0b0e13"
           :accent "#f0ac5f"
           :grey "#8A8F98"
           :black "#000000"}
   :scale {:radius "0.65rem"
           :radius-lg "0.75rem"
           :media-max "400px"
           :code-max "356px"
           :scene-max "440px"
           :gap "1rem"}
   :type {:sans "Inter, ui-sans-serif, system-ui, sans-serif"
          :mono "ui-monospace, SFMono-Regular, \"JetBrains Mono\", Menlo, monospace"}
   :scene {:palette [:teal :gold :grey :gray :red :yellow :white :blue :green :purple :orange :pink :black]
           :background :bg
           :fallback :grey}})
