(ns reddit-nntp.main
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.zip :as zip]
            [clojure.string :as string]))

(defn extract-posts [posts-repr]
  (into #{}
  (map #(get % "data")
       (get-in posts-repr ["data" "children"]))))

(defn grab-posts-from-reddit []
  (let [opts {:client-params { "http.useragent" "reddit-nntp" }}
        resp (client/get "http://www.reddit.com/r/crypto/.json" opts)]
    (get resp :body)))

(defn grab-comments-from-reddit [post]
  [{ "id" "foo"
    "body" "This."
    "children" [ { "id" "foo.bar"
                   "body" "Worst comment"
                   "children" [] }]}])

(defn augment-post-with-comments [comments, post]
  (assoc post "children" comments))

(defn post-zip [post-with-comments]
  (zip/zipper (constantly true)
              #(seq (% "children"))
              #(augment-post-with-comments %2 %1)
              post-with-comments))

(defn zip-walk [loc f]
  (if (zip/end? loc)
    (zip/root loc)
    (zip-walk (zip/next (f loc)) f)))

(defn zip-nodes [loc]
  (->> loc
       (iterate zip/next)
       (take-while (complement zip/end?))
       (map zip/node)))

(defn fill-in-references-from-parent-ids [post-with-comments]
  (zip-walk (post-zip post-with-comments)
            (fn [comment]
              (let [references (map #(% "id") (reverse (zip/path comment)))
                    add-references #(assoc % "references" references)]
                (#(zip/edit % add-references) comment)))))

(defn flatten-post-and-comments [post-and-comments]
  (zip-nodes (post-zip post-and-comments)))

(defn useful-keys [post-and-comments]
  (select-keys post-and-comments ["id" "body" "title" "references"]))

(defn render-nntp [post]
  (string/join "\n"
               [(string/join ["Subject: " (post "title")])
                (string/join ["References: " (string/join " " (map #(string/join ["<" % "@reddit-nntp>"]) (post "references")))])
                (string/join ["Message-ID: " (string/join ["<" (post "id") "@reddit-nntp>"])])
                ""]))

(defn reddit-nntp [grab-posts-from-reddit grab-comments-from-reddit]
  (->> (grab-posts-from-reddit)
       json/read-str
       extract-posts
       (map
        (fn [post]
          (->> post
               (#(augment-post-with-comments (grab-comments-from-reddit %) %))
               fill-in-references-from-parent-ids
               flatten-post-and-comments
               (map useful-keys))))))

(defn -main
  [& args]
  (println (reddit-nntp grab-posts-from-reddit grab-comments-from-reddit)))
