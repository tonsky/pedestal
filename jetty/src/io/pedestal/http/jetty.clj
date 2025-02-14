; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.jetty
  (:require [io.pedestal.http.jetty.container]
            [clojure.string :as string])
  (:import (org.eclipse.jetty.server Server ServerConnector
                                     Request
                                     HttpConfiguration
                                     SecureRequestCustomizer
                                     ConnectionFactory
                                     HttpConnectionFactory
                                     SslConnectionFactory
                                     NegotiatingServerConnectionFactory)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.alpn ALPN)
           (org.eclipse.jetty.alpn.server ALPNServerConnectionFactory)
           (org.eclipse.jetty.http2 HTTP2Cipher)
           (org.eclipse.jetty.http2.server HTTP2ServerConnectionFactory
                                           HTTP2CServerConnectionFactory)
           (javax.servlet Servlet)
           (java.security KeyStore)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))

;; Implement any container specific optimizations from Pedestal's container protocols


;; The approach here is based on code from ring.adapter.jetty

;; The Jetty9 updates are based on http://www.eclipse.org/jetty/documentation/current/embedding-jetty.html
;; and http://download.eclipse.org/jetty/stable-9/xref/org/eclipse/jetty/embedded/ManyConnectors.html

(defn- ^SslContextFactory ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [options]
  (or (:ssl-context-factory options)
      (let [{:keys [^KeyStore keystore key-password
                    ^KeyStore truststore
                    ^String trust-password
                    ^String security-provider
                    client-auth]} options
            ^SslContextFactory context (SslContextFactory.)]
        (when (every? nil? [keystore key-password truststore trust-password client-auth])
          (throw (IllegalArgumentException. "You are attempting to use SSL, but you did not supply any certificate management (KeyStore/TrustStore/etc.)")))
        (if (string? keystore)
          (.setKeyStorePath context keystore)
          (.setKeyStore context keystore))
        (.setKeyStorePassword context key-password)
        (when truststore
          (if (string? truststore)
            (.setTrustStorePath context truststore)
            (.setTrustStore context truststore)))
        (when trust-password
          (.setTrustStorePassword context trust-password))
        (when security-provider
          (.setProvider context security-provider))
        (case client-auth
          :need (.setNeedClientAuth context true)
          :want (.setWantClientAuth context true)
          nil)
        (.setCipherComparator context HTTP2Cipher/COMPARATOR)
        (.setUseCipherSuitesOrder context true)
        context)))

(defn- ssl-conn-factory
  "Create an SslConnectionFactory instance."
  [options]
  (let [{:keys [alpn container-options]} options]
    (SslConnectionFactory.
      (ssl-context-factory container-options)
      (if alpn
        (.getProtocol ^ALPNServerConnectionFactory alpn)
        "http/1.1"))))

(defn- ssl-connector
  "Creates a SslSelectChannelConnector instance."
  ([^Server server options]
   (ssl-connector server options nil))
  ([^Server server options connection-factories]
   (let [{host :host
          alpn :alpn
          {:keys [ssl-port reuse-addr?]
           :or {ssl-port 443
                reuse-addr? true}
           :as container-options} :container-options} options
         ssl-factories (when alpn
                         (into-array ConnectionFactory
                                     (remove nil?
                                             (into [(SslConnectionFactory.
                                                      (ssl-context-factory container-options)
                                                      (.getProtocol ^ALPNServerConnectionFactory alpn))]
                                                   connection-factories))))
         connector (if ssl-factories
                     (ServerConnector. server ssl-factories)
                     (ServerConnector. server (ssl-context-factory container-options)))]
     (doto connector
       (.setReuseAddress reuse-addr?)
       (.setPort ssl-port)
       (.setHost host)))))

(defn- http-configuration
  "Provides an HttpConfiguration that can be consumed by connection factories.
  The `:io.pedestal.http.jetty/http-configuration` option can be used to specify
  your own HttpConfiguration instance."
  [options]
  (if-let [http-conf-override ^HttpConfiguration (::http-configuration options)]
    http-conf-override
    (let [{:keys [ssl? ssl-port h2?]} options
          http-conf                   ^HttpConfiguration (HttpConfiguration.)]
      (when (or ssl? ssl-port h2?)
        (.setSecurePort http-conf ssl-port)
        (.setSecureScheme http-conf "https"))
      (doto http-conf
        (.setSendDateHeader true)
        (.setSendXPoweredBy false)
        (.setSendServerVersion false)
        (.addCustomizer (SecureRequestCustomizer.))))))

(defn- needed-pool-size
  "Jetty 9 calculates a needed number of threads per acceptors and selectors,
  based on the available cores on a given machine.
  This performs that calculation.  This is used to ensure an appropriate
  number of threads are created for the server."
  ([]
   (let [cores (.availableProcessors (Runtime/getRuntime))
         ;; The Jetty docs claim acceptors is 1.5 the number of cores available,
         ;; but the code says:  1 + cores / 16 - https://github.com/eclipse/jetty.project/blob/master/jetty-server/src/main/java/org/eclipse/jetty/server/AbstractConnector.java#L192
        acceptors (int (* 1.5 cores)) ;(inc (/ cores 16))
        selectors (/ (inc cores) 2.0)] ; (cores + 1) / 2 - https://github.com/eclipse/jetty.project/blob/master/jetty-io/src/main/java/org/eclipse/jetty/io/SelectorManager.java#L73
   ;; 2 connectors - HTTP & HTTPS
   (needed-pool-size 2 acceptors selectors)))
  ([connectors acceptors selectors]
   (* (Math/round ^Double (+ acceptors selectors)) connectors)))

