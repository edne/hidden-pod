(ns hidden-pod.tor
  (:require [clojure.string :as string]
            [clojure.java.io :as io])
  (:import (java.io File
                    FileWriter
                    BufferedWriter
                    PrintWriter)
           (java.nio.file Files)
           (java.net Socket)
           (java.util Scanner)
           (java.util.concurrent TimeUnit)
           (net.freehaven.tor.control TorControlConnection)
           (com.msopentech.thali.toronionproxy OnionProxyManagerEventHandler
                                               FileUtilities)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext
                                                    JavaWatchObserver)))


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


(defn- get-os-name []
  (-> "os.name"
      System/getProperty
      .toLowerCase))


(defn- linux? []
  (.contains (get-os-name) "linux"))


(defn- windows? []
  (.contains (get-os-name) "win"))


(defn- mac? []
  (.contains (get-os-name) "mac"))


(defn- bootstrapped? [control-connection]
  (-> control-connection
      (.getinfo "status/bootstrap-phase")
      (.contains "progress=100")))


(defn- new-observer [file]
  (if-not (can-create-parent-dir file)
    (throw (Exception. (str "Could not create " file " parent directory"))))
  (if-not (can-create-file file)
    (throw (Exception. (str "Could not create " file))))
  (new JavaWatchObserver file))


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


(defn- start-with-timeout [ctx timeout-secs]
  {:pre [(> timeout-secs 0)]}
  (let [control-connection (:control-connection ctx)
        control-socket (:control-socket ctx)]
    (if-not (->> #(or (bootstrapped? control-connection)
                      (Thread/sleep 1000))
                 (take timeout-secs)
                 (filter identity)
                 #(if % (first %)))
      (do (.close control-socket)
          (throw (Exception. "Failed to run Tor")))
      ctx)))


(defn- connect-to-tor [ctx]
  (let [control-port 9051
        control-socket (new Socket "127.0.0.1" control-port)
        control-connection (new TorControlConnection control-socket)]
    (.authenticate control-connection (make-array Byte/TYPE 0))
    (start-with-timeout (merge ctx {:control-socket control-socket
                                    :control-connection control-connection})
                        30)
    control-connection))


(defn- create-context []
  (let [working-dir (->> (into-array java.nio.file.attribute.FileAttribute [])
                         (Files/createTempDirectory "tor-folder") .toFile)
        proxy-context (new JavaOnionProxyContext working-dir)
        hiddenservice-dir-name "hiddenservice"
        torrc-name "torrc"]
    {:proxy-context proxy-context
     :working-dir working-dir
     :torrc-name torrc-name
     :torrc-file  (new File working-dir torrc-name)
     :hostname-file (new File working-dir
                         (str "/" hiddenservice-dir-name "/hostname"))}))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [ctx (create-context)
        control-connection (-> ctx
                               connect-to-tor)
        hostname-file (:hostname-file ctx)
        observer (new-observer hostname-file)]
    (set-conf control-connection
              hostname-file
              remote-port local-port)
    (wait-observer observer 30)
    (slurp hostname-file)))
