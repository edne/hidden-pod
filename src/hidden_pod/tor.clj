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


(defn- enable-network [control-connection]
  (.setConf control-connection "DisableNetwork" "0"))


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


(defn- stop-proxy [control-socket]
  (if control-socket
    (.close control-socket)))


(defn- read-control-port [tor-process]
  (let [input-stream (.getInputStream tor-process)
        scanner (new Scanner input-stream)]
    (->> #(let [line (.nextLine scanner)]
            (println line)
            line)
         repeatedly
         (map #(re-find #"listening on port (\d+)\." %))
         (filter identity)
         first  ;; the first non-empy list of matches ["1234." "1234"]
         last   ;; the last match in the list
         Integer/parseInt)))


(defn- get-pid []
  (-> (java.lang.management.ManagementFactory/getRuntimeMXBean)
      .getName
      (string/split #"@")
      first))


(defn- start-tor-process [ctx owner]
  (let [tor-path (-> :tor-exe-file ctx .getAbsolutePath)
        config-path (-> :torrc-file ctx .getAbsolutePath)
        working-dir (-> :working-dir ctx .getAbsolutePath)
        pid (get-pid)
        cmd [tor-path "-f" config-path owner pid]
        process-builder (new ProcessBuilder cmd)
        environment (.environment process-builder)]
    (.put environment "HOME" working-dir)
    (if (linux?)
      (.put environment "LD_LIBRARY_PATH" working-dir))
    (.start process-builder)))


(defn- install-files [ctx]
  (.installFiles (:proxy-context ctx))
  (if-not (-> :tor-exe-file ctx (.setExecutable true))
    (throw (Exception. "Could not make Tor executable"))))


(defn- configure-files [ctx]
  (let [torrc-file (:torrc-file ctx)
        cookie-file    (-> :cookie-file ctx .getAbsolutePath)
        data-directory (-> :working-dir ctx .getAbsolutePath)
        file-writer (new FileWriter torrc-file true)
        buffered-writer (new BufferedWriter file-writer)
        print-writer (new PrintWriter buffered-writer)]
    (.println print-writer (str "CookieAuthFile " cookie-file))
    (.println print-writer (str "DataDirectory " data-directory))
    (.close print-writer)))


(defn- authenticate [control-connection cookie-file owner]
  (doto control-connection
    (.authenticate (FileUtilities/read cookie-file))  ;; TODO: use clj read
    (.takeOwnership)
    (.resetConf [owner])
    (.setEventHandler (new OnionProxyManagerEventHandler))
    (.setEvents ["CIRC" "ORCONN" "NOTICE" "WARN" "ERR"])))


(defn- start-with-timeout [ctx timeout-secs]
  {:pre [(> timeout-secs 0)]}
  (let [control-connection (:control-connection ctx)
        control-socket (:control-socket ctx)]
    (enable-network control-connection)
    (if-not (->> #(or (bootstrapped? control-connection)
                      (Thread/sleep 1000))
                 (take timeout-secs)
                 (filter identity)
                 #(if % (first %)))
      (do (stop-proxy control-socket)
          (.deleteAllFilesButHiddenServices (:proxy-context ctx))
          (throw (Exception. "Failed to run Tor")))
      ctx)))


(defn- start-tor [ctx]
  (install-files ctx)
  (configure-files ctx)
  (let [cookie-file (:cookie-file ctx)
        cookie-observer (new-observer cookie-file)
        owner "__OwningControllerProcess"
        tor-process (start-tor-process ctx owner)
        control-port (read-control-port tor-process)
        control-socket (new Socket "127.0.0.1" control-port)
        control-connection (new TorControlConnection control-socket)]
    (wait-observer cookie-observer 3)
    (authenticate control-connection cookie-file owner)
    (start-with-timeout (merge ctx {:control-socket control-socket
                                     :control-connection control-connection
                                     :cookie-file cookie-file})
                        30)))


(defn- get-tor-exe-filename []
  (cond (linux?) "tor"
        (windows?) "tor.exe"
        (mac?) "tor.real"
        :else (throw (Exception. "Unsupported OS"))))


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
     :tor-exe-file (new File working-dir
                        (get-tor-exe-filename))
     :cookie-file (new File working-dir ".tor/control_auth_cookie")
     :hostname-file (new File working-dir
                         (str "/" hiddenservice-dir-name "/hostname"))}))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [ctx (create-context)
        control-connection (-> ctx
                               start-tor
                               :control-connection)
        hostname-file (:hostname-file ctx)
        observer (new-observer hostname-file)]
    (set-conf control-connection
              hostname-file
              remote-port local-port)
    (wait-observer observer 30)
    (slurp hostname-file)))
