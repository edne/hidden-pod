(ns hidden-pod.tor
  (:import (java.nio.file Files)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaOnionProxyManager)))


(defn- start-proxy [proxy-manager]
  (if-not (.startWithRepeat proxy-manager 30 5)
    (throw (Exception. "Failed to run Tor"))))


(defn- new-proxy-manager []
  (let [proxy-manager (->> (into-array java.nio.file.attribute.FileAttribute [])
                           (Files/createTempDirectory "tor-folder") .toFile
                           (new JavaOnionProxyContext)
                           (new JavaOnionProxyManager))]
    (start-proxy proxy-manager)
    proxy-manager))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [onion-addr (.publishHiddenService (new-proxy-manager)
                                          remote-port local-port)]
    onion-addr))
