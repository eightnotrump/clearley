(ns clearley.rules
  (require clearley.grammar clojure.string uncore.rpartial
           [uncore.throw :as t]
           [uncore.str :as s])
  (use uncore.core))
; Core stuff for context free grammar parsing.

; just for speed
(defrecord Match [rule submatches])

(def match ->Match)

; The instrumented core abstraction
(defrecord CfgRule [dot raw-rule null-results grammar])

(defn advance [cfg-rule]
  (update cfg-rule :dot inc))

(defn cfg-rule [rule grammar]
  (CfgRule. 0 rule {} grammar))

(defn rule? [x]
  (instance? clearley.rules.CfgRule x))

(defn goal? [cfg-rule]
  (-> cfg-rule :raw-rule :name (= ::goal)))

(defn goal-rule [r grammar]
  (CfgRule. 0 {:name ::goal, :tag :seq, :value [(clearley.grammar/normalize r nil)],
               :action identity} {} grammar))

; For a rule that has been nulled out, gets a reduced version of the rule
; appropriate for take-action.
; This is a stopgap and won't be neccesary in a real GLL parser.
(defn get-original [{{:keys [action] :as raw-rule} :raw-rule :keys [null-results]}]
  (if (seq null-results)
    (assoc raw-rule :action (uncore.rpartial/gen-rpartial action null-results))
    raw-rule))

; Null advance: for advancing a rule that can predict the empty string
; See Aycock+Horspool Practical Earley Parsing
(defn null-advance [rule result]
  (let [dot (:dot rule)]
    (update-all rule {:dot inc, :null-results #(assoc % dot result)})))

; Anaphoric, uses 'value and 'dot
; Makes sure a rule has only one subrule
(defmacro check-singleton [tag result]
  `(if (= (count ~'value) 1)
     ~result
     (t/IAE ~tag "must have only one subrule")))

; For rules that only match one clause, once
(defmacro predict-singleton [tag result]
  `(check-singleton ~tag (if (= ~'dot 1) [] ~result)))

; Predicts a rule, returning a seq [rule | fn]
(defmulti predict (fn-> :raw-rule :tag))
(defmethod predict :symbol [{dot :dot, grammar :grammar, {value :value} :raw-rule}]
  (map #(cfg-rule % grammar)
       (predict-singleton :symbol [(get grammar (first value))])))
(defmethod predict :or [{dot :dot, grammar :grammar, {value :value} :raw-rule}]
  (map #(cfg-rule % grammar) (if (= dot 1) [] value)))
(defmethod predict :seq [{dot :dot, grammar :grammar, {value :value} :raw-rule}]
  (if (= dot (count value)) []
    [(cfg-rule (get value dot) grammar)]))
(defmethod predict :star [{dot :dot, grammar :grammar, {value :value} :raw-rule}]
  (map #(cfg-rule % grammar) (check-singleton :star value)))
(defmethod predict :scanner [{dot :dot {value :value} :raw-rule}]
  (predict-singleton :scanner value))
(defmethod predict :token [{dot :dot {value :value} :raw-rule}]
  (predict-singleton :token [(fn [x] (= x (first value)))]))

; Is this rule complete?
(defmulti is-complete? (fn-> :raw-rule :tag))
(defmethod is-complete? :star [{:keys [dot]}] true)
(defmethod is-complete? :seq [{dot :dot {value :value} :raw-rule}]
  (= dot (count value)))
(defmethod is-complete? :default [{:keys [dot]}]
  (= 1 dot))

(defn clause-strs
  ([clauses] (clause-strs clauses 0))
  ([clauses dot]
   (let [clause-strs (map pr-str clauses)]
     (concat (take dot clause-strs)
             (if (zero? dot) [] ["*"])
             (drop dot clause-strs)))))

(defn clause-name [clause]
  (if (:name clause) (:name clause) (pr-str clause)))

(defmulti rule-str (fn-> :raw-rule :tag))
(defmethod rule-str :seq [{dot :dot, {:keys [name value]} :raw-rule :as rule}]
  (let [clause-strs (map clause-name value)]
    (str name " -> "
         (cond (= dot 0) (s/separate-str " " clause-strs)
               (is-complete? rule) (s/separate-str " " (concat clause-strs ["✓"]))
               true (s/separate-str " " (concat (take dot clause-strs) ["•"]
                                                (drop dot clause-strs)))))))
(defmethod rule-str :default [{dot :dot, {:keys [name tag value]} :raw-rule}]
  (str name " -> " tag " ("
       (s/separate-str " " (map clause-name value))
       ")"
       (if (zero? dot) "" " ✓")))

(def ^:dynamic *breadcrumbs*)
; Basically, this does an LL match on the empty string
(defn null-result* [{grammar :grammar :as rule}]
  (if (rule? rule)
    (if (contains? *breadcrumbs* rule)
      (get *breadcrumbs* rule)
      (do
        (set! *breadcrumbs* (assoc *breadcrumbs* rule nil))
        (let [r (if (is-complete? rule)
                  (match (:raw-rule rule) [])
                  (if-let [r (some identity (map null-result* (predict rule)))]
                    (if-let [r2 (null-result* (advance rule))]
                      (match (:raw-rule rule)
                             (apply vector r (:submatches r2))))))]
          (set! *breadcrumbs* (assoc *breadcrumbs* rule r))
          r)))
    nil))
(defn null-result [rule]
  (binding [*breadcrumbs* {}]
    (null-result* rule)))

(defn take-action* [match]
  (if (nil? match)
    (throw (RuntimeException. "Failure to parse"))
    (let [{:keys [rule submatches]} match
          subactions (map take-action* submatches)
          ; TODO clean up the next two lines eventually
          action (get rule :action (fn [] rule))
          action (if (= :token (:tag rule)) (fn [_] (action)) action)]
      ; TODO shouldn't need the above, naked tokens should not appear
      ; on the stack.
      (try
        (apply action subactions)
        (catch clojure.lang.ArityException e
          (throw (RuntimeException. (str "Wrong # of params taking action for rule "
                                         (if (rule? rule)
                                           (rule-str rule)
                                           (pr-str rule)) ", "
                                         "was given " (count subactions))
                                    e)))))))

(defn eager-advance [{grammar :grammar :as rule}]
  (if-let [eager-match (some identity (map null-result (predict rule)))]
    (null-advance rule (take-action* eager-match))))
