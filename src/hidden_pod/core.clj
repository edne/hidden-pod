(ns hidden-pod.core
  (:import (java.nio.file Files)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaOnionProxyManager))
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

(defn -main
  "For now just forward a port to an hidden service"
  [& args]
  (println (publish-hidden-service 4000 80))
  ;; The service dies when the program terminate
  ) 
