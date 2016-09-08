(ns hidden-pod.tor
  (:import (java.nio.file Files)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaOnionProxyManager)))


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
