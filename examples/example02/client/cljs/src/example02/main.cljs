(ns example02.main
  (:require [clojure.string :as string]
            [cljs.nodejs :as nodejs]
            [cljs.tools.cli :refer [parse-opts]]
            [example02.connection :as conn]
            [example02.api :as api]
            [promesa.core :as p :include-macros true]))

(nodejs/enable-util-print!)

(def readyaml (nodejs/require "read-yaml"))

(def _commands
  [["install"
    {:fn api/install}]
   ["instantiate"
    {:fn api/instantiate
     :default-args #js {:partyA #js {:entity "A"
                                     :value 100}
                        :partyB #js {:entity "B"
                                     :value 200}}}]
   ["make-payment"
    {:fn api/make-payment
     :default-args #js {:partySrc "A"
                        :partyDst "B"
                        :amount 10}}]
   ["delete-account"
    {:fn api/delete-account
     :default-args #js {:id "A"}}]
   ["check-balance"
    {:fn api/check-balance
     :default-args #js {:id "A"}}]])

(def commands (into {} _commands))
(defn print-commands [] (->> commands keys vec print-str))

(def options
  [[nil "--config CONFIG" "path/to/client.config"
    :default "client.config"]
   ["-p" "--path ID" "path/to/chaincode.car ('install' only)"]
   ["-i" "--id ID" "ChaincodeID"
    :default "mycc"]
   ["-v" "--version VERSION" "Chaincode version"
    :default "1"]
   [nil "--channel ID" "Channel ID"
    :default "mychannel"]
   ["-c" "--command CMD" (str "One of " (print-commands))
    :default "check-balance"
    :validate [#(contains? commands %) (str "Supported commands: " (print-commands))]]
   ["-a" "--args ARGS" "JSON formatted arguments to submit"]
   ["-h" "--help"]])

(defn exit [status msg & rest]
  (do
    (apply println msg rest)
    status))

(defn prep-usage [msg] (->> msg flatten (string/join \newline)))

(defn usage [options-summary]
  (prep-usage ["Usage: example02 [options]"
               ""
               "Options Summary:"
               options-summary
               ""]))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args options)
        {:keys [config command args]} options]
    (cond

      (:help options)
      (exit 0 (usage summary))

      (not= errors nil)
      (exit -1 "Error: " (string/join errors))

      :else
      (let [desc (commands command)
            _args (if (empty? arguments)
                    (:default-args desc)
                    (->> arguments first (.parse js/JSON)))
            _config (-> (.sync readyaml config)
                        (js->clj :keywordize-keys true))]

        (p/alet [context (p/await (conn/connect! (assoc options :config _config)))
                 params (-> options
                            (assoc :args _args)
                            (merge context))]

                (println (str "Running " command "(" (.stringify js/JSON _args) ")"))

                ;; Run the subcommand funtion
                (-> ((:fn desc) params)
                    (p/catch #(println "Error:" %))
                    (p/then #(conn/disconnect! context))))))))

(set! *main-cli-fn* -main)
