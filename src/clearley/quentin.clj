(ns clearley.quentin
  (require [uncore.throw :as t]
           [uncore.str :as s]
           [clearley.rules :as rules]
           [uncore.collections.worm-ordered-multimap :as omm]
           clojure.stacktrace clojure.pprint)
  (import clearley.ParseState clearley.TransientParseState
          java.util.ArrayList)
  (use clearley.clr uncore.core uncore.memo))
; TODO what does aot do?
; TODO eliminate state, then have parser return results.
; TODO do we need locking?
; TODO kill the double switch--instead assign each item a global int. int switches
; is a neccesary however.
;
; TODO a lot of this is ugly. One thing we can do is put deterministic item parsers
; in protocols (we will need to do this anyway). Then extra generated methods
; can be statically linked.

(def ^:dynamic *myns*)

(defn parse-stream [input]
  (TransientParseState. (seq input)))

; The core abstraction is
; Parse stream handler: parse stream -> parse stream
(declare continue-parsing item-parser-sym embed-parser-with-lookahead)

; For a factory seed, obj, and a factory method, factory,
; looks up the value created by the seed obj in the given namespace's map.
; If it doesn't exist, creates it, interns it, and returns the sym.
(defn get-or-bind [obj factory a-str]
  (let [item-set-var-map @(ns-resolve *myns* 'item-set-var-map)
        ns-lock @(ns-resolve *myns* 'ns-lock)]
    (locking ns-lock
      (let [sym (symbol (str a-str "-" (count (get @item-set-var-map a-str {}))))]
        (if-let [sym0 (get-in @item-set-var-map [a-str obj])]
          sym0
          (let [r (factory obj)]
            (println "Creating" sym "for" (s/cutoff (str obj) 40))
            (intern *myns* sym r)
            (swap! item-set-var-map #(assoc-in % [a-str obj] sym))
            sym))))))

; An evil way to def a thunk that, when called, generates a fn and redefs the sym
; to the fn! Returns the bound symbol.
(defn lookup-thunk [key candidate-thunk a-str]
  (let [item-set-var-map @(ns-resolve *myns* 'item-set-var-map)
        ns-lock @(ns-resolve *myns* 'ns-lock)]
    (locking ns-lock
      (let [sym (symbol (str a-str "-" (count (get @item-set-var-map a-str {}))))]
        (if-let [sym0 (get-in @item-set-var-map [a-str key])]
          sym0
          (let [thunk (fn [& args] (let [r (candidate-thunk)]
                                     (intern *myns* sym r)
                                     (apply r args)))]
            (println "Creating" sym)
            (intern *myns* sym thunk)
            (swap! item-set-var-map #(assoc-in % [a-str key] sym))
            sym))))))

(defn item-id [item]
  (generate ::item item (fn [_ id] id)))

(defn fail [^ParseState stream] (t/RE "Failure to parse at position: " (.pos stream)))

; === Advance loops ===

; Anaphoric, needs a ~'result and a wrapping loop/recur and a ~'state
; For a backlink, calls the item-parser represented by that advance,
; and either looks up and recurs to a main branch, or calls the continuance.
(defn gen-advance-handler [item-set backlink]
  (let [shift-advance (advance-item-set item-set backlink false)
        continue-advance (advance-item-set item-set backlink true)]
    (if shift-advance
      (do
        (if continue-advance
          (println "Stack split in item set\n" (item-set-str item-set)
                   "for return value " (item-str backlink)))
        ; TODO re-enable
        ;(if-let [known-lookahead (:follow backlink)]
         ; `(recur ~(embed-parser-with-lookahead shift-advance known-lookahead))
          `(recur (~(item-parser-sym shift-advance) ~'state
                      (doto (ArrayList.) (.add (.returnValue ~'state))))))
      ; Just call the continuance
      ;(if-let [known-lookahead (:follow backlink)]
       ; (embed-parser-with-lookahead continue-advance known-lookahead)
        `(~(item-parser-sym continue-advance) ~'state
             (doto ~'partial-match (.add (.returnValue ~'state)))))))

; TODO we can make this smaller since we know
; a token consuming shift only happens once
; For an item set, builds the 'continuing table' which handles all steps after
; the first initial shift. Implemented as a loop/recur with a case branch.
; The branch table matches all possible advances (advancing shifts, continues)
; to the appropriate branch. The appropriate branch either recurs (advancing shift)
; or calls the next item set (continue).
(defn gen-advance-loop [{backlink-map :backlink-map :as item-set}]
  `(loop [~'state ~'state]
     (case (.getGoto ~(apply symbol '(^ParseState state)))
       ~@(mapcat (fn [backlink]
                   `(~(item-id backlink) ~(gen-advance-handler item-set backlink)))
                 (omm/keys backlink-map)))))

; Creates an advancer fn. Takes in a parse state and an ArrayList partial match.
(defn advance-looper [item-set]
  (let [r `(fn [~(apply symbol '(^ParseState state))
                ~(apply symbol '(^ArrayList partial-match))]
             ~(gen-advance-loop item-set))]
    ;(println "Advancer:") (clojure.pprint/pprint r)
    (binding [*ns* *myns*]
      (eval r))))

(defn get-advancer-sym [item-set]
  (lookup-thunk item-set #(advance-looper item-set) "advancer"))

; === Shifts and scanning

; Requires 'input, 'state, 'advancer, and 'partial-match.
; Returns a code snippet for handling a state after a scanner is matched.
; Can either take action and return, or call a advancer returning the result.
(defn gen-scanner-handler [scanner item-set action-map]
  (let [shift (get-actions-for-tag scanner action-map :shift)
        return (get-actions-for-tag scanner action-map :return)]
    (if (> (count return) 1)
      (do
        (println "Reduce-reduce conflict in item set\n" (item-set-str item-set))
        (println "for items" (s/separate-str " " (map item-str-follow return)))))
    (if (seq shift)
      (do
        (if (seq return)
          (println "Shift-reduce conflict in item set\n" (item-set-str item-set)))
        `(let [~'state (~(item-parser-sym (pep-item-set (map advance-item shift)))
                           (.shift ~'state ~'input)
                           (doto (ArrayList.) (.add ~'input)))]
           (~(get-advancer-sym item-set) ~'state ~'partial-match)))
        ;`(~(get-advancer-sym item-set)
         ;    (~(item-parser-sym (pep-item-set (map advance-item shift)))
          ;       (.shift ~'state ~'input)
           ;      (doto (ArrayList.) (.add (.returnValue ~'state))))
            ; ~'partial-match))
      (let [returned (first return)] ; Guaranteed to exist
        `(do
           (.setReturnValue ~'state 
                            (apply ~(-> returned :rule rules/get-original :action
                                      (get-or-bind identity "action"))
                                   ~'partial-match))
           (.reduce ~'state ~(get-or-bind (first return) identity "item")
                    ~(-> return first :backlink item-id)))))))

; Parses an item-set together with a partial match. Needs a 'state,
; 'istream, 'input, and 'partial-match.
(defn gen-initial-shift [item-set action-map]
  (let [action-map (action-map item-set)
        cond-pair-fn (fn [scanner]
                       [(list (get-or-bind scanner identity "scanner") 'input)
                        (gen-scanner-handler scanner item-set action-map)])]
    `(if (seq ~'istream)
       (cond ~@(mapcat cond-pair-fn (remove #(= :clearley.clr/term %)
                                            (omm/keys action-map)))
             true (fail ~'state))
       ; Special handling for terminus
       ~(if-let [term-handler (gen-scanner-handler :clearley.clr/term item-set
                                                   action-map)]
          term-handler
          `(fail ~'state)))))

; For when we already know the lookahead state (resolved from a lookahead reduce).
; This is compact enough we can put it inline.
(defn embed-parser-with-lookahead [item-set lookahead]
  (if (= :clearley.clr/term lookahead)
    (gen-scanner-handler lookahead item-set (action-map item-set))
    `(let [~'input (first (.input ~'state))]
       ~(gen-scanner-handler lookahead item-set (action-map item-set)))))

; Creates an fn that takes in a ParseState and a partial-match. Emits a fn that
; matches the whole thing.
(defn gen-parser-body [item-set]
    (let [r `(fn [~(apply symbol '(^ParseState state))
                  ~(apply symbol '(^ArrayList partial-match))]
               (let [~'istream (.input ~'state)
                     ~'input (first ~'istream)]
                 ~(gen-initial-shift item-set action-map)))
          f (binding [*ns* *myns*] (eval r))]
      ;(println "Parser") (clojure.pprint/pprint r)
      f
      #_(fn [& args]
        (println "Parsing item set")
        (print (item-set-str item-set))
        (println "with code")
        (clojure.pprint/pprint r)
        (apply f args))))

(defn item-parser-sym [^clearley.clr.ItemSet item-set]
  (when item-set
    (lookup-thunk (item-set-key item-set)
                  (fn []
                    ;(println "Loading code for item set:")
                    ;(println (item-set-str item-set))
                    (gen-parser-body item-set)) "item-parser")))

(defn new-ns []
  (let [sym (gensym "quentin")
        r (create-ns sym)]
    ;(remove-ns sym) ; TODO
    (binding [*ns* r]
      (use 'clojure.core 'clearley.quentin)
      (import 'clearley.ParseState 'java.util.ArrayList))
    (intern r 'item-set-var-map (atom {})) ; map: seeds -> symbol
    (intern r 'ns-lock (Object.))
    r))

(defn parse [grammar goal input myns mem]
  (try
    (with-memoizer mem
      (binding [*myns* myns]
        (.returnValue
          (@(ns-resolve *myns*
                        (item-parser-sym (pep-item-set [(goal-item goal grammar)])))
              (parse-stream input) (ArrayList.)))))
    ; TODO the below
    (catch RuntimeException e (clojure.stacktrace/print-stack-trace e) nil)))

(defn pprint-parse-stream [{:keys [input output] :as stream}]
  (runmap (fn [item] (if (instance? clearley.clr.Item item)
                       (println (item-str item))
                       (prn item)))
          output))

; Builds a rule match from the output stack and pushes the match to the top
; (think of a Forth operator reducing the top of a stack)
; Final output (for a valid parse) will be a singleton list
(defn ostream-str [val]
  (if (instance? clearley.rules.Match val)
    (pr-str (rules/take-action* val))
    (pr-str val)))
(defn reduce-ostream-helper [ostream val]
  (if (instance? clearley.clr.Item val)
    (let [{:keys [rule match-count]} val]
      (cons (rules/match (rules/get-original rule)
                         (vec (reverse (take match-count ostream))))
            (drop match-count ostream)))
    (do
      (cons (rules/match val []) ostream))))

(defn reduce-ostream [ostream]
  (first (reduce reduce-ostream-helper '() ostream)))

; TODO make thread safe
#_(defn finalize-state [^ParseState state]
  (reduce-ostream (if state (.output state) nil)))
