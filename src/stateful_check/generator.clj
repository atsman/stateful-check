(ns stateful-check.generator
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.rose-tree :as rose]
            [stateful-check.symbolic-values :as sv]
            [stateful-check.command-utils :as u]))

(def setup-var (sv/->RootVar "setup"))

(defn- command-obj-gen [spec state]
  (let [unwrap #(if (var? %) @% %)]
    (if-let [generate-command (:model/generate-command spec)]
      (gen/such-that #(u/check-requires % state)
                     (gen/fmap (fn [name]
                                 (assoc (unwrap (get (:commands spec) name))
                                        :name name))
                               (generate-command state)))
      (let [valid-commands (->> (:commands spec)
                                (map (fn [[name cmd-obj]]
                                       (assoc (unwrap cmd-obj)
                                              :name name)))
                                (filter #(u/check-requires % state)))]
        (assert (seq valid-commands)
                (str "At least one command must pass :model/requires with state: " (prn-str state)))
        (gen/elements valid-commands)))))

(defn- command-gen [spec state]
  (gen/gen-bind (command-obj-gen spec state)
                (fn [cmd-obj-rose]
                  (let [cmd-obj (rose/root cmd-obj-rose)]
                    (gen/fmap #(cons cmd-obj %)
                              (u/args-gen cmd-obj state))))))

(defn- command-sequence-tree-gen [spec state vars]
  (gen/frequency
   [[1 (gen/gen-pure [[] state])]
    [(count vars) (gen/gen-bind (command-gen spec state)
                                (fn [cmd-and-args-tree]
                                  (let [[cmd-obj & args] (rose/root cmd-and-args-tree)
                                        result (first vars)]
                                    (if (u/check-precondition cmd-obj state args)
                                      (let [next-state (u/model-make-next-state cmd-obj state args result)]
                                        (gen/gen-bind (command-sequence-tree-gen spec next-state (next vars))
                                                      (fn [[cmd-list-tail-tree next-next-state]]
                                                        (gen/gen-pure [(cons (rose/fmap #(cons result %)
                                                                                        cmd-and-args-tree)
                                                                             cmd-list-tail-tree)
                                                                       next-next-state]))))
                                      (command-sequence-tree-gen spec state vars)))))]]))

;; if the test requires more than 26 threads then I am impressed
(def ^:private thread-names "abcdefghijklmnopqrstuvwxzy")

(defn index->letter [n]
  (nth thread-names n))

(defn make-vars [length thread-id]
  (map (fn [i] (sv/->RootVar (str (inc i)
                                 (when thread-id
                                   (index->letter thread-id)))))
       (range length)))

(defn- parallel-command-sequence-gen [spec state {:keys [max-length max-size threads]}]
  (let [[max-seq max-par] (if (map? max-length)
                            ((juxt :sequential :parallel) max-length)
                            [max-length max-length])]
    (gen/sized
     (fn [size]
       (let [size-factor (/ size max-size)
             seq-length (int (* max-seq size-factor))
             par-length (int (* max-par size-factor))]
         (letfn [(parallel-commands-gen [n state]
                   (if (zero? n)
                     (gen/gen-pure [])
                     (gen/gen-bind (command-sequence-tree-gen spec state (make-vars par-length (dec n)))
                                   (fn [[tree state]]
                                     (gen/gen-bind (parallel-commands-gen (dec n) state)
                                                   (fn [other-trees]
                                                     (gen/gen-pure (conj other-trees (vec tree)))))))))]
           (gen/gen-bind (command-sequence-tree-gen spec state (make-vars seq-length nil))
                         (fn [[sequential-trees state]]
                           (gen/gen-bind (parallel-commands-gen threads state)
                                         (fn [parallel-trees]
                                           (gen/gen-pure {:sequential (vec sequential-trees)
                                                          :parallel parallel-trees})))))))))))

(defn- shrink-parallel-command-sequence
  ([{:keys [sequential parallel]}] (shrink-parallel-command-sequence sequential parallel))
  ([sequential parallel]
   (let [parallel (filterv seq parallel)]
     (rose/make-rose {:sequential (mapv rose/root sequential)
                      :parallel (mapv #(mapv rose/root %) parallel)}
                     (concat
                      (for [sequential (rose/remove sequential)]
                        ;; remove/shrink a command from the sequential prefix
                        (shrink-parallel-command-sequence sequential parallel))
                      (for [[i thread] (map vector (range) parallel)
                            shrunk-thread (rose/remove thread)]
                        ;; remove/shrink a command from a parallel thread
                        (shrink-parallel-command-sequence sequential
                                                          (assoc parallel i shrunk-thread)))
                      (for [[i thread] (map vector (range) parallel)]
                        ;; pull one of the first parallel commands into the sequential prefix
                        (shrink-parallel-command-sequence (conj sequential (first thread))
                                                          (update parallel i (comp vec next))))
                      (for [sequential (rose/remove sequential)
                            sequential (rose/remove sequential)]
                        ;; remove/shrink a command from the sequential prefix
                        (shrink-parallel-command-sequence sequential parallel))
                      (for [[i thread] (map vector (range) parallel)
                            shrunk-thread (rose/remove thread)
                            shrunk-thread (rose/remove thread)]
                        ;; remove/shrink two commands from a parallel thread
                        (shrink-parallel-command-sequence sequential
                                                          (assoc parallel i shrunk-thread))))))))

(defn- valid-commands? [cmd-objs state bindings]
  (boolean (reduce (fn [[state bindings] [handle cmd-obj & args]]
                     (if (and (u/check-requires cmd-obj state)
                              (every? (fn [arg]
                                        (if (satisfies? sv/SymbolicValue arg)
                                          (sv/valid? arg bindings)
                                          true))
                                      args)
                              (u/check-precondition cmd-obj state args))
                       [(u/model-make-next-state cmd-obj state args handle)
                        (conj bindings handle)]
                       (reduced false)))
                   [state bindings] cmd-objs)))

(defn every-interleaving
  ([{:keys [sequential parallel]}] (every-interleaving sequential parallel))
  ([sequential parallel]
   (let [parallel (filterv seq parallel)]
     (if (empty? parallel)
       (list sequential)
       (mapcat (fn [i thread]
                 (every-interleaving (conj sequential (first thread))
                                     (update parallel i (comp vec next))))
               (range) parallel)))))

(defn commands-gen [spec {:keys [threads max-length max-size]}]
  (let [init-state-fn (or (:model/initial-state spec)
                          (:initial-state spec)
                          (constantly nil))
        init-state (if (:real/setup spec)
                     (init-state-fn setup-var)
                     (init-state-fn))
        init-bindings (if (:real/setup spec)
                        #{setup-var}
                        #{})]
    (->> (parallel-command-sequence-gen spec init-state {:max-length (or max-length 10)
                                                         :threads (or threads 0)
                                                         :max-size (or max-size 200)})
         (gen/gen-fmap shrink-parallel-command-sequence)
         (gen/such-that (fn [cmds]
                          ;; we need to generate lists of commands
                          ;; that are valid no matter how they're
                          ;; executed (assuming each command is
                          ;; atomic), that way we know that no matter
                          ;; how the execution goes, we'll be able to
                          ;; tell the difference between a failure and
                          ;; a success
                          (every? #(valid-commands? % init-state init-bindings)
                                  (every-interleaving cmds)))))))
