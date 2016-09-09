(ns hidden-pod.tor
  (:import (java.nio.file Files)
           (java.net Socket)
           (java.util.concurrent TimeUnit
                                 CountDownLatch)
           (net.freehaven.tor.control TorControlConnection)
           (com.msopentech.thali.toronionproxy OnionProxyManagerEventHandler
                                               FileUtilities)
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


(defn- new-observer [proxy-context file]
  (if-not (can-create-parent-dir file)
    (throw (Exception. (str "Could not create " file " parent directory"))))
  (if-not (can-create-file file)
    (throw (Exception. (str "Could not create " file))))
  (.generateWriteObserver proxy-context file))


(defn- wait-observer [observer timeout]
  (if-not (.poll observer (* timeout 1000)
                 TimeUnit/MILLISECONDS)
    (throw (Exception. "Wait time for file to be created expired"))))


(defn- set-conf [control-connection
                 hostname-file
                 remote-port local-port]
  (.setConf control-connection
            [(str "HiddenServiceDir " (-> hostname-file
                                          .getParentFile
                                          .getAbsolutePath))
             (str "HiddenServicePort " remote-port " 127.0.0.1:" local-port)])
  (.saveConf control-connection))


(defn- stop-proxy [proxy-manager]
  (let [control-socket (.controlSocket proxy-manager)]
    (if control-socket
      (.close control-socket))))


(defn- install-and-start-tor [proxy-manager]
  (.installAndConfigureFiles proxy-manager)
  (let [proxy-context (.onionProxyContext proxy-manager)
        cookie-file (.getCookieFile proxy-context)
        observer (new-observer proxy-context
                               cookie-file)
        tor-path (-> proxy-context .getTorExecutableFile .getAbsolutePath)
        config-path (-> proxy-context .getTorrcFile .getAbsolutePath)
        pid (.getProcessId proxy-context)
        owner "__OwningControllerProcess"
        cmd [tor-path "-f" config-path owner pid]
        env (.getEnvironmentArgsForExec proxy-context)
        process-builder (new ProcessBuilder cmd)]
    (.setEnvironmentArgsAndWorkingDirectoryForStart proxy-context process-builder)
    (let [tor-process (.start process-builder)
          countdown-latch (new CountDownLatch 1)]
      ; .eatstream on InputStream sets .control_port
      (.eatStream proxy-manager (.getInputStream tor-process)
                  false countdown-latch)
      (.eatStream proxy-manager
                  (.getErrorStream tor-process)
                  true nil)
      (wait-observer observer 3)
      (.await countdown-latch))
    (let [control-socket (new Socket "127.0.0.1" (.control_port proxy-manager))
          control-connection (new TorControlConnection control-socket)]
      (doto control-connection
        (.authenticate (FileUtilities/read cookie-file))  ;; TODO: use clj read
        (.takeOwnership)
        (.resetConf [owner])
        (.setEventHandler (new OnionProxyManagerEventHandler))
        (.setEvents ["CIRC" "ORCONN" "NOTICE" "WARN" "ERR"]))
      (set! (.controlSocket proxy-manager) control-socket)
      (set! (.controlConnection proxy-manager) control-connection))))


(defn- start-with-timeout [proxy-manager proxy-context
                           timeout-secs]
  {:pre [(> timeout-secs 0)]}
  (install-and-start-tor proxy-manager)
  (enable-network proxy-manager)
  (or (->> #(or (bootstrapped? proxy-manager)
                (Thread/sleep 1000))
           (take timeout-secs)
           (filter identity)
           #(if % (first %)))
      (do (stop-proxy proxy-manager)
          (.deleteAllFilesButHiddenServices proxy-context)
          false)))


(defn- start-proxy [proxy-manager]
  (let [proxy-context (.onionProxyContext proxy-manager)]
    (if-not (start-with-timeout proxy-manager proxy-context 30)
            (throw (Exception. "Failed to run Tor")))))


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
  (let [proxy-manager (new-proxy-manager)
        proxy-context (.onionProxyContext proxy-manager)
        hostname-file (.getHostNameFile proxy-context)
        observer (new-observer proxy-context
                               hostname-file)]
    (set-conf (.controlConnection proxy-manager)
              hostname-file
              remote-port local-port)
    (wait-observer observer 30)
    (slurp hostname-file)))
