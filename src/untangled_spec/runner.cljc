(ns untangled-spec.runner
  #?(:cljs
     (:require-macros
       [cljs.core.async.macros :as a]
       [untangled-spec.runner :refer [define-assert-exprs!]]))
  (:require
    [clojure.core.async :as a]
    [clojure.test :as t]
    [cljs.test #?@(:cljs (:include-macros true))]
    [com.stuartsierra.component :as cp]
    [untangled-spec.assertions :as ae]
    [untangled-spec.reporter :as reporter]
    [untangled-spec.selectors :as sel]
    #?@(:cljs ([untangled.client.mutations :as m]
               [untangled-spec.renderer :as renderer]
               [untangled-spec.router :as router]))
    #?@(:clj  ([clojure.tools.namespace.repl :as tools-ns-repl]
               [clojure.walk :as walk]
               [cognitect.transit :as transit]
               [om.next.server :as oms]
               [ring.util.response :as resp]
               [untangled-spec.impl.macros :as im]
               [untangled-spec.watch :as watch]
               [untangled.server.core :as usc]
               [untangled.websockets.protocols :as ws]
               [untangled.websockets.components.channel-server :as wcs]))))

#?(:clj
   (defmacro define-assert-exprs! []
     (let [test-ns (im/if-cljs &env "cljs.test" "clojure.test")
           do-report (symbol test-ns "do-report")
           t-assert-expr (im/if-cljs &env cljs.test/assert-expr clojure.test/assert-expr)
           do-assert-expr
           (fn [args]
             (let [[msg form] (cond-> args (im/cljs-env? &env) rest)]
               `(~do-report ~(ae/assert-expr msg form))))]
       (defmethod t-assert-expr '=       eq-ae     [& args] (do-assert-expr args))
       (defmethod t-assert-expr 'exec    fn-ae     [& args] (do-assert-expr args))
       (defmethod t-assert-expr 'throws? throws-ae [& args] (do-assert-expr args))
       nil)))
(define-assert-exprs!)

#?(:clj
   (defmethod print-method Throwable [e w]
     (print-method (str e) w)))

#?(:clj
   ;;TODO encode anything not transit serializable with pr-str (maybe just str?)
   ;; make sure we can use (defmethod print-method ...) to change a new type's rep
   (defn- ensure-encodable [tr]
     (letfn [(encodable? [x]
               (some #(% x)
                     [number? string? symbol? keyword? sequential?
                      (every-pred map? (comp not record?))]))]
       (walk/postwalk #(if (encodable? %) % (pr-str %)) tr))))

#?(:clj
   (defn- send-render-tests-msg
     ([system tr cid]
      (let [cs (:channel-server system)]
        (ws/push cs cid 'untangled-spec.renderer/render-tests
          (ensure-encodable tr))))
     ([system tr]
      (->> system :channel-server
        :connected-cids deref :any
        (mapv (partial send-render-tests-msg system tr))))))

#?(:clj
   (defrecord ChannelListener [channel-server]
     ws/WSListener
     (client-dropped [this cs cid] this)
     (client-added [this cs cid]
       (send-render-tests-msg
         this {:test-report (reporter/get-test-report (:test/reporter this))} cid))

     cp/Lifecycle
     (start [this]
       (wcs/add-listener wcs/listeners this)
       this)
     (stop [this]
       (wcs/remove-listener wcs/listeners this)
       this)))

#?(:clj
   (defn- make-channel-listener []
     (cp/using
       (map->ChannelListener {})
       [:channel-server :test/reporter])))

(defn- render-tests [{:keys [test/reporter] :as runner}]
  (let [render* #?(:cljs renderer/render-tests :clj send-render-tests-msg)]
    (render* runner {:test-report (reporter/get-test-report reporter)})))

(defn run-tests [runner & [{:keys [refresh?]
                            :or {refresh? true}}]]
  #?(:clj (when refresh? (tools-ns-repl/refresh)))
  (reporter/reset-test-report! (:test/reporter runner))
  (reporter/with-untangled-reporting
    runner render-tests
    ((:test! runner))))

(defrecord TestRunner [opts]
  cp/Lifecycle
  (start [this]
    (let [#?@(:cljs
               [sel-chan (a/go-loop [selectors (a/<! (:selectors-chan opts))]
                           (sel/set-selectors! opts selectors)
                           (run-tests this)
                           (recur (a/<! (:selectors-chan opts))))])]
      (run-tests this)
      #?(:clj this :cljs (assoc this :close-me sel-chan))))
  (stop [this]
    #?(:cljs (a/close! (:close-me this)))
    this))

(defn- make-test-runner [opts test!]
  (cp/using
    (assoc (map->TestRunner {:opts opts :test! test!})
      :test/renderer #?(:clj nil :cljs (:renderer opts)))
    [:test/reporter #?(:clj :channel-server)]))

(defn test-runner [opts test!]
  #?(:cljs (cp/start
             (cp/system-map
               :test/runner (make-test-runner opts test!)
               :test/reporter (reporter/make-test-reporter)))
     :clj (let [system (atom nil)
                api-read (fn [env k params] {:action #(prn ::read k params)})
                api-mutate (fn [env k params]
                             {:action
                              #(case k
                                 'run-tests/with-new-selectors
                                 #_=> (do
                                        (sel/set-selectors! opts (:selectors params))
                                        (run-tests (:test/runner @system)
                                                   {:refresh? false}))
                                 (prn ::mutate k params))})]
            (reset! system
              (cp/start
                (usc/make-untangled-server
                  :config-path "config/untangled-spec-server-tests-config.edn"
                  :parser (oms/parser {:read api-read :mutate api-mutate})
                  :components {:channel-server (wcs/make-channel-server)
                               :channel-listener (make-channel-listener)
                               :test/runner (make-test-runner opts test!)
                               :test/reporter (reporter/make-test-reporter)
                               :change/watcher (watch/on-change-listener run-tests)}
                  :extra-routes {:routes   ["/" {"_untangled_spec_chsk" :web-socket
                                                 "untangled-spec-server-tests.html" :server-tests}]
                                 :handlers {:web-socket wcs/route-handlers
                                            :server-tests (fn [{:keys [request]} _match]
                                                            (resp/resource-response "untangled-spec-server-tests.html"
                                                              {:root "public"}))}}))))))
