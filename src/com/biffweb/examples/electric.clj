(ns com.biffweb.examples.electric
  (:require [com.biffweb :as biff]
            [com.biffweb.examples.electric.email :as email]
            [com.biffweb.examples.electric.app :as app]
            [com.biffweb.examples.electric.home :as home]
            [com.biffweb.examples.electric.worker :as worker]
            [com.biffweb.examples.electric.schema :as schema]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as tn-repl]
            [malli.core :as malc]
            [malli.registry :as malr]
            [nrepl.cmdline :as nrepl-cmd]
            [shadow.cljs.devtools.server :as shadow-server]
            [shadow.cljs.devtools.api :as shadow-api]))

(def plugins
  [app/plugin
   (biff/authentication-plugin {})
   home/plugin
   schema/plugin
   worker/plugin])

(def routes [["" {:middleware [biff/wrap-site-defaults]}
              (keep :routes plugins)]
             ["" {:middleware [biff/wrap-api-defaults]}
              (keep :api-routes plugins)]])

;; The Electric lib includes a resource at /public/index.html, perhaps by
;; accident, which takes precedence over our Reitit routes.
(defn wrap-workaround-index-thing [handler]
  (fn [req]
    (if (#{"/" "/index.html"} (:uri req))
      {:status 303
       :headers {"location" "/home/"}}
      (handler req))))

(def handler (-> (biff/reitit-handler {:routes routes})
                 biff/wrap-base-defaults
                 wrap-workaround-index-thing))

(def static-pages (apply biff/safe-merge (map :static plugins)))

(defn generate-assets! [ctx]
  (biff/export-rum static-pages "target/resources/public")
  (biff/delete-old-files {:dir "target/resources/public"
                          :exts [".html"]}))

(defn on-save [ctx]
  (biff/add-libs)
  (biff/eval-files! ctx)
  (generate-assets! ctx)
  (test/run-all-tests #"com.biffweb.examples.electric.test.*"))

(def malli-opts
  {:registry (malr/composite-registry
              malc/default-registry
              (apply biff/safe-merge
                     (keep :schema plugins)))})

(def initial-system
  {:biff/plugins #'plugins
   :biff/send-email #'email/send-email
   :biff/handler #'handler
   :biff/malli-opts #'malli-opts
   :biff.beholder/on-save #'on-save
   :biff.xtdb/tx-fns biff/tx-fns
   :com.biffweb.examples.electric/chat-clients (atom #{})})

(defonce system (atom {}))

(defn use-shadow-cljs [{:keys [shadow-cljs/mode] :as ctx}]
  (let [version (str (random-uuid))]
    (shadow-server/start!)
    (if (= mode :dev)
      (do
        (shadow-api/watch :dev)
        (update ctx :biff/stop conj #(shadow-server/stop!)))
      (do
        (shadow-api/release
         :prod {:config-merge [{:closure-defines {'hyperfiddle.electric-client/VERSION version}}]})
        (shadow-server/stop!)
        (assoc ctx :shadow-cljs/version version)))))

(def components
  [biff/use-config
   biff/use-secrets
   use-shadow-cljs
   biff/use-xt
   biff/use-queues
   biff/use-tx-listener
   biff/use-jetty
   biff/use-chime
   biff/use-beholder])

(defn start []
  (let [new-system (reduce (fn [system component]
                             (log/info "starting:" (str component))
                             (component system))
                           initial-system
                           components)]
    (reset! system new-system)
    (generate-assets! new-system)
    (log/info "Go to" (:biff/base-url new-system))))

(defn -main [& args]
  (start)
  (apply nrepl-cmd/-main args))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start))

(comment
  ;; Evaluate this if you make a change to initial-system, components, :tasks,
  ;; :queues, or config.edn. If you update secrets.env, you'll need to restart
  ;; the app.
  (refresh)

  ;; If that messes up your editor's REPL integration, you may need to use this
  ;; instead:
  (biff/fix-print (refresh)))
