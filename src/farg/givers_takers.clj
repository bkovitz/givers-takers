(ns farg.givers-takers
  (:refer-clojure :exclude [rand rand-int cond memoize])
  (:require [better-cond.core :refer [cond]]
            [clojure.core.matrix :as m :refer [array mget mset!]]
            [clojure.core.matrix.operators :as o]
            [clojure.core.matrix.stats :as s]
            [clojure.math.numeric-tower :as math]
            [clojure.core.strint :refer [<<]]
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
                    [1 1 1 1 1]
                    [1 1 1 1 1]
                    [1 1 1 1 1]
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
    (doseq [o (vals organisms)]
      (add-centered! claims-matrix one-claim-matrix (:loc o)))
    (assoc world :claims-matrix claims-matrix)))

(defn absorb-resources [{:keys [claims-matrix claim-amt sunlight-per-cell]
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

(defn givers [world]
  (S/select [:organisms S/MAP-VALS (S/selected? [:phenotype (S/pred= :giver)])]
    world))

(defn takers [world]
  (S/select [:organisms S/MAP-VALS (S/selected? [:phenotype (S/pred= :taker)])]
    world))

(defn neighbors [{:keys [world-size] :as world} loc]
  (let [z (m/zero-array [world-size world-size])
        xlat (xlat-coord-f z radial-decay-matrix loc)]
    (for [xy (m/index-seq radial-decay-matrix)
          :when (not= [2 2] xy)
          :let [organism (S/select-one [:organisms (S/keypath (xlat xy))]
                                       world)]
          :when organism]
      organism)))

(defn organisms-at [locs]
  (S/comp-paths [:organisms (apply S/multi-path (map #(S/keypath %) locs))]))

(defn give-resources [{:keys [collective-gain] :as world}]
  (reduce (fn [world {:keys [absorbed loc] :as giver}]
            (cond
              :let [neighbor-locs (map :loc (neighbors world loc))]
              (empty? neighbor-locs) world
              :let [give-amt (/ (* absorbed collective-gain)
                                (count neighbor-locs))]
              (S/transform (organisms-at neighbor-locs)
                           (fn [neighbor]
                             (update neighbor :bonus (fnil + 0.0) give-amt))
                           world)))
          world
          (givers world)))

(defn place-child
  "Returns [x y] of location of child, or nil if could not be placed."
  [{:keys [organisms world-size] :as world} parent-loc]
  (loop [attempts 0]
    (cond
      :let [dist (util/sample-normal :sd 1.0)
            angle (util/sample-uniform util/circle-interval)
            [Δx Δy] (->> (util/polar->rectangular dist angle) (map math/round))
            [x y] parent-loc
            [x y] [(mod (+ x Δx) world-size) (mod (+ y Δy) world-size)]]
      (not (contains? organisms [x y]))
        [x y]
      (< attempts 20)
        (recur (inc attempts))
      nil)))

(defn make-child [{:keys [givers?] :as world} {:keys [loc g] :as parent}]
  (cond
    :let [child-loc (place-child world loc)]
    (nil? child-loc)
      world
    :let [child-g (if givers?
                    (-> (util/sample-normal :mean g :sd 0.02) util/clamp-unit)
                    0.0)]
    (update world :organisms assoc child-loc {:g child-g :loc child-loc})))

(defn make-children [{:keys [world-size] :as world}]
  (let [z (m/zero-array [world-size world-size])]
    (with-state [new-world (assoc world :organisms {})]
      (doseq [{:keys [absorbed bonus g loc]
               :or {bonus 0.0} :as taker} (takers world)]
        (bind nchildren (sample-poisson (+ absorbed bonus)))
        (dotimes [_ nchildren]
          (make-child taker))))))

;;; printing

(defn just-g [{:keys [organisms]} x y]
  (cond
    :let [o (get organisms [x y])]
    (nil? o)
       "  - "
    (str (case (:phenotype o) :giver \G :taker \T \space)
         (subs (format "%.2f" (:g o)) 1))))

(defn just-absorbed [{:keys [organisms]} x y]
  (cond
    :let [o (get organisms [x y])]
    (nil? o)
       "  - "
    (format "%1.2f" (:absorbed o))))

(defn just-bonus [{:keys [organisms]} x y]
  (cond
    :let [o (get organisms [x y])]
    (nil? o)
       "  - "
    (format "%1.2f" (get o :bonus 0.0))))

(defn print-world [{:keys [world-size organisms generation] :as world}]
  (println (<<
    "generation ~{generation}   "
    "givers ~(count (givers world)), "
    "takers ~(count (takers world))   "
    "g: ~(->> organisms vals (map :g) util/dstats pr-str)"))
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

(defn print-bonuses [{:keys [world-size organisms] :as world}]
  (doseq [y (reverse (range world-size))]
    (println (clojure.string/join \space
               (map #(just-bonus world % y) (range world-size))))))

;        (doseq [Δxy (take nchildren (util/lazy-shuffle
;                                      (m/index-seq radial-decay-matrix)))]
;          (bind child-loc (xlat Δxy))
;          (bind child-g (-> (util/sample-normal :mean g :sd 0.02)
;                            util/clamp-unit))
;          (bind child-g 0.0)
;          (update :organisms assoc child-loc {:g child-g :loc child-loc})
;          )))))

;;; running

(def empty-world ^{:type ::world}
  {:type ::world, :generation 0, :organisms {}})

(def default-opts {:world-size 30 :num-init-orgs 10 :collective-gain 5.0
                   :claim-amt 2.0 :sunlight-per-cell (/ 4.0 25)
                   :ngens 200 :nruns 1 :givers? true})

(defmethod print-method ::world [world ^java.io.Writer w]
  (let [{:keys [generation organisms]} world]
    (.write w (<< "#world{:generation ~{generation} :n ~(count organisms)}"))))

(defn run
 ([]
  (run {}))
 ([opts]
  (with-rng-seed (:seed opts)
    (with-state [world (merge empty-world default-opts opts)]
      (init-organisms)
      (develop)
      -- (print-world world)
      (doseq [generation (range 1 (inc (:ngens world)))]
        (assoc :generation generation)
        (claim-resources)
        (absorb-resources)
        (give-resources)
;        -- (println)
;        -- (print-claims world)
;        -- (println)
;        -- (print-absorbed world)
;        -- (println)
;        -- (print-bonuses world)
        (make-children)
        (develop)
        -- (println)
        -- (print-world world)
      )))))

;NEXT Output columns of data to a text file:
;
;run generation collective-gain n m sd   maybe a histogram of g
;
;for many values of collective-gain.
