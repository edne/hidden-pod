(ns hidden-pod.tor
  (:import (java.nio.file Files)
           (java.util.concurrent TimeUnit)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaOnionProxyManager)))


(defn- enable-network [proxy-manager]
  (let [control-connection (.controlConnection proxy-manager)]
    (.setConf control-connection "DisableNetwork" "0")))


(defn- bootstrapped? [proxy-manager]
  (let [control-connection (.controlconnection proxy-manager)]
    (and control-connection
         (-> control-connection
             (.getinfo "status/bootstrap-phase")
             (.contains "progress=100")))))


(defn- stop [proxy-manager]
  (let [control-socket (.controlSocket proxy-manager)]
    (if control-socket
      (.close control-socket))))


(defn- start-with-timeout [proxy-manager proxy-context
                          timeout-secs]
  {:pre [(> timeout-secs 0)]}
  (when (.installAndStartTorOp proxy-manager)
    (enable-network proxy-manager)
    (or (->> #(or (bootstrapped? proxy-manager)
                  (Thread/sleep 1000))
             (take timeout-secs)
             (filter identity)
             #(if % (first %)))
        (do (stop proxy-manager)
            (.deleteAllFilesButHiddenServices proxy-context)
            false))))


(defn- start-proxy [proxy-manager]
  (let [proxy-context (.onionProxyContext proxy-manager)]
    (if-not (start-with-timeout proxy-manager proxy-context 30)
            (throw (Exception. "Failed to run Tor")))))



(defn- can-create-parent-dir [file]
  (or (-> file
          .getParentFile
          .exists)
      (-> file
          .getParentFile
          .mkdirs)))


(defn- can-create-file [file]
  (or (.exists file)
      (.createNewFile file)))


(defn- publish-hidden-service* [proxy-manager remote-port local-port]
  (let [control-connection (.controlConnection proxy-manager)]
    (when control-connection
      (let [proxy-context (.onionProxyContext proxy-manager)
            hostname-file (.getHostNameFile proxy-context)]

        (if-not (can-create-parent-dir hostname-file)
          (throw (Exception. "Could not create hostname file parent directory")))
        (if-not (can-create-file hostname-file)
          (throw (Exception. "Could not create hostname file")))

        (let [hostname-file-observer (.generateWriteObserver proxy-context
                                                             hostname-file)
              conf [(str "HiddenServiceDir " (-> hostname-file
                                                 .getParentFile
                                                 .getAbsolutePath))
                    (str "HiddenServicePort " remote-port " 127.0.0.1:" local-port)]]

          (.setConf control-connection conf)
          (.saveConf control-connection)

          (if-not (.poll hostname-file-observer (* 30 1000)
                         TimeUnit/MILLISECONDS)
            (throw (Exception. "Wait for hidden service hostname file to be created expired"))))

        (slurp hostname-file)))))


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
  (let [onion-addr (publish-hidden-service* (new-proxy-manager)
                                            remote-port local-port)]
    onion-addr))
