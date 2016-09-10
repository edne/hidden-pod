(ns hidden-pod.tor
  (:require [clojure.string :as s]
            [clojure.java.io :as io])
  (:import (java.nio.file Files)
           (java.net Socket)
           (java.util Scanner)
           (java.util.concurrent TimeUnit)
           (net.freehaven.tor.control TorControlConnection)
           (com.msopentech.thali.toronionproxy OnionProxyManagerEventHandler
                                               FileUtilities)
           (com.msopentech.thali.java.toronionproxy JavaOnionProxyContext)))


(defn- enable-network [control-connection]
  (.setConf control-connection "DisableNetwork" "0"))


(defn- bootstrapped? [control-connection]
  (-> control-connection
      (.getinfo "status/bootstrap-phase")
      (.contains "progress=100")))


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


(defn- stop-proxy [control-socket]
  (if control-socket
    (.close control-socket)))


(defn- read-control-port [tor-process]
  (let [input-stream (.getInputStream tor-process)
        scanner (new Scanner input-stream)]
    (->> #(.nextLine scanner)
         repeatedly
         (map #(re-find #"listening on port (\d+)\." %))
         (filter identity)
         first  ;; the first non-empy list of matches ["1234." "1234"]
         last   ;; the last match in the list
         Integer/parseInt)))


(defn- start-tor-process [proxy-context owner]
  (let [tor-path (-> proxy-context .getTorExecutableFile .getAbsolutePath)
        config-path (-> proxy-context .getTorrcFile .getAbsolutePath)
        pid (.getProcessId proxy-context)
        cmd [tor-path "-f" config-path owner pid]
        process-builder (new ProcessBuilder cmd)]
    (.setEnvironmentArgsAndWorkingDirectoryForStart proxy-context process-builder)
    (.start process-builder)))


(defn- configure-files [data]
  (let [proxy-context (:proxy-context data)]
    (.installFiles proxy-context)
    (if-not (-> proxy-context .getTorExecutableFile (.setExecutable true))
      (throw (Exception. "Could not make Tor executable")))
    (let [torrc-file (.getTorrcFile proxy-context)
          cookie-file    (-> proxy-context .getCookieFile .getAbsolutePath)
          data-directory (-> proxy-context .getWorkingDirectory .getAbsolutePath)
          geoip-file     (-> proxy-context .getGeoIpFile .getName)
          geoipv6-file   (-> proxy-context .getGeoIpv6File .getName)]
      (with-open [r (io/input-stream torrc-file)]
        (println "CookieAuthFile" cookie-file)
        (println "DataDirectory"  data-directory)
        (println "GeoIPFile"      geoip-file)
        (println "GeoIPv6File"    geoipv6-file)))))


(defn- authenticate [control-connection cookie-file owner]
  (doto control-connection
    (.authenticate (FileUtilities/read cookie-file))  ;; TODO: use clj read
    (.takeOwnership)
    (.resetConf [owner])
    (.setEventHandler (new OnionProxyManagerEventHandler))
    (.setEvents ["CIRC" "ORCONN" "NOTICE" "WARN" "ERR"])))


(defn- start-with-timeout [data timeout-secs]
  {:pre [(> timeout-secs 0)]}
  (let [proxy-context (:proxy-context data)
        control-connection (:control-connection data)
        control-socket (:control-socket data)]
    (enable-network control-connection)
    (if-not (->> #(or (bootstrapped? control-connection)
                      (Thread/sleep 1000))
                 (take timeout-secs)
                 (filter identity)
                 #(if % (first %)))
      (do (stop-proxy control-socket)
          (.deleteAllFilesButHiddenServices proxy-context)
          (throw (Exception. "Failed to run Tor")))
      data)))


(defn- start-tor [data]
  (configure-files data)
  (let [proxy-context (:proxy-context data)
        cookie-file (.getCookieFile proxy-context)
        cookie-observer (new-observer proxy-context cookie-file)
        owner "__OwningControllerProcess"
        tor-process (start-tor-process proxy-context owner)
        control-port (read-control-port tor-process)
        control-socket (new Socket "127.0.0.1" control-port)
        control-connection (new TorControlConnection control-socket)]
    (wait-observer cookie-observer 3)
    (authenticate control-connection cookie-file owner)
    (start-with-timeout (merge data {:control-socket control-socket
                                     :control-connection control-connection
                                     :cookie-file cookie-file})
                        30)))


(defn- start-proxy [data*]
  (let [data (-> data*
                 start-tor)]
    data))


(defn- create-context []
  (->> (into-array java.nio.file.attribute.FileAttribute [])
       (Files/createTempDirectory "tor-folder") .toFile
       (new JavaOnionProxyContext)))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [proxy-context (create-context)
        control-connection (-> {:proxy-context proxy-context}
                               start-tor
                               :control-connection)
        hostname-file (.getHostNameFile proxy-context)
        observer (new-observer proxy-context
                               hostname-file)]
    (set-conf control-connection
              hostname-file
              remote-port local-port)
    (wait-observer observer 30)
    (slurp hostname-file)))
