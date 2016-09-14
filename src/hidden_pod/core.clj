(ns hidden-pod.core
  (:require [hidden-pod.onion :as onion]
            [hidden-pod.server :as server])
  (:gen-class))


(defn -main
  "Serve a folder on an hidden service"
  [& args]
  (if args
    (let [path (first args)
          local-port 3000
          [hostname private-key] (onion/publish-hidden-service local-port 80)]
      (println "Serving at:" hostname)
      ;(println "Private key:\n" private-key)
      (server/serve-folder path local-port))))
