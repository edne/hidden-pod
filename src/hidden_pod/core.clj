(ns hidden-pod.core
  (:require [hidden-pod.onion :as onion]
            [hidden-pod.server :as server])
  (:gen-class))


(defn -main
  "Serve a folder on an hidden service"
  [& args]
  (if args
    (let [path (first args)
          local-port 3000]
      (onion/publish-hidden-service local-port 80)
      (server/serve-folder path local-port))))
