(ns hidden-pod.server
  (:use [ring.adapter.jetty]
        [ring.middleware.file]))


(defn handler [request]
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body (str request)})


(defn serve-folder
  "Serve static content on a given port"
  [path port]
  (run-jetty (wrap-file handler path)
             {:port port}))
