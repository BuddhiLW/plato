(ns plato.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [plato.json :as json]))

(deftest scalars-write
  (is (= "null" (json/write nil)))
  (is (= "true" (json/write true)))
  (is (= "false" (json/write false)))
  (is (= "12" (json/write 12)))
  (is (= "1.5" (json/write 1.5)))
  (is (= "\"fade\"" (json/write :fade)))
  (is (= "\"sym\"" (json/write 'sym))))

(deftest strings-are-escaped
  (is (= "\"a \\\"q\\\" b\"" (json/write "a \"q\" b")))
  (is (= "\"a\\\\b\"" (json/write "a\\b")))
  (is (= "\"l1\\nl2\\tx\"" (json/write "l1\nl2\tx")))
  (testing "a value cannot close an inline <script> block"
    (is (= "\"\\u003C/script>\"" (json/write "</script>")))
    (is (= "{\"\\u003Ck\":1}" (json/write {"<k" 1})))))

(deftest collections-write
  (is (= "{}" (json/write {})))
  (is (= "[]" (json/write [])))
  (is (= "{\"a\":1,\"b\":[1,2]}" (json/write {:a 1 :b [1 2]})))
  (is (= "[1,\"two\",null]" (json/write [1 "two" nil])))
  (is (= "{\"n\":{\"deep\":true}}" (json/write {:n {:deep true}}))))

(deftest indentation-nests
  (is (= "{\n  \"a\": 1,\n  \"b\": {\n    \"c\": 2\n  }\n}"
         (json/write {:a 1 :b {:c 2}} {:indent 2}))))

(deftest key-fn-renames-keys
  (is (= "{\"backgroundTransition\":\"fade\"}"
         (json/write {:background-transition :fade} {:key-fn json/camel-key})))
  (is (= "hash" (json/camel-key "hash")))
  (is (= "navigationMode" (json/camel-key "navigation-mode"))))

(deftest numbers-stay-valid-json
  (is (= "0.75" (json/write 3/4)))
  (is (= "[0.5,1]" (json/write [1/2 1])))
  (is (= "null" (json/write ##NaN)))
  (is (= "null" (json/write ##Inf)))
  (is (= "null" (json/write ##-Inf)))
  (is (= "{\"a\":null}" (json/write {:a ##NaN}))))

(deftest control-characters-are-u-escaped
  (is (= "\"a\\u0000b\"" (json/write (str "a" (char 0) "b"))))
  (is (= "\"\\u001F\"" (json/write (str (char 0x1F)))))
  (is (= "\"\\u000B\"" (json/write (str (char 0x0B)))))
  (is (= "\"\\n\"" (json/write "\n"))))

(deftest camel-key-uses-the-name-not-the-printed-form
  (is (= "slideNumber" (json/camel-key :slide-number)))
  (is (= "slideNumber" (json/camel-key 'slide-number)))
  (is (= "slideNumber" (json/camel-key "slide-number")))
  (is (= "hash" (json/camel-key :hash))))
