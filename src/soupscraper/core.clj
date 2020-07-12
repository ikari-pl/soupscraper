(ns soupscraper.core
  (:require [clojure.core.async :as async]
            [clojure.string :as string]
            [skyscraper.core :as core :refer [defprocessor]]
            [skyscraper.context :as context]
            [taoensso.timbre :as log :refer [warnf]]
            [taoensso.timbre.appenders.core :as appenders]))

;; logic c/o tomash, cf https://github.com/UlanaXY/BowlOfSoup/pull/1
(defn fullsize-asset-url [url]
  (when url
    (if-let [[_ a b c ext] (re-find #"^https://asset.soup.io/asset/(\d+)/([0-9a-f]+)_([0-9a-f]+)_[0-9]+\.(.*)$" url)]
      (format "http://asset.soup.io/asset/%s/%s_%s.%s" a b c ext)
      (string/replace url "https://" "http://"))))

(defn asset-info [type url]
  (let [url (fullsize-asset-url url)
        [_ prefix asset-id ext] (re-find #"^http://asset.soup.io/asset/(\d+)/([0-9a-f_]+)\.(.*)$" url)]
    {:type type
     :prefix prefix
     :asset-id asset-id
     :ext ext
     :url url
     :processor :asset}))

(defn parse-post [div]
  (if-let [content (reaver/select div ".content")]
    (let [imagebox (reaver/select content "a.lightbox")
          imagedirect (reaver/select content ".imagecontainer > img")
          body (reaver/select content ".body")
          h3 (reaver/select content "content > h3")
          video (reaver/select content ".embed video")
          id (subs (reaver/attr div :id) 4)]
      (merge {:id id}
             (cond
               video (asset-info :video (reaver/attr video :src))
               imagebox (asset-info :image (reaver/attr imagebox :href))
               imagedirect (asset-info :image (reaver/attr imagedirect :src))
               body {:type :text}
               :otherwise nil)
             (when h3 {:title (reaver/text h3)})
             (when body {:content (.html body)})))
    (do
      (warnf "[parse-post] no content: %s", div)
      {:type :unable-to-parse, :post div})))

(def months ["January" "February" "March" "April" "May" "June" "July" "August" "September" "October"
             "November" "December"])

(defn yyyymmdd [h2]
  (format "%s-%02d-%s"
          (reaver/text (reaver/select h2 ".y"))
          (inc (.indexOf months (reaver/text (reaver/select h2 ".m"))))
          (reaver/text (reaver/select h2 ".d"))))

(def as-far-as (atom nil))

(defprocessor :soup
  :cache-template "soup/:soup/list/:since"
  :process-fn (fn [document {:keys [earliest pages-only] :as context}]
                (let [dates (map yyyymmdd (reaver/select document "h2.date"))
                      moar (-> (reaver/select document "#load_more a") (reaver/attr :href))]
                  (reset! as-far-as (last dates))
                  (concat
                   (when (and moar (or (not earliest) (>= (compare (last dates) earliest) 0)))
                     (let [since (second (re-find #"/since/(\d+)" moar))]
                       [{:processor :soup, :since since, :url moar}]))
                   (when-not pages-only
                     (map parse-post (reaver/select document ".post")))))))

(defprocessor :asset
  :cache-template "soup/:soup/assets/:prefix/:asset-id"
  :parse-fn (fn [headers body] body)
  :process-fn (fn [document context]
                {:downloaded true}))

(defn download-error-handler
  [error options context]
  (let [{:keys [status]} (ex-data error)
        retry? (or (nil? status) (>= status 500) (= status 429))]
    (cond
      (= status 404)
      (do
        (warnf "[download] %s 404'd, dumping in empty file" (:url context))
        (core/respond-with {:headers {"content-type" "text/plain"}
                            :body (byte-array 0)}
                           options context))

      retry?
      (do
        (if (= status 429)
          (do
            (warnf "[download] Unexpected error %s, retrying after a nap" error)
            (Thread/sleep 5000))
          (warnf "[download] Unexpected error %s, retrying" error))
        [context])

      :otherwise
      (do
        (warnf "[download] Unexpected error %s, giving up" error)
        (core/signal-error error context)))))

(defn seed [{:keys [soup earliest pages-only]}]
  [{:url (format "https://%s.soup.io" soup),
    :soup soup,
    :since "latest",
    :processor :soup,
    :earliest earliest,
    :pages-only pages-only}])

(defn scrape-args [opts]
  [(seed opts)
   :parse-fn               core/parse-reaver
   :parallelism            1
   ;; :max-connections        1
   :html-cache             true
   :download-error-handler download-error-handler
   :sleep                  (:sleep opts)
   :http-options           {:redirect-strategy  :lax
                            :as                 :byte-array
                            :connection-timeout 60000
                            :socket-timeout     60000}
   :item-chan              (:item-chan opts)])

(defn scrape [opts]
  (apply core/scrape (scrape-args opts)))

(defn scrape! [opts]
  (apply core/scrape! (scrape-args opts)))

(def cli-options
  [["-e" "--earliest" "Skip posts older than YYYY-MM-DD"]])

(log/set-level! :info)
(log/merge-config! {:appenders {:println {:enabled? false}
                                :spit (appenders/spit-appender {:fname "log/skyscraper.log"})}})

(defn main []
  (let [item-ch (async/chan)
        opts {:soup "tomash"}]
    (println "Downloading infiniscroll pages...")
    (async/thread
      (loop [i 1]
        (when-let [items (async/<!! item-ch)]
          (let [item (first items)]
            (if (and item (= (::core/stage item) `core/split-handler))
              (do
                (printf "%s pages going back as far as %s\n" i @as-far-as)
                (recur (inc i)))
              (recur i))))))
    (scrape! (assoc opts :sleep 1000 :item-chan item-ch :pages-only true))))
