(ns clearley.core
  "Tools for parsing and processing linear input.
  The central abstraction is the context-free grammar, where one match rule
  maps to arbitrary sequences of sub-rules.
  Emphasis is on completeness, modularity, and ease of use.
  A functional API and a macro DSL are provided.
 
  See the high-level docs for a further background and overview." 
  (require [clojure string pprint]
           [uncore.throw :as t])
  (use [clearley rules glr]
       [uncore.core]))
; Anything a programmer would need when requiring Clearley is here.

(defn rule
  "Creates a context-free grammar rule. A rule has a required seq of clauses,
  an optional name, and an optional action.
  If not supplied, the default action bundles the args into a list."
  ([clauses] (rule nil clauses nil))
  ([clauses action] (rule nil clauses action))
  ([name clauses action] (context-free-rule name clauses action)))

(defn token
  "Creates a rule that matches a single given token.
  Its action returns the given value if supplied, or the token if not."
  ([a-token] (token a-token a-token))
  ([a-token value] (rule nil [a-token] (fn [_] value))))

(defrecord OneOrMoreImpl [subrule done]
  RuleKernel
  (predict [self] [(rule nil [subrule] vector)
                   (rule nil [self subrule] conj)])
  (rule-deps [self] [subrule])
  (scan [_ _] [])
  (is-complete? [_] done)
  (advance [self] (assoc self :done true))
  (rule-str [_] (str (clause-str subrule) (if done " *" ""))))

(defn one-or-more
  "Creates a rule that matches one or more of a clause. Its action returns a vector
  of the matches."
  ([clause]
   (one-or-more (str (clause-str clause) "+") clause))
  ([name clause]
   (merge (OneOrMoreImpl. clause false) {:name name, :action identity})))

(defrecord Scanner [rulefn scanned]
  RuleKernel
  (rule-deps [_] [])
  (predict [self] [])
  (scan [self input-token]
    (if (and (not (is-complete? self)) (rulefn input-token))
      [(advance self)]
      []))
  (is-complete? [_] scanned)
  (advance [self] (assoc self :scanned true))
  (rule-str [_] (str (clause-str rulefn) (if scanned " *" ""))))

(defn scanner
  "Creates a rule that accepts input tokens. For a token t, if (scanner-fn t)
  is logical true, this rule matches that token. The default action returns the token."
  ([scanner-fn] (scanner scanner-fn identity))
  ([scanner-fn action]
   (wrap-kernel (Scanner. scanner-fn false) nil action)))

(defn char-range
  "Creates a rule that accepts any one character within a given range
  given by min and max, inclusive. min and max should be chars. The default
  action is the identity."
  ([min max]
   (char-range min max identity))
  ([min max action]
  (if (not (and (char? min) (char? max)))
    (t/IAE "min and max should be chars"))
  (let [intmin (int min)
        intmax (int max)]
    (scanner #(let [intx (int %)] (and (<= intx intmax) (>= intx intmin)))
             action))))

