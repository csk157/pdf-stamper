;; ## Text holes
;;
;; Text holes contain extra formatting information compared to e.g. image holes. The amount of extra formatting information
;; required is a matter of the type of text hole. The requirements are described below.

(ns pdf-stamper.text
  (:require
    [pdf-stamper.text.parsed :as parsed-text]
    [pdf-stamper.text.pdfbox :as pdf]))

(defn fill-text-parsed
  "### Parsed text holes

  Holes where `:type` is `:text-parsed`. In addition to the common keys parsed text holes must contain the `:format` key. The format contains the typography
  for the different paragraph types that are available:

  - `:paragraph`
  - `:bullet`
  - `:number`
  - `:head-1`
  - `:head-2`
  - `:head-3`

  The value of each of these keys are maps containing the keys:

  - `:font`
  - `:style`
  - `:size`
  - `:color`
  - `:spacing`
  - `:indent`

  From the top, `:font` and `:style` in combination name a font in the context, which if not present will default to something sensible.
  `:style` is a set, e.g. `#{:bold :italic}`, describing the font style.
  `:size` is a point value describing the font size.
  `:color` is an RGB vector describing the font color, e.g. `[255 255 255]`.

  Spacing and indent are a little more involved. The spacing map makes it possible to define spacing above and below paragraphs, as well as above
  and below individual lines in paragraphs. `:spacing` is a map with the keys `:paragraph` and `:line`, each with the keys `:above` and `:below`.
  The indent map makes it possible to define indentation of all lines of a paragraph type. It is a map with the key `:all`.

  *Future*: This is one place support for first line indent needs to be added.

  `:bullet` paragraphs must contain a key to tell the system which character to use for bullets, called `:bullet-char`."
  [document c-stream data context]
  (let [formatting (merge (:format data)
                          (select-keys data [:width :height]))
        [paragraphs overflow] (parsed-text/paragraphs-overflowing
                                (get-in data [:contents :text])
                                formatting
                                context)]
    (-> c-stream
        (pdf/begin-text-block)
        (pdf/set-text-position (:x data) (+ (:y data) (:height formatting)))
        (pdf/write-paragraphs formatting paragraphs context)
        (pdf/end-text-block))
    (parsed-text/handle-overflow overflow (:name data))))

(defn fill-text
  "### Text holes

  Holes where type is `:text`, distinct from `:text-parsed` in that the text is printed in a single line and with only the formatting present
  in the `:paragraph` key of the `:format` map. Aditionally alignment of the text is controllable using the `:align` key: a map with the keys `:horizontal`
  and `:vertical`. The possible values for horizontal alignment are `:left`, `:right`, and `:center`; the possible values for vertical alignment are
  `:top`, `:bottom`, and `:center`."
  [document c-stream data context]
  (let [formatting (merge (:format data)
                          (select-keys data [:align :width :height]))
        text {:contents (get-in data [:contents :text])
              :format formatting}]
    (-> c-stream
        (pdf/begin-text-block)
        (pdf/set-text-position (:x data) (:y data))
        (pdf/write-unparsed-line text context)
        (pdf/end-text-block))
    nil))

