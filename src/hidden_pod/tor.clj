(ns hidden-pod.tor
  (:import (java.nio.file Files)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaOnionProxyManager)))


(defn start-with-timeout [proxy-manager proxy-context
                          timeout-secs]
  {:pre [(> timeout-secs 0)]}
  (when (.installAndStartTorOp proxy-manager)
    (.enableNetwork proxy-manager true)
    (or (->> #(or (.isBootstrapped proxy-manager)
                  (Thread/sleep 1000))
             (take timeout-secs)
             (filter identity)
             #(if % (first %)))
        (do (.stop proxy-manager)
            (.deleteAllFilesButHiddenServices proxy-context)
            false))))


(defn- start-proxy [proxy-manager proxy-context]
  (if-not (start-with-timeout proxy-manager proxy-context 30)
    (throw (Exception. "Failed to run Tor"))))


(defn- new-proxy-manager []
  (let [proxy-context (->> (into-array java.nio.file.attribute.FileAttribute [])
                           (Files/createTempDirectory "tor-folder") .toFile
                           (new JavaOnionProxyContext))
        proxy-manager (new JavaOnionProxyManager proxy-context)]
    (start-proxy proxy-manager proxy-context)
    proxy-manager))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [onion-addr (.publishHiddenService (new-proxy-manager)
                                          remote-port local-port)]
    onion-addr))
