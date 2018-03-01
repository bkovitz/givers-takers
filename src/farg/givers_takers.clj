(ns farg.givers-takers
  (:refer-clojure :exclude [rand rand-int cond memoize])
  (:require [better-cond.core :refer [cond]]
            [clojure.core.matrix :as m :refer [array mget mset!]]
            [clojure.core.matrix.operators :as o]
            [clojure.core.matrix.stats :as s]
            [clojure.tools.trace :refer [deftrace] :as trace]
            [clojure.pprint :refer [pprint]]
            [clojure.math.combinatorics :as combo]
            [clojure.math.numeric-tower :as math]
            [clojure.java.io :as io :refer [file writer]]
            [com.rpl.specter :as S]
            [farg.util :refer [dd dde sample-poisson rand choose choose-one
                               with-rng-seed mround with-*out*]
             :as util]
            [farg.with-state :refer [with-state]]
            [incanter.stats :as stats])
  (:gen-class))

(m/set-current-implementation :vectorz)

(def radial-decay-matrix
  (let [raw (array [[1 1 1 1 1]
                    [1 2 2 2 1]
                    [1 2 3 2 1]
                    [1 2 2 2 1]
                    [1 1 1 1 1]])]
    (o/div= raw (m/esum raw))))

(defn add-centered!
  "Destructively adds elements of delta to elements of target, centered in
  target at [cx cy]. Returns the mutated target."
  [target delta [cx cy]]
  (let [[txsiz tysiz] (m/shape target)
        [Δxsiz Δysiz :as Δsiz] (m/shape delta)
        [Δxctr Δyctr] (map #(quot % 2) Δsiz)
        x+ (fn [tx Δx] (mod (+ tx (- Δx Δxctr)) txsiz))
        y+ (fn [ty Δy] (mod (+ ty (- Δy Δyctr)) tysiz))]
    (doseq [Δx (range Δxsiz), Δy (range Δysiz)]
      (let [tx (x+ cx Δx), ty (y+ cy Δy)
            t (mget target tx ty)]
        (mset! target tx ty (+ t (mget delta Δx Δy)))))
    target))

(defn lazy-random-xy-coords [size]
  (->> (combo/cartesian-product (range size) (range size))
    (util/lazy-shuffle)
    (map vec)))

(defn index-by [k xs]
  (apply hash-map (mapcat (fn [x] [(get x k) x]) xs)))

(defn init-organisms [{:keys [world-size num-init-orgs] :as world}]
  (let [organisms (->> (lazy-random-xy-coords world-size)
                    (map (fn [[x y]] {:g 0.0 :loc [x y]}))
                    (take num-init-orgs))]
    (assoc world :organisms (index-by :loc organisms))))

(defn claim-resources [{:keys [world-size organisms claim-amt] :as world}]
  (let [claims-matrix (m/zero-array [world-size world-size])
        one-claim (m/mul radial-decay-matrix claim-amt)]
    (doseq [o (vals organisms)]
      (add-centered! claims-matrix one-claim (:loc o)))
    (assoc world :claims claims-matrix)))

;;; printing

(defn just-g [{:keys [organisms]} x y]
  (cond
    :let [o (get organisms [x y])]
    (nil? o)
       "  - "
    (subs (format "%.3f" (:g o)) 1)))

(defn print-world [{:keys [world-size] :as world}]
  (doseq [y (reverse (range world-size))]
    (println (clojure.string/join \space
               (map #(just-g world % y) (range world-size))))))

(defn print-claims [{:keys [world-size claims] :as world}]
  (doseq [y (reverse (range world-size))]
    (println (clojure.string/join \space
               (map #(format "%.2f" (mget claims % y)) (range world-size))))))

;;; running

(def default-opts {:world-size 10 :num-init-orgs 5 :collective-gain 2.0
                   :claim-amt 2.0})

(defn run
 ([]
  (run {}))
 ([opts]
  (with-rng-seed (:seed opts)
    (with-state [world (merge default-opts opts)]
      (init-organisms)
      (claim-resources)
      -- (print-world world)
      -- (print-claims world)
      ))))