;; Consider allowing users to set the number of acceptors (ideal at 1 per core) and/or selectors
(defn- create-server
  "Construct a Jetty Server instance."
  [servlet options]
  (let [{host :host
         port :port
         {:keys [ssl? ssl-port
                 h2? h2c? connection-factory-fns
                 context-configurator context-path configurator max-threads daemon? reuse-addr?]
          :or {configurator identity
               context-path "/"
               max-threads (max 50 (needed-pool-size))
               h2c? true
               reuse-addr? true}} :container-options} options
        thread-pool (QueuedThreadPool. ^Integer max-threads)
        server (Server. thread-pool)
        _ (when-not (string/starts-with? context-path "/")
            (throw (IllegalArgumentException. "context-path must begin with a '/'")))
        _ (when (and h2? (not ssl-port))
            (throw (IllegalArgumentException. "SSL must be enabled to use HTTP/2. Please set an ssl port and appropriate *store setups")))
        _ (when (and (nil? port) (not (or ssl? ssl-port h2?)))
            (throw (IllegalArgumentException. "HTTP was turned off with a `nil` port value, but no SSL config was supplied.  Please set an HTTP port or configure SSL")))
        _ (when (and (nil? port) (true? h2c?))
            (throw (IllegalArgumentException. "HTTP was turned off with a `nil` port value, but you attempted to turn on HTTP2-Cleartext.  Please set an HTTP port or set `h2c?` to false in your service config")))
        http-conf (http-configuration (:container-options options))
        http (HttpConnectionFactory. http-conf)
        http2c (when h2c? (HTTP2CServerConnectionFactory. http-conf))
        http2 (when h2? (HTTP2ServerConnectionFactory. http-conf))
        alpn (when h2?
               ;(set! (. ALPN debug) true)
               ;(NegotiatingServerConnectionFactory/checkProtocolNegotiationAvailable) ;; This only looks at Java8 bootclasspath stuff, and is no longer valid in newer Jetty versions
               (doto (ALPNServerConnectionFactory. "h2,h2-17,h2-14,http/1.1")
                 (.setDefaultProtocol "http/1.1")))
        ssl (when (or ssl? ssl-port h2?)
              (ssl-conn-factory (assoc options :alpn alpn)))
        http-connector (when port
                         (doto (ServerConnector. server (into-array ConnectionFactory
                                                                    (remove nil? [http http2c])))
                           (.setReuseAddress reuse-addr?)
                           (.setPort port)
                           (.setHost host)))
        ssl-connector (when ssl
                        (doto (ServerConnector. server
                                                (into-array ConnectionFactory
                                                            (remove nil?
                                                                    (into [ssl alpn http2 (HttpConnectionFactory. http-conf)]
                                                                          (map (fn [ffn] (ffn options http-conf)) connection-factory-fns)))))
                          (.setReuseAddress reuse-addr?)
                          (.setPort ssl-port)
                          (.setHost host)))
        context (doto (ServletContextHandler. server context-path)
                  (.addServlet (ServletHolder. ^javax.servlet.Servlet servlet) "/*"))]
    (when daemon?
      (.setDaemon thread-pool true))
    (when http-connector
      (.addConnector server http-connector))
    (when ssl-connector
      (.addConnector server ssl-connector))
    (when context-configurator
      (context-configurator context))
    (configurator server)))

(defn start
  [^Server server
   {:keys [join?] :or {join? true} :as options}]
  (.start server)
  (when join? (.join server))
  server)

(defn stop [^Server server]
  (.stop server)
  server)

(defn server
  ([service-map] (server service-map {}))
  ([service-map options]
   (let [server (create-server (:io.pedestal.http/servlet service-map) options)]
     {:server   server
      :start-fn #(start server options)
      :stop-fn  #(stop server)})))


  ;; :port         - the http/h2c port to listen on (defaults to 80);
  ;;                     If nil and SSL Config is set, HTTP is disabled.
  ;; :host         - the hostname to listen on
  ;; :join?        - blocks the thread until server ends (defaults to true)

  ;; -- Container Options --
  ;; :daemon?      - use daemon threads (defaults to false)
  ;; :max-threads  - the maximum number of threads to use (default 50)
  ;; :reuse-addr?  - reuse the socket address (defaults to true)
  ;; :configurator - a function called with the Jetty Server instance
  ;; :context-configurator - a function called with the Jetty ServletContextHandler
  ;; :ssl?         - allow connections over HTTPS
  ;; :ssl-port     - the SSL port to listen on (defaults to 443, implies :ssl?)
  ;; :h2?          - enable http2 protocol on secure socket port
  ;; :h2c?         - enable http2 clear text on plain socket port
  ;; :connection-factory-fns - a vector of functions that take the options map and HttpConfiguration
  ;;                           and return a ConnectionFactory obj (applied to SSL connection)
  ;; :ssl-context-factor - a Jetty SslContectFactory to use in place on Pedestal building one.
  ;;                       If passed in, all other SSL related options are ignored.
  ;; :keystore     - the keystore to use for SSL connections - may be the object or a string path
  ;; :key-password - the password to the keystore
  ;; :truststore   - a truststore to use for SSL connections - may be the object or a string path
  ;; :trust-password - the password to the truststore
  ;; :client-auth  - SSL client certificate authenticate, may be set to :need,
  ;;                 :want or :none (defaults to :none)"
