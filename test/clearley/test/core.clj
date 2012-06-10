(ns clearley.test.core
  (:use clearley.core clearley.test.utils lazytest.deftest))

; Some basic tests
(defn rulefn
  [head & clauses]
  (rule head clauses))

(def sum1 (rulefn :sum :sum \+ :times))
(def sum2 (rulefn :sum :times))
(def num1 (rulefn :num \1))

(def simple-parser-rules [sum1
                          sum2
                          (rulefn :times :times \* :num)
                          (rulefn :times :num)
                          num1
                          (rulefn :num \2)
                          (rulefn :num \3)
                          (rulefn :num \4)
                          (rulefn :num \5 \5)])

(def simple-parser (earley-parser :sum simple-parser-rules))

(deftest simple-parser-test
  (with-parser simple-parser
    (is-parsing "1+2")
    (is-parsing "1+2*3+4")
    (is-parsing "1*2+3*4")
    (is-parsing "1+55*3+2*55")
    (is (not (parses? "44")))
    (is (not (parses? "55*23")))
    (is (not (parses? "1+2a")))
    (is-parsing "1+55*2*55+3+55*4")
    (is-ast [[[\1]]] "1")
    (is-ast [[[[\2]]] \+ [[[\3]] \* [\4]]] "2+3*4")
    (is-ast [[[[[\1]]] \+ [[[\2]] \* [\3]]] \+ [[[\4]] \* [\1]]] "1+2*3+4*1")
    (is-ast [[[\5 \5]]] "55")))

; Slightly less basic tests
(deftest simple-match-test
  (with-parser simple-parser
    (is-parse [sum2 [(rulefn :times :num) [num1 [\1]]]] "1")
    (is-parse [sum2 [(rulefn :times :num) [(rulefn :num \5 \5) [\5] [\5]]]] "55")
    (is-parse [sum1 [sum1 [sum2 [(rulefn :times :num) [num1 [\1]]]] [\+]
                     [(rulefn :times :times \* :num)
                      [(rulefn :times :num) [(rulefn :num \2) [\2]]]
                      [\*] [(rulefn :num \3) [\3]]]]
               [\+]
               [(rulefn :times :times \* :num) [(rulefn :times :num)
                                                [(rulefn :num \4) [\4]]]
                [\*] [(rulefn :num \5 \5) [\5] [\5]]]]
              "1+2*3+4*55")))

; Tokenizers
(defn letter-to-num [thechar]
  (if (java.lang.Character/isLetter thechar)
    (char (- (int thechar) 48))
    thechar))

(def letter-to-num-parser (earley-parser :sum letter-to-num simple-parser-rules))

(deftest simple-tokenizer-test
  (with-parser letter-to-num-parser
    (is-ast [[[\a]]] "a")
    (is-ast [[[[[\a]]] \+ [[[\2]] \* [\c]]] \+ [[[\d]] \* [\1]]] "a+2*c+d*1")
    (is-parse [sum2 [(rulefn :times :num) [num1 [\a]]]] "a")))

; Action tests
(def calculator-rules
  [(rule :sum [:sum \+ :times] (fn [a _ b] (+ a b)))
   (rule :sum [:times] identity)
   (rule :times [:times \* :num] (fn [a _ b] (* a b)))
   (rule :times [:num] identity)
   (rule :num [\2] (fn [_] 2))
   (rule :num [\3] (fn [_] 3))])

(def calculator-parser (earley-parser :sum calculator-rules))

(deftest calculator-test
  (with-parser calculator-parser
    (is-action 5 "2+3")
    (is-action 6 "2*3")
    (is-action 19 "2*3+2*2+3*3")))

; Rule embedding
(def weird-rules
  [(rule :a [\a [\b \c] (rule :d [\d])])])

(def weird-rule-parser (earley-parser :a weird-rules))

(deftest rule-embedding-test
  (with-parser weird-rule-parser
    (parses? "abd")
    (parses? "acd")
    (not (parses? "abcd"))))

; Test of defrule
(defrule sum
  ([sum \+ times] (+ sum times))
  ([times] times))
(defrule times
  ([times \* digit] (* times digit))
  ([digit] digit))
(defrule digit [\3] 3)

(def parser2 (build-parser sum))

(deftest build-parser-test
  (with-parser parser2
    (is-action 3 "3")
    (is-action 9 "3*3")
    (is-action 6 "3+3")
    (is-action 15 "3+3*3+3")))

; Extending rules
(extend-rule digit [\4] 4)
(def parser3 (build-parser sum))

(deftest extend-rule-test
  (with-parser parser3
    (is-action 7 "3+4")
    (is-action 12 "3*4")))

; Rule aliasing
(extend-rule sum [sum \- (foo times)] (- sum foo))
(def parser4 (build-parser sum))

(deftest rule-aliasing-test
  (with-parser parser4
    (is-action 0 "3-3")))

; Rule literals in defrule
(def digits567 [(token \5 5) (token \6 6) (token \7 7)])
(extend-rule digit
             ([digits567] digits567)
             ([(a-digit [(token \8 8) (token \9 9)])] a-digit))
(def parser5 (build-parser sum))

(deftest rule-literal-test
  (with-parser parser5
    (is-action 2 "7-5")
    (is-action 1 "9-8")
    (is-action 4 "9-5")))

; Chart str format isn't fixed... so we don't test it
; just test that it is not nil
(deftest print-charts-test
  (is (with-out-str
        (print-charts parser5 "3*4+5-6+7"))))

; Scanners
(add-rules digit (scanner #(= \0 %) (fn [_] 0)))
(def parser6 (build-parser sum))

(deftest scanner-test
  (with-parser parser6
    (is-action 3 "0+3")
    (is-action 1 "3+0*5*4+0+3-5")))

; Token ranges
; should override digit
(def digit (token-range \0 \9 (fn [c] (- (int c) (int \0)))))
(def parser7 (build-parser sum))

(deftest token-range-test
  (with-parser parser7
    (is-action 1 "1")
    (is-action 3 "1+2")
    (is-action 23 "0+1*2+3*4+9")))