(defprotocol Parser
  (parse [parser input] "Parse the given input with the given parser,
                        yielding a match tree."))

(defprotocol ChartParser
  (print-charts [parser input] "Prints this parser's charts to *out*.
                               Format is not fixed. A good explanation of parse charts
                               (for an Earley parser, but same idea) is at
                               http://www.wikipedia.org/wiki/Earley_parser."))

(defn parser
  "Constructs a parser given a map of rules,
  a goal clause, and an optional tokenizer."
  ([goal rules]
   (parser goal identity rules))
  ([goal tokenizer rules]
   (reify
     Parser
     (parse [parser input]
       ; For now, only return first match. If failure, last chart will be empty
       (-> (parse-charts input rules tokenizer goal) last scan-goal first))
     ChartParser
     (print-charts [parser input]
       (pstr-charts (parse-charts input rules tokenizer goal))))))

(defn print-match
  "Pretty-prints a match tree to *out*."
  [match]
  ((fn f [{:keys [rule submatches]} depth]
     (println (apply str (repeat depth " ")) (clause-str rule))
     (domap #(f % (+ depth 2)) submatches))
     match 0)
  nil) ; don't return a tree full of nils

(defn take-action
  "Executes the parse actions for a parser match."
  [match]
  (if (nil? match)
    (throw (RuntimeException. "Failure to parse"))
    (let [{:keys [rule submatches]} match
          subactions (map take-action submatches)
          action (rule-action rule)]
      (try
        (apply action subactions)
        (catch clojure.lang.ArityException e
          (throw (RuntimeException. (str "Wrong # of params taking action for rule "
                                         (rule-str rule) ", "
                                         "was given " (count subactions))
                                    e)))))))

(defn execute
  "Parses some input and executes the parse actions."
  [parser input]
  (take-action (parse parser input)))

; Defrule begins here.
; --In the future, might be able to replace all this nonsense with a parser.

; Macro helper fn. Dequalifies a stringable qualified sym or keyword
(defn- dequalify [strable]
  (-> strable str (clojure.string/split #"/") last symbol))

; Macro helper fn for def rule. Returns a pair of
; [appropriate-symbol-for-action-body, rule-or-rulename]
(defn- process-nonlist-clause [clause]
  (cond (list? clause) (assert false)
        (symbol? clause) [(dequalify clause) `'~clause]
        (keyword? clause) [(dequalify clause) clause]
        (= java.lang.String (type clause)) [(symbol (str clause)) clause]
        true ['_ clause])) ; can't be an arg in a fn

; Macro helper fn for def rule. Returns a pair of
; [appropriate-symbol-for-action-body, rule-or-rulename-or-uneval'd-form]
(defn- process-clause [clause]
  (if (list? clause)
    (if-let [thename (first clause)]
      (let [therule (second clause)]
        [thename (if (list? therule) ; A form to evaluate
                   ; Return it; will be eval'd when defrule called
                   therule
                   ; See what process-nonlist-clause has to say
                   (second (process-nonlist-clause therule)))])
      (t/IAE "Not a valid subrule: " clause))
    (process-nonlist-clause clause)))

; Macro helper fn. Builds the `(rule ...) bodies for defrule.
; Head: a symbol. impls: seq of (bindings bodies+) forms.
(defn- build-defrule-rule-bodies [head impls]
  (vec (map (fn [impl]
              (let [clauses (first impl)]
                (if (seq clauses)
                  (let [processed-clauses (map process-clause clauses)]
                    `(rule '~head [~@(map second processed-clauses)]
                           (fn [~@(map first processed-clauses)] ~@(rest impl))))
                  (t/IAE "Rule clauses must be seqable"))))
            impls)))

; Macro helper fn. Builds the body for defrule and related macros.
; Head: a symbol. impl-or-impls: (clauses bodies+) or ((clauses bodies+)+).
(defn- build-defrule-bodies [head impl-or-impls]
  (let [first-form (first impl-or-impls)]
    (cond (or (vector? first-form) (string? first-form))
          (build-defrule-rule-bodies head [(apply list first-form
                                                  (rest impl-or-impls))])
          (seq? first-form) (build-defrule-rule-bodies head impl-or-impls)
          true (t/IAE "Not a valid defrule; "
                     "expected clause vector, string, or clause-body pairs"))))

(defmacro defrule
  "Defs a parser rule or seq of parser rules. See the docs for
  the full defrule syntax.
  
  Usage:
  (defrule symbol [clauses] action?)
  (defrule (symbol [clauses] action?)+)

  Valid clauses:
  any rule object
  a symbol pointing to a seq of rules
  [rule or rule-symbol]+
  (rule-alias-symbol rule-symbol)
  (rule-alias-symbol [rule or rule-symbol]+)
  
  Defines one or more rules and binds them in a seq to the given var.
  The rule's name will be the given (unqualified) symbol by default.
  The optional action form defines a parse action, where the symbols will be bound
  to the results of the actions of the correspoinding subrules.

  Example:

  (defrule sum [num \\+ (num2 num)] (+ num num2))
  
  The above rule matches two nums (one of which is aliased as num2)
  and adds them together. If a parse action is not provided, a default
  will be used which bundles its args into a list. The rule will be bound
  to 'sum in the current namespace.
  
  Symbols in the defrule bodies do not become qualified."
  [sym & impl-or-impls]
  `(def ~sym ~(build-defrule-bodies sym impl-or-impls)))

(defmacro extend-rule
  "Like defrule, but for an existing symbol with some rules bound to it,
  such as one defined by defrule."
  [sym & impl-or-impls]
  `(def ~sym (vec (concat ~sym ~(build-defrule-bodies sym impl-or-impls)))))

(defmacro add-rules
  "Adds some amount of additional rules to a symbol with rules bound to it,
  such as one defined by defrule. The given rules must be rule objects, not
  defrule-style definitions."
  [sym & rules]
  `(def ~sym (vec (concat ~sym [~@rules]))))

; In the future, we might bind &env to theenv
; The form of &env is not fixed by Clojure authors so don't do it now
(defn build-grammar-with-ns
  "Builds a grammar in the given ns from the given goal clause.
  Symbols in the grammar will be unqualified."
  [goal thens]
  (build-grammar-1 goal thens {}))

(defmacro build-grammar
  "Builds a grammar in the current ns from the given goal clause.
  A grammar is a map from symbols to seqs of rules.
  Symbols in the grammar are unqualified."
  [goal]
  `(build-grammar-with-ns '~goal *ns*))

(declare close-rule)

(defrecord ^:private ClosedRule [rule grammar]
  RuleKernel
  (predict [self] 
    (map #(close-rule % grammar)
         (predict-clause (predict rule) grammar)))
  (rule-deps [_] (rule-deps rule))
  (scan [self input-token] (map #(assoc self :rule %) (scan rule input-token)))
  (is-complete? [_] (is-complete? rule))
  (advance [self] (assoc self :rule (advance rule)))
  (rule-str [_] (str "::" (rule-str rule))))

(defn close-rule [goal grammar]
  "Creates a rule that closes over the given grammar. This rule
  can be used as a rule in other grammars, while being unaffected by that grammar.

  Closed rules are indicated in charts with a :: prefix."
  (let [goal-rule (to-rule goal)]
    (assoc goal-rule :kernel (ClosedRule. goal-rule grammar))))

(defmacro build-parser
  "Build a parser in the current ns from the given goal rule and an
  optional tokenizer."
  ([goal]
   `(build-parser ~goal identity))
  ([goal tokenizer]
   `(build-parser-with-ns '~goal ~tokenizer *ns*)))

(defn build-parser-with-ns
  "Build a parser in a given ns from the given goal rule and tokenizer."
  [goal tokenizer thens]
  (parser goal tokenizer (build-grammar-with-ns goal thens)))
