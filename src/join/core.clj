(ns join.core
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.logging :refer [info]]
   [jackdaw.serdes.edn :as jse]
   [jackdaw.serdes.resolver :as resolver]
   [jackdaw.streams :as j])
  (:gen-class))

;; does not seem to be fetching schema from registry
(def schema-registry-url "http://localhost:8081")

(def serde-resolver
  (partial resolver/serde-resolver :schema-registry-url schema-registry-url))

(def users-schema
  "{\"type\":\"record\",\"name\":\"value_users\",\"namespace\":\"playground\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"first_name\",\"type\":\"string\"}]}")

(def addresses-schema
  "{\"type\":\"record\",\"name\":\"value_addresses\",\"fields\":[{\"name\":\"user_id\",\"type\":\"string\"},{\"name\":\"address\",\"type\":\"string\"}]}")

(def merged-schema
  "{\"type\":\"record\",\"name\":\"value_merged\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"first_name\",\"type\":\"string\"},{\"name\":\"address\",\"type\":\"string\"}]}")

(defn avro-topic
  [topic-name schema]
  {:topic-name (name topic-name)
   :partition-count 12
   :replication-factor 3
   :key-serde   ((serde-resolver) {:serde-keyword :jackdaw.serdes/string-serde})
   :value-serde ((serde-resolver) {:serde-keyword :jackdaw.serdes.avro.confluent/serde
                                   :schema schema
                                   :key? false})})
(def app-config
  "Returns the application config."
  {"application.id"            "join"
   "bootstrap.servers"         "localhost:9092"
   "default.key.serde"         "jackdaw.serdes.EdnSerde"
   "default.value.serde"       "jackdaw.serdes.EdnSerde"
   "cache.max.bytes.buffering" "0"})

(defn topology-builder
  [builder]
  (let [user-topic (avro-topic :users users-schema)
        address-topic (avro-topic :addresses addresses-schema)
        merged-topic (avro-topic :merged merged-schema)
        global-user (j/global-ktable builder user-topic)
        stream-address (j/kstream builder address-topic)]
    (-> stream-address
      ;; how can the key-value-mapper extract a key from payload?
      ;; (:user-id v) does not work.
        (j/join-global global-user (fn one [[k v]] k)
                       (fn two [address user]
                         (assoc user :address (:address address))))
        (j/map-values identity)
        (j/to merged-topic)))

  builder)

(defn start-app
  [& _]
  (let [builder  (j/streams-builder)
        topology (topology-builder builder)
        app (j/kafka-streams topology app-config)]
    (j/start app)
    (println "consumer....")
    app))

(defn -main
  [& args]
  (start-app))