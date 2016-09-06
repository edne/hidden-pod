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
  (let [tor-folder (->> (into-array java.nio.file.attribute.FileAttribute [])
                        (Files/createTempDirectory "tor-folder")
                        .toFile)
        onion-proxy-content (new JavaOnionProxyContext tor-folder)
        onion-proxy-manager (new JavaOnionProxyManager onion-proxy-content)]
    (if (.startWithRepeat onion-proxy-manager 30 5)
      (let [onion-url (.publishHiddenService onion-proxy-manager 80 4000)]
        onion-url)
      (throw (Exception. "Failed to run Tor")))))


(defn handler [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "File not found"})


(defn -main
  ;"For now just forward a port to an hidden service"
  "For now just serve a folder"
  [& args]
  ;(println (publish-hidden-service 3000 80))
  (run-jetty (wrap-file handler (first args))
             {:port 3000}))
