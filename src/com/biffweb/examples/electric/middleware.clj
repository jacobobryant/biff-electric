(ns com.biffweb.examples.electric.middleware
  (:require [ring.adapter.jetty9 :as ring]
            [hyperfiddle.electric-jetty-adapter :as electric-jetty]))

(defn wrap-redirect-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      {:status 303
       :headers {"location" "/app"}}
      (handler ctx))))

(defn wrap-signed-in [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status 303
       :headers {"location" "/signin?error=not-signed-in"}})))

(defn wrap-electric [handler]
  (fn [{:keys [shadow-cljs/version params] :as ctx}]
    (cond
     (not (ring/ws-upgrade-request? ctx))
     (handler ctx)

     (and version (not= version (:HYPERFIDDLE_ELECTRIC_CLIENT_VERSION params)))
     (electric-jetty/reject-websocket-handler 1008 "stale client")

     :else
     {:status 101
      :headers {"upgrade" "websocket"
                "connection" "upgrade"}
      :ws (electric-jetty/electric-ws-adapter
           (partial electric-jetty/electric-ws-message-handler
                    (dissoc ctx :biff/db)))})))
