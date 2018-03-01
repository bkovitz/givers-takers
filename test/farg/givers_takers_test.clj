(ns farg.givers-takers-test
  (:refer-clojure :exclude [rand rand-int cond])
  (:require [better-cond.core :refer [cond]]
            [clojure.core.matrix :as m :refer [array mul]]
            [clojure.core.matrix.operators :as o]
            [clojure.core.matrix.stats :as s]
            [clojure.tools.trace :refer [deftrace] :as trace]
            [clojure.pprint :refer [pprint]]
            [clojure.test :refer :all]
            [com.rpl.specter :as S]
            [farg.givers-takers :as gt :refer :all]
            [farg.util :as util :refer [dd dde]]
            [farg.with-state :refer [with-state]]))

(deftest test-add-centered
  (let [delta (m/array [[1 2 3]
                        [4 5 6]
                        [7 8 9]])
        got (add-centered! (m/zero-array [5 5]) delta [0 0])]
    (is (= got
           (array [[5 6 0 0 4]
                   [8 9 0 0 7]
                   [0 0 0 0 0]
                   [0 0 0 0 0]
                   [2 3 0 0 1]])))))
  
