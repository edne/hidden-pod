(ns hidden-pod.onion
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [me.raynes.conch :refer [with-programs let-programs]])
  (:import (java.io File
                    FileWriter
                    BufferedWriter
                    PrintWriter)
           (java.nio.file Files)
           (java.net Socket
                     ConnectException)
           (java.util.concurrent TimeUnit)
           (net.freehaven.tor.control TorControlConnection)))


(defn- run-tor [working-dir]
  ;; TODO handle missing tor in PATH asking to user and using let-programs from conch
  (let [torrc-path (str (.getAbsolutePath working-dir) "/torrc")]
    (spit torrc-path "")  ;; create an empty file
    (future (with-programs [tor]
              (tor "-f" torrc-path
                   "--ControlPort" "9051")))))


(defn- set-conf [control-connection
                 hostname-file
                 remote-port local-port]
  (.setConf control-connection
            [(str "HiddenServiceDir " (-> hostname-file
                                          .getParentFile
                                          .getAbsolutePath))
             (str "HiddenServicePort " remote-port " 127.0.0.1:" local-port)])
  (.saveConf control-connection))


(defn- try-socket [addr port]
  (try (new Socket addr port)
       (catch ConnectException e
         nil)))


(defn- create-socket [addr port]
  (loop []
    (or (try-socket addr port)
        (do (Thread/sleep 100)
            (recur)))))


(defn- connect []
  (let [control-socket (create-socket "127.0.0.1" 9051)
        control-connection (new TorControlConnection control-socket)]
    (.authenticate control-connection (make-array Byte/TYPE 0))
    control-connection))


(defn- create-directory []
  (->> (into-array java.nio.file.attribute.FileAttribute [])
       (Files/createTempDirectory "tor-folder")
       .toFile))


(defn- poll-and-read [file]
  (loop []
    (if (.exists file)
      (slurp file)
      (do (Thread/sleep 100)
          (recur)))))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [working-dir (create-directory)
        _ (run-tor working-dir)
        control-connection (connect)
        hostname-file (new File working-dir "/hiddenservice/hostname")
        private-key-file (new File working-dir "/hiddenservice/private_key")]
    (set-conf control-connection
              hostname-file
              remote-port local-port)
    [(poll-and-read hostname-file)
     (poll-and-read private-key-file)]))
