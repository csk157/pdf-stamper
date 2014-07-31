(ns pdt.pdf
  (:require
    [clojure.edn :as edn]
    [pdt.context :as context]
    [pdt.pdf.images :as images]
    [pdt.pdf.text :as text]
    [pdt.pdf.text.parsed :as parsed-text])
  (:import
    [org.apache.pdfbox.pdmodel PDDocument]
    [org.apache.pdfbox.pdmodel.edit PDPageContentStream]
    [org.apache.pdfbox.pdmodel.graphics.xobject PDXObjectImage PDPixelMap]))

;;;
;;; Living in IO land here, so tread carefully!
;;;

(defn- fill-page
  "document: a PDDocument instance
  page-data: data for a single page
  context: fonts, templates, etc.

  Expected to return the opened PDDocument instance for the template,
  for closing after PDF is completed and saved."
  [document page-data context]
  (let [template (:template page-data)
        template-overflow (context/get-template-overflow template context)
        template-holes (context/get-template-holes template context)
        template-doc (context/get-template-document template context)
        template-page (-> template-doc (.getDocumentCatalog) (.getAllPages) (.get 0))
        template-c-stream (PDPageContentStream. document template-page true false)]
    (.addPage document template-page)
    (let [overflows (doall
                      (map (fn [hole]
                             (when (get-in page-data [:locations (:name hole)])
                               (condp = (:type hole)
                                 :image (let [data (merge hole
                                                          (get-in page-data
                                                                  [:locations (:name hole)]))]
                                          (images/fill-image document
                                                             template-c-stream
                                                             data
                                                             context))
                                 :text-parsed (let [data (update-in (merge hole
                                                                           (get-in page-data
                                                                                   [:locations (:name hole)]))
                                                                    [:contents :text]
                                                                    #(if (string? %) (parsed-text/get-paragraph-nodes %) %))]
                                                (text/fill-text-parsed document
                                                                       template-c-stream
                                                                       data
                                                                       context))
                                 :text (let [data (merge hole
                                                         (get-in page-data
                                                                 [:locations (:name hole)]))]
                                         (text/fill-text document
                                                         template-c-stream
                                                         data
                                                         context)))))
                           (sort-by :priority template-holes)))
          overflow-page-data {:template template-overflow
                              :locations (into {} overflows)}]
      (.close template-c-stream)
      (if (seq (:locations overflow-page-data))
        (conj (fill-page document overflow-page-data context) template-doc) ;; TODO: Stack overflow waiting to happen...
        [template-doc]))))

(defn fill-pages
  [pages context]
  (let [output (java.io.ByteArrayOutputStream.)]
    (with-open [document (PDDocument.)]
      (let [context-with-embedded-fonts (reduce (fn [context [font style]]
                                                  (context/embed-font document font style context))
                                                context
                                                (:fonts-to-embed context))
            open-documents (doall (map #(fill-page document % context-with-embedded-fonts) pages))]
        (.save document output)
        (.save document "test123.pdf")
        (doseq [doc (flatten open-documents)]
          (.close doc))))
    output))


;;; Test data

(def template-1 (edn/read-string (slurp "test/templates/template-1.edn")))
(def template-2 (edn/read-string (slurp "test/templates/template-2.edn")))
(def template-pdf-1 "test/templates/template-1.pdf")
(def template-pdf-2 "test/templates/template-2.pdf")

(def font-1 "test/templates/OpenSans-Regular.ttf")

(def image-1 (javax.imageio.ImageIO/read (clojure.java.io/as-file "test/templates/image-1.jpg")))
(def text-1
  "<pp><h1>Banebeskrivelse</h1><p>Ham aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa<em><strong>hock</strong> ullamco</em> quis, t-bone biltong kielbasa sirloin prosciutto non <b>ribeye</b> andouille chuck mollit.</p><h2>Regler</h2><p>Sausage commodo ex cupidatat in pork loin. Ham leberkas sint pork chop bacon. <em><b>Chuck ea dolor</b></em>, salami sausage ad duis tongue officia nisi veniam pork belly cupidatat.</p><p>test number two</p><h3>Huskeliste</h3><ul><li>one time <b>ape</b> and duck</li><li>two times <em><b>ape</b></em> and duck</li><li>abekat er en led hest, med mange gode grise i stalden. Test</li></ul></pp>")

(def text-2
  "<pp><p>Abekat <em>er <b>en</b> ged</em></p></pp>")

(def context (->> context/base-context
                  (context/add-font font-1 :open-sans #{:regular})
                  (context/add-template template-1 template-pdf-1)
                  (context/add-template template-2 template-pdf-1)))

(def pages
  [{:template :template-1
    :locations {:one {:contents {:image image-1}}
                :two {:contents {:text text-1}}}}

   {:template :template-2
    :locations {:one {:contents {:text text-2}}
                :two {:contents {:text "Monkey balls!"}}}}])

(def out (fill-pages pages context))

