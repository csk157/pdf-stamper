;; ## PDF creation from templates
;;
;; pdf-stamper lets you build complete PDF documents without worrying about building the layout in code.
;; Those who have tried will know that it is by no means a simple task getting the layout
;; just right, and building a layout that can adapt to changing requirements can get frustrating in the
;; long run.
;;
;; With pdf-stamper the layout is decoupled from the code extracting and manipulating the data. This leads
;; to a simpler process for building PDF documents from your data: Data placement is controlled by
;; template description datastructures, and data is written to PDF pages defining the layout.

(ns pdf-stamper
  (:require
    [pdf-stamper.context :as context]
    [pdf-stamper.text :as text]
    [pdf-stamper.text.parsed :as parsed-text]
    [pdf-stamper.images :as images]
    [potemkin])
  (:import
    [org.apache.pdfbox.pdmodel PDDocument]
    [org.apache.pdfbox.pdmodel.edit PDPageContentStream]))

;; ## Templates
;;
;; template descriptions are regular Clojure maps with the three keys:
;;
;; - `:name`
;; - `:overflow`
;; - `:holes`
;;
;; The `:overflow` key is optional. It defines which template description to use if/when a hole on
;; this template overflows. If it is not present text will be truncated.
;;
;; ### Holes
;;
;; Holes are what make a template description: They define where on the page the various pieces of data
;; are put, and how.

(defmulti fill-hole
  "There are a number of hole types built in to pdf-stamper, but new hole types can be added
  by implementing this multimethod.

  If a hole type should be able to overflow, the return value from a call to `fill-hole` must
  be a map of the form `{<hole name> {:contents ...}}`."
  (fn [document c-stream hole location-data context] (:type hole)))

;; All holes have these fields in common:
;;
;; - `:height`
;; - `:width`
;; - `:x`
;; - `:y`
;; - `:name`
;; - `:type`
;; - `:priority`
;;
;; Coordinates and widths/heights are always in PDF points (1/72 inch).
;;
;; *Note*: The PDF coordinate system starts from the bottom left, and increasing y-values move the cursor up. Thus, all `(x,y)`
;; coordinates specified in templates should be to the lower left corner.
;;
;; `:priority` is effectively a layering of the contents on template pages; e.g. if you have two overlapping holes on a template
;; the one with the lowest value in `:priority` will be drawn on the page first, and the other hole on top of that.

(defn- fill-holes
  "When filling the holes on a page we have to take into account that Clojure sequences are
  lazy by default; i.e. we cannot expect the side-effects of stamping to the PDF page to have
  happened just by applying the `map` function. `doall` is used to force all side-effects
  before returning the resulting seq of overflowing holes.

  *Note*: Holes where the page does not contain data will be skipped."
  [document c-stream holes page-data context]
  (doall
    (into {}
          (map (fn [hole]
                 (when-let [location-data (get-in page-data [:locations (:name hole)])]
                   (fill-hole document c-stream hole location-data context)))
               (sort-by :priority holes)))))

;; The types supported out of the box are:
;;
;; - `:image`
;; - `:text`
;; - `:text-parsed`
;;
;; For specifics on the hole types supported out of the box, see the documentation for their respective namespaces.

(defmethod fill-hole :image
  [document c-stream hole location-data context]
  (let [data (merge hole location-data)]
    (images/fill-image document
                       c-stream
                       data
                       context)))

(defmethod fill-hole :text-parsed
  [document c-stream hole location-data context]
  (let [data (update-in (merge hole location-data)
                        [:contents :text]
                        #(if (string? %) (parsed-text/get-paragraph-nodes %) %))]
    (text/fill-text-parsed document
                           c-stream
                           data
                           context)))

(defmethod fill-hole :text
  [document c-stream hole location-data context]
  (let [data (merge hole location-data)]
    (text/fill-text document
                    c-stream
                    data
                    context)))

;; ## The Context
;;
;; The context is the datastructure that contains additional data needed by pdf-stamper. For now that is fonts and templates (both descriptions and files).
;; This namespace contains referrals to the three important user-facing functions from the context namespace, namely `add-template`, `add-font`, and `base-context`.
;; For a detailed write-up on the context, please refer to the namespace documentation.

(potemkin/import-vars
  [pdf-stamper.context
   add-template
   add-font
   base-context])

;; ## Filling pages
;;
;; pdf-stamper exists to fill data onto pages while following a pre-defined layout. This is where the magic happens.

(defn- page-template-exists?
  "Trying to stamp a page that requests a template not in the context
  is an error. This is function is used to give a clear name to the
  precondition of `fill-page`."
  [page-data context]
  (get-in context [:templates (:template page-data)]))

(defn- fill-page
  "Every single page is passed through this function, which extracts
  the relevant template and description for the page data, adds it to
  the document being built, and delegates the actual work to the hole-
  filling functions defined above.

  The template to use is extracted from the page data. Using this the
  available holes, template PDF page, and template to use with overflowing
  holes (if any) are extracted from the context.

  Any overflowing holes are handled by calling recursively with the
  overflow. All other holes are copied as-is to the new page, to make
  repeating holes possible.

  *Future*: It would probably be wise to find a better way than a direct
  recursive call to handle overflows. Otherwise handling large bodies of
  text could become a problem."
  [document page-data context]
  (assert (page-template-exists? page-data context)
          (str "No template " (:template page-data) " for page."))
  (let [template (:template page-data)
        template-overflow (context/get-template-overflow template context)
        template-holes (context/get-template-holes template context)
        template-doc (context/get-template-document template context)
        template-page (-> template-doc (.getDocumentCatalog) (.getAllPages) (.get 0))
        template-c-stream (PDPageContentStream. document template-page true false)]
    (.addPage document template-page)
    (let [overflows (fill-holes document template-c-stream (sort-by :priority template-holes) page-data context)
          overflow-page-data {:template template-overflow
                              :locations (when (seq overflows)
                                           (merge (:locations page-data) overflows))}]
      (.close template-c-stream)
      (if (and (seq (:locations overflow-page-data))
               (:template overflow-page-data))
        (conj (fill-page document overflow-page-data context) template-doc)
        [template-doc]))))

(defn fill-pages
  "When the context is populated with fonts and templates, this is the
  function to call. The data passed in as the first argument is a description
  of each individual page, i.e. a seq of maps containing the keys:

  - `:template`
  - `:locations`

  The former is the name of a template in the context, and the latter is a
  map where the keys are hole names present in the template. The value is
  always a map with the key `:contents`, which itself is a map. The key
  in the contents map depends on the type of the hole, as defined in the
  template; e.g. `:image` for image holes, `:text` for text and parsed text
  holes. This is really an implementation detail of the individual functions
  for filling the holes.

  The completed document is written to the resulting
  `java.io.ByteArrayOutputStream`, ready to be sent over the network or
  written to a file using a `java.io.FileOutputStream`."
  [pages context]
  (let [output (java.io.ByteArrayOutputStream.)]
    (with-open [document (PDDocument.)]
      (let [context-with-embedded-fonts (reduce (fn [context [font style]]
                                                  (context/embed-font document font style context))
                                                context
                                                (:fonts-to-embed context))
            open-documents (doall (map #(fill-page document % context-with-embedded-fonts) pages))]
        (.save document output)
        (doseq [doc (flatten open-documents)]
          (.close doc))))
    output))

;; This concludes the discussion of the primary interface to pdf-stamper. Following are the namespace documentations for the functionality
;; that is not directly user-facing.

