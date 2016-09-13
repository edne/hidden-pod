(ns hidden-pod.onion
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [me.raynes.conch :refer [with-programs let-programs]]
            [juxt.dirwatch :refer (watch-dir)])
  (:import (java.io File
                    FileWriter
                    BufferedWriter
                    PrintWriter)
           (java.nio.file Files)
           (java.net Socket)
           (java.util.concurrent TimeUnit)
           (net.freehaven.tor.control TorControlConnection)))


(defn- run-tor [working-dir]
  ;; TODO handle missing tor in PATH asking to user and using let-programs
  (let [torrc-path (str (.getAbsolutePath working-dir) "/torrc")]
    (spit torrc-path "")
    (future (with-programs [tor]
              (tor "-f" torrc-path
                   "--ControlPort" "9051"))))
  (Thread/sleep 1000))


(defn- set-conf [control-connection
                 hostname-file
                 remote-port local-port]
  (.setConf control-connection
            [(str "HiddenServiceDir " (-> hostname-file
                                          .getParentFile
                                          .getAbsolutePath))
             (str "HiddenServicePort " remote-port " 127.0.0.1:" local-port)])
  (.saveConf control-connection))


(defn- wait-bootstrap [control-connection control-socket timeout-secs]
  (if-not (->> #(or (-> control-connection
                        (.getinfo "status/bootstrap-phase")
                        (.contains "progress=100"))
                    (Thread/sleep 100))
               repeatedly
               (take (* 10 timeout-secs))
               (filter identity)
               #(if % (first %)))
    (do (.close control-socket)
        (throw (Exception. "Wait time to bootstrapping Tor expired")))))


(defn- connect []
  (let [control-socket (new Socket "127.0.0.1" 9051)
        control-connection (new TorControlConnection control-socket)]
    (.authenticate control-connection (make-array Byte/TYPE 0))
    (wait-bootstrap control-connection control-socket 30)
    control-connection))


(defn- create-directory []
  (->> (into-array java.nio.file.attribute.FileAttribute [])
       (Files/createTempDirectory "tor-folder")
       .toFile))


(defn- create-hostname-file [working-dir]
  (new File working-dir "/hiddenservice/hostname"))


(defn publish-hidden-service
  "Create an hidden service forwarding a port, return the address"
  [local-port remote-port]
  (let [working-dir (create-directory)
        _ (run-tor working-dir)
        control-connection (connect)
        hostname-file (create-hostname-file working-dir)]
    (watch-dir #(let [file-name (-> % :file .getName)
                      full-path (-> % :file .getAbsolutePath)]
                  (if (= file-name "hostname")
                    (println "Serving at: " (slurp full-path))))
               working-dir)
    (set-conf control-connection
              hostname-file
              remote-port local-port)
    ; TODO: wait watcher or make it fully asynchronous
    ))
