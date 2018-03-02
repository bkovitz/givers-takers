(defproject farg/givers-takers "0.1.0-SNAPSHOT"
  :description "Evolutionary simulation of Giver/Taker species"
  :url "https://github.com/bkovitz/givers-takers"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[better-cond "1.0.1"]
                 [com.rpl/specter "1.1.0"]
                 [farg/util "0.1.0-SNAPSHOT"]
                 [farg/pmatch "0.1.0-SNAPSHOT"]
                 [farg/with-state "0.0.1-SNAPSHOT"]
                 [farg/x "0.1.0-SNAPSHOT"]
                 [incanter "1.5.7"]
                 [net.mikera/vectorz-clj "0.47.0"]
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/math.combinatorics "0.1.1"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/tools.trace "0.7.9"]
                 [net.mikera/core.matrix "0.62.0"]
                 [potemkin "0.4.4"]
                 [seesaw "1.4.5"]]
  :main ^:skip-aot farg.givers-takers
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
