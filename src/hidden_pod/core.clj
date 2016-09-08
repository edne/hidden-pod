(ns hidden-pod.core
  (:require [hidden-pod.tor :as tor]
            [hidden-pod.server :as server])
  (:gen-class))


(defn -main
  "Serve a folder on an hidden service"
  [& args]
  (if args
    (let [path (first args)
          local-port 3000
          onion-addr (tor/publish-hidden-service local-port 80)]
      (println "Serving at:" onion-addr)
      (server/serve-folder path local-port))))
