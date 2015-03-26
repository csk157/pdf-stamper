(ns pdf-stamper.test-generators
  (:require
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]

    [schema.core :as s]
    
    [pdf-stamper.tokenizer.tokens :as t]
    [pdf-stamper.schemas :as schema]))

;; TODO:
;; 1. What is a valid style map? OK
;; 2. Generate valid style maps OK
;; 3. Generate sequences of word tokens with special tokens interleaved OK
;; 4. What is a valid data piece? OK
;; 5. Generate valid data pieces
;; 6. What is a valid template description? OK
;; 7. Generate valid template descriptions
;;

(def BaseStyle
  {:format s/Keyword
   (s/optional-key :character-style) #{(s/enum :italic :bold)}})

(def ListStyle
  (merge BaseStyle
         {:format (s/enum :bullet :number)
          :list {:type (s/enum :bullet :number)
                 (s/optional-key :numbering) s/Any
                 :level s/Num}}))

(def Style
  (s/either
    BaseStyle
    ListStyle))

(defn valid-style?
  [style]
  (not (s/check Style style)))

(def list-style-bullet-gen
  (gen/fmap (partial into {})
            (gen/tuple
              (gen/tuple
                (gen/return :type) (gen/return :bullet))
              (gen/tuple
                (gen/return :level) gen/nat))))

(def bullet-list-style-gen
  (gen/fmap (partial into {})
            (gen/tuple
              (gen/tuple
                (gen/return :format) (gen/return :bullet))
              (gen/tuple
                (gen/return :list) list-style-bullet-gen))))

(def list-style-number-gen
  (gen/fmap (partial into {})
            (gen/tuple
              (gen/tuple
                (gen/return :type) (gen/return :number))
              (gen/tuple
                (gen/return :level) gen/nat))))

(def number-list-style-gen
  (gen/fmap (partial into {})
            (gen/tuple
              (gen/tuple
                (gen/return :format) (gen/return :number))
              (gen/tuple
                (gen/return :list) list-style-number-gen))))

(def base-style-gen
  (gen/fmap (partial into {})
            (gen/tuple
              (gen/tuple
                (gen/elements [:format]) (gen/elements [:paragraph :heading-1 :heading-2 :heading-3]))
              (gen/one-of [(gen/return nil)
                           (gen/tuple
                             (gen/return :character-style) (gen/elements [:bold :italic]))]))))

(defn word-token-gen
  [style]
  (gen/fmap (partial apply t/t-word)
            (gen/tuple (if style (gen/return style) base-style-gen)
                       (gen/not-empty gen/string-alphanumeric))))

(defn number-token-gen
  [style number]
  (gen/return (t/t-number style number)))

(def number-list-gen
  (gen/such-that not-empty
                 (gen/bind number-list-style-gen
                           (fn [style]
                             (gen/vector
                               (gen/tuple
                                 (number-token-gen style 0)
                                 (word-token-gen style)))))))

(defn bullet-token-gen
  [style]
  (gen/return (t/t-bullet style)))

(def bullet-list-gen
  (gen/such-that not-empty
                 (gen/bind bullet-list-style-gen
                           (fn [style]
                             (gen/vector
                               (gen/tuple
                                 (bullet-token-gen style)
                                 (word-token-gen style)))))))

(def list-gen
  (gen/one-of [bullet-list-gen number-list-gen]))

(def new-paragraph-gen
  (gen/bind base-style-gen
            (fn [style] (gen/return (t/t-new-paragraph style)))))

(def new-line-gen
  (gen/bind base-style-gen
            (fn [style] (gen/return (t/t-new-line style)))))

(def new-page-gen
  (gen/bind base-style-gen
            (fn [style] (gen/return (t/t-new-page style)))))

(def special-token-gen
  (gen/frequency [[2 new-paragraph-gen] [7 new-line-gen] [1 new-page-gen]]))

(def tokens-gen
  (gen/vector (gen/frequency [[1 list-gen] [7 (word-token-gen nil)] [2 special-token-gen]])))
