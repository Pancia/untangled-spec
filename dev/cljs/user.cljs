(ns ^:figwheel-always cljs.user
  (:require-macros [cljs.test
                    :refer (is deftest run-tests testing)])
  (:require smooth-test.async-spec
            smooth-test.specification-spec
            [smooth-test.runner.browser :as b]
            [cljs.test :as t]))

(enable-console-print!)

(defn run-all-tests []
  (run-tests 'smooth-test.async-spec)
  (run-tests 'smooth-test.specification-spec)

  )

(defn on-load []
  (run-all-tests)
  )

