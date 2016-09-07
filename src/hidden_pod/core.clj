(ns hidden-pod.core
  (:import (java.nio.file Files)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaOnionProxyManager))
  (:use [ring.adapter.jetty]
        [ring.middleware.file])
  (:gen-class))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [onion-proxy-manager (->> (into-array java.nio.file.attribute.FileAttribute [])
                                 (Files/createTempDirectory "tor-folder") .toFile
                                 (new JavaOnionProxyContext)
                                 (new JavaOnionProxyManager))]
    (if (.startWithRepeat onion-proxy-manager 30 5)   ;; false if fails
      (.publishHiddenService onion-proxy-manager
                             remote-port local-port)  ;; return .onion address
      (throw (Exception. "Failed to run Tor")))))


(defn file-not-found [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "File not found"})


(defn -main
  "Serve a folder on an hidden service"
  [& args]
  (println "Serving at:" (publish-hidden-service 3000 80))
  (run-jetty (wrap-file file-not-found (first args))
             {:port 3000}))
