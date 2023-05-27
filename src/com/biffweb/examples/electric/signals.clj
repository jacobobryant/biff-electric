(ns com.biffweb.examples.electric.signals
  (:require [com.biffweb :as biff]
            [missionary.core :as m]
            [xtdb.api :as xt]))

(defn xt-signal [{:keys [biff.xtdb/node ::init ::reducer]}]
  (->> (m/observe (fn [f]
                    (let [listener (xt/listen node {::xt/event-type ::xt/indexed-tx :with-tx-ops? true} f)]
                      #(.close listener))))
       (m/reductions reducer init)
       (m/relieve {})
       (m/latest identity)))

(defn recent-messages [{:keys [biff.xtdb/node] :as ctx}]
  (let [initial-value (map first
                           (xt/q (xt/db node)
                                 '{:find [(pull msg [*]) t]
                                   :order-by [[t :desc]]
                                   :in [t0]
                                   :where [[msg :msg/sent-at t]
                                           [(< t0 t)]]}
                                 (biff/add-seconds (java.util.Date.) (* -60 10))))
        reducer (fn [messages tx]
                  (if-some [new-messages (->> tx
                                              ::xt/tx-ops
                                              (keep (fn [[op maybe-doc]]
                                                      (when (and (= op ::xt/put)
                                                                 (contains? maybe-doc :msg/user))
                                                        maybe-doc)))
                                              not-empty)]
                    (let [t0 (- (inst-ms (java.util.Date.))
                                (* 1000 60 10))]
                      (->> (concat new-messages messages)
                           (filter (fn [{:keys [msg/sent-at]}]
                                     (< t0 (inst-ms sent-at))))))
                    messages))]
    (xt-signal (merge ctx {::init initial-value ::reducer reducer}))))

(defn user [{:keys [biff.xtdb/node session] :as ctx}]
  (let [db (xt/db node)
        initial-value (xt/entity db (:uid session))
        reducer (fn [user tx]
                  (or (->> (::xt/tx-ops tx)
                           (keep (fn [[op maybe-doc]]
                                   (when (and (= op ::xt/put)
                                              (= (:xt/id maybe-doc) (:xt/id initial-value)))
                                     maybe-doc)))
                           first)
                      user))]
    (xt-signal (merge ctx {::init initial-value ::reducer reducer}))))
