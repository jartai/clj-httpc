(ns clj-httpc.client
  "Batteries-included HTTP client."
  (:refer-clojure :exclude (get))
  (:use
    [clojure.contrib.def])
  (:require
    [clj-httpc.core :as core]
    [clj-httpc.content :as content]
    [clj-httpc.util :as util]
    [clojure.contrib.string :as str])
  (:import
    [java.net URL]
    [java.nio.charset Charset]
    [com.google.gdata.util ContentType]))

(defvar- mget clojure.core/get)

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn if-pos [v]
  (if (and v (pos? v)) v))

(defn parse-url [url]
  (let [url-parsed (URL. url)]
    {:scheme (.getProtocol url-parsed)
     :server-name (.getHost url-parsed)
     :server-port (if-pos (.getPort url-parsed))
     :uri (.getPath url-parsed)
     :query-string (.getQuery url-parsed)}))

(defn follow-redirect [client req resp]
  (let [url (get-in resp [:headers "location"])]
    (client (merge req (parse-url url)))))

(defn wrap-redirects [client]
  (fn [{:keys [request-method] :as req}]
    (let [{:keys [status] :as resp} (client req)]
      (cond
       (and (#{301 302 307} status) (#{:get :head} request-method))
         (follow-redirect client req resp)
       (and (= 303 status) (= :head request-method))
         (follow-redirect client (assoc req :request-method :get) resp)
       :else
         resp))))

(defn wrap-decompression [client]
  (fn [req]
    (if (get-in req [:headers "Accept-Encoding"])
      (client req)
      (let [req-c (update req :headers assoc "Accept-Encoding" "gzip, deflate")
            resp-c (client req)]
        (case (get-in resp-c [:headers "Content-Encoding"])
          "gzip"
            (update resp-c :body util/gunzip)
          "deflate"
            (update resp-c :body util/inflate)
          resp-c)))))

(defn wrap-output-coercion [client]
  (fn [{:keys [as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (cond
        (or (nil? body) (= :byte-array as))
          resp
        (nil? as)
          (let [content-type (content/create-content-type
                               (get-in resp '(:headers "content-type")))
                charset (content/get-charset
                          content-type
                          (get-in req '(:http-params content/default-charset)))]
            (assoc resp :body (String. #^"[B" body charset)))))))

(defn wrap-input-coercion [client]
  (fn [{:keys [body] :as req}]
    (if (string? body)
      (client (-> req (assoc :body (util/utf8-bytes body)
                             :character-encoding "UTF-8")))
      (client req))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-content-type [client]
  (fn [{:keys [content-type] :as req}]
    (if content-type
      (client (-> req (assoc :content-type
                        (content-type-value content-type))))
      (client req))))

(defn wrap-accept [client]
  (fn [{:keys [accept] :as req}]
    (if accept
      (client (-> req (dissoc :accept)
                      (assoc-in [:headers "Accept"]
                        (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding [client]
  (fn [{:keys [accept-encoding] :as req}]
    (if accept-encoding
      (client (-> req (dissoc :accept-encoding)
                      (assoc-in [:headers "Accept-Encoding"]
                        (accept-encoding-value accept-encoding))))
      (client req))))

(defn generate-query-string [params]
  (str/join "&"
    (map (fn [[k v]] (str (util/url-encode (name k)) "="
                          (util/url-encode (str v))))
         params)))

(defn wrap-query-params [client]
  (fn [{:keys [query-params] :as req}]
    (if query-params
      (client (-> req (dissoc :query-params)
                      (assoc :query-string
                             (generate-query-string query-params))))
      (client req))))

(defn basic-auth-value [user password]
  (str "Basic "
       (util/base64-encode (util/utf8-bytes (str user ":" password)))))

(defn wrap-basic-auth [client]
  (fn [req]
    (if-let [[user password] (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                      (assoc-in [:headers "Authorization"]
                        (basic-auth-value user password))))
      (client req))))

(defn wrap-method [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                      (assoc :request-method m)))
      (client req))))

(defn wrap-url [client]
  (fn [req]
    (if-let [url (:url req)]
      (client (-> req (dissoc :url) (merge (parse-url url))))
      (client req))))

(def #^{:doc
  "Executes the HTTP request corresponding to the given map and returns the
   response map for corresponding to the resulting HTTP response.

   In addition to the standard Ring request keys, the following keys are also
   recognized:
   * :url
   * :method
   * :query-params
   * :basic-auth
   * :content-type
   * :accept
   * :accept-encoding
   * :as

  The following additional behaviors over also automatically enabled:
   * Exceptions are thrown for status codes other than 200-207, 300-303, or 307
   * Gzip and deflate responses are accepted and decompressed
   * Input and output bodies are coerced as required and indicated by the :as
     option."}
  request
  (-> #'core/request
    wrap-redirects
    wrap-decompression
    wrap-input-coercion
    wrap-output-coercion
    wrap-query-params
    wrap-basic-auth
    wrap-accept
    wrap-accept-encoding
    wrap-content-type
    wrap-method
    wrap-url))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (try
    (request (merge req {:method :get :url url}))
    (catch Exception e (util/create-error-response url e))))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (try
    (request (merge req {:method :head :url url}))
    (catch Exception e (util/create-error-response url e))))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (try
    (request (merge req {:method :post :url url}))
    (catch Exception e (util/create-error-response url e))))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (try
    (request (merge req {:method :put :url url}))
    (catch Exception e (util/create-error-response url e))))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (try
    (request (merge req {:method :delete :url url}))
    (catch Exception e (util/create-error-response url e))))
