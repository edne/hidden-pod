(defproject hidden-pod "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "GNU Affero General Public License"
            :url "http://www.gnu.org/licenses/agpl-3.0.html"}
  :resource-paths ["resources/jtorctl-briar.jar"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.msopentech.thali/ThaliOnionProxyJava "0.0.2"]
                 [ring "1.5.0"]]

  :main ^:skip-aot hidden-pod.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
