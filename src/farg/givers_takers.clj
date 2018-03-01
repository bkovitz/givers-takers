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
                    [1 2 2 2 1]
                    [1 2 2 2 1]
                    [1 1 1 1 1]])]
    (o/div= raw (m/esum raw))))

(defn xlat-coord-f
  "Returns a function that translates coordinates [lx ly] in matrix little-m
  to matrix big-m, centered at [cx cy] (in big-m), returning [bx by]."
  [big-m little-m [cx cy]]
  (let [[bxsiz bysiz] (m/shape big-m)
        [lxsiz lysiz :as lsiz] (m/shape little-m)
        [lxctr lyctr] (map #(quot % 2) lsiz)
        x+ (fn [bx lx] (mod (+ bx (- lx lxctr)) bxsiz))
        y+ (fn [by ly] (mod (+ by (- ly lyctr)) bysiz))]
    (fn [[lx ly]]
      [(x+ cx lx) (y+ cy ly)])))

(defn add-centered!
  [target delta [cx cy]]
  (let [xlat (xlat-coord-f target delta [cx cy])]
    (doseq [Δxy (m/index-seq delta)]
      (let [[tx ty] (xlat Δxy)
            t (mget target tx ty)]
        (mset! target tx ty (+ t (apply mget delta Δxy)))))
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

(defn develop [{:keys [organisms] :as world}]
  (S/transform [:organisms S/MAP-VALS]
    (fn [{:keys [g] :as o}]
      (assoc o :phenotype (util/weighted-choice [[:giver g]
                                                 [:taker (- 1.0 g)]])))
    world))

(defn make-one-claim-matrix [claim-amt]
  (m/mul radial-decay-matrix claim-amt))

(defn claim-resources [{:keys [world-size organisms claim-amt] :as world}]
  (let [claims-matrix (m/zero-array [world-size world-size])
        one-claim-matrix (make-one-claim-matrix claim-amt)]
    (dd one-claim-matrix)
    (doseq [o (vals organisms)]
      (add-centered! claims-matrix one-claim-matrix (:loc o)))
    (assoc world :claims-matrix claims-matrix)))

(defn absorb-resources [{:keys [world-size organisms claims-matrix claim-amt
                                sunlight-per-cell]
                         :as world}]
  (let [one-claim-matrix (make-one-claim-matrix claim-amt)]
    (S/transform [:organisms S/MAP-VALS]
      (fn [{:keys [loc] :as o}]
        (let [xlat (xlat-coord-f claims-matrix one-claim-matrix loc)]
          (assoc o :absorbed
            (reduce (fn [total-absorbed xy1]
                      (let [my-claim (apply mget one-claim-matrix xy1)]
                        (+ total-absorbed
                           (cond
                             :let [total-claimed
                                    (apply mget claims-matrix (xlat xy1))]
                             (<= total-claimed sunlight-per-cell)
                               my-claim
                             (* (/ my-claim total-claimed)
                                sunlight-per-cell)))))
                    0.0
                    (m/index-seq one-claim-matrix)))))
    world)))

;;; printing

(defn just-g [{:keys [organisms]} x y]
  (cond
    :let [o (get organisms [x y])]
    (nil? o)
       "  - "
    (str (if (= :giver (:phenotype o)) \G \space)
         (subs (format "%.2f" (:g o)) 1))))

(defn just-absorbed [{:keys [organisms]} x y]
  (cond
    :let [o (get organisms [x y])]
    (nil? o)
       "  - "
    (format "%1.2f" (:absorbed o))))

(defn print-world [{:keys [world-size] :as world}]
  (doseq [y (reverse (range world-size))]
    (println (clojure.string/join \space
               (map #(just-g world % y) (range world-size))))))

(defn print-claims [{:keys [world-size claims-matrix] :as world}]
  (doseq [y (reverse (range world-size))]
    (println (clojure.string/join \space
               (map #(format "%.2f" (mget claims-matrix % y))
                    (range world-size))))))

(defn print-absorbed [{:keys [world-size organisms] :as world}]
  (doseq [y (reverse (range world-size))]
    (println (clojure.string/join \space
               (map #(just-absorbed world % y) (range world-size))))))

;;; running

(def default-opts {:world-size 10 :num-init-orgs 5 :collective-gain 2.0
                   :claim-amt 4.0 :sunlight-per-cell (/ 4.0)})

(defn run
 ([]
  (run {}))
 ([opts]
  (with-rng-seed (:seed opts)
    (with-state [world (merge default-opts opts)]
      (init-organisms)
      (develop)
      (claim-resources)
      (absorb-resources)
      -- (print-world world)
      -- (println)
      -- (print-claims world)
      -- (println)
      -- (print-absorbed world)
      ))))
