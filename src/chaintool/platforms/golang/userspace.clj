;; Copyright London Stock Exchange Group 2016 All Rights Reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;                  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns chaintool.platforms.golang.userspace
  (:require [chaintool.car.ls :refer :all]
            [chaintool.car.write :as car]
            [chaintool.config.util :as config]
            [chaintool.platforms.api :as platforms.api]
            [chaintool.platforms.golang.core :refer :all]
            [chaintool.util :as util]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.file-utils :as fileutils])
  (:refer-clojure :exclude [compile]))

;;-----------------------------------------------------------------
;; go-env - returns a map representing the golang environment
;;
;; "go env" returns a new-line separated list of NAME="VALUE" pairs
;; Therefore, we need to parse the new-line and "=" separators and
;; strip off the quotes from the VALUE.
;;-----------------------------------------------------------------
(defn- go-env [path]
  (->> (go-cmd {:path path :silent true} "env")
       string/split-lines
       (map #(string/split % #"="))
       (map (fn [[k v]] [k (string/replace v #"\"" "")]))
       (into {})))

;;-----------------------------------------------------------------
;; get-go-env - returns the value of one specific key from the
;; golang environment
;;-----------------------------------------------------------------
(defn- get-go-env [path key]
  (-> (go-env path)
      (get key)))

;;-----------------------------------------------------------------
;; convertfile - takes a basepath (string) and file (handle) and
;; returns a tuple containing {handle path}
;;
;; handle: the raw io/file handle as passed in via 'file'
;; path: the relative path of the file w.r.t. the root of the
;; archive
;;-----------------------------------------------------------------
(defn- convert-file [basepath file]
  (let [basepathlen (->> basepath io/file .getAbsolutePath count inc)
        fqpath (.getAbsolutePath file)
        path (subs fqpath basepathlen)]
    {:handle file :path path}))

;;-----------------------------------------------------------------
;; get-project-files - takes a basepath string, and a vector of spec
;; strings, and builds a list of {:handle :path} structures. Spec
;; entires can be either an explicit file or a directory, both of
;; which are implicitly relative to basepath.  For example:
;;
;;       basepath: "/path/to/foo"
;;       spec: ["bar" "baz.conf"]
;;
;; would import ("/path/to/foo/bar" "/path/to/foo/baz.conf").  If
;; any spec is a directory it will be recursively expanded.
;;
;; The resulting structure will consist of an io/file under
;; :handle, and a :path with the basepath removed
;;-----------------------------------------------------------------
(defn- get-project-files [basepath specs]
  (->> specs
       (map #(->> (io/file basepath %)
                  file-seq
                  (filter fs/file?)))
       flatten
       (map #(convert-file basepath %))))

;;-----------------------------------------------------------------
;; find-package - given a rootkey (GOPATH/GOROOT) and a package,
;; find the path to the package on the filesystem (or nil if not
;; found)
;;-----------------------------------------------------------------
(defn- find-package [path rootkey pkg]
  (let [roots (-> (get-go-env path rootkey)
                  (string/split #":"))]

    ;; $roots may possibly contain multiple entries, delineated by ":"
    ;; Therefore, we must recursively check each one found
    (loop [root (first roots)
           remain (rest roots)]

      (cond

        ;; terminating condition, no match found
        (nil? root) nil

        :else (let [path (io/file root "src" pkg)]
                (cond
                  ;; Check if a directory exists for the package
                  ;; within $root/src/$pkg.  If it does, we declare a
                  ;; match, terminating the search.
                  (fs/exists? path) path

                  ;; else continue searching
                  :else (recur (first remain) (rest remain))))))))

;;-----------------------------------------------------------------
;; syslib? - returns true if the provided pkg is a golang system
;; library
;;-----------------------------------------------------------------
(defn- syslib? [pkg]
  (when (find-package nil "GOROOT" pkg)
    true))

;;-----------------------------------------------------------------
;; shimlib? - use a set as a predicate for detecting packages
;; already included in the ccenv on the peer
;;-----------------------------------------------------------------
(def shimlib? #{"github.com/hyperledger/fabric/core/chaincode/shim"
                "github.com/hyperledger/fabric/protos/peer"})

;;-----------------------------------------------------------------
;; generated? - returns true if the package is generated code.  We
;; can elide these from the package since they will be regenerated
;; by the peer when chaintool builds the package.
;;-----------------------------------------------------------------
(defn- generated? [pkg]
  (when (re-find #"^hyperledger\/cc" pkg)
    true))

;;-----------------------------------------------------------------
;; golang-src? - returns true if the path points to a type of
;; golang src
;;-----------------------------------------------------------------
(defn- golang-src? [path]
  (when (-> (fs/extension path)
            #{".go" ".c" ".h"})
    true))

;;-----------------------------------------------------------------
;; golang-test? - returns true if the path points to a golang
;; unit-test
;;-----------------------------------------------------------------
(defn- golang-test? [path]
  (when (->> (fs/base-name path)
             (re-find #"_test.go"))
    true))

;;-----------------------------------------------------------------
;; project-pkg? - returns true if the pkg is included in the project
;;-----------------------------------------------------------------
(defn- project-pkg? [prjpath pkgpath]
  (let [prjsrc (io/file prjpath "src")]
    (fs/child-of? prjsrc pkgpath)))

;;-----------------------------------------------------------------
;; go-list - executes a "go list" operation and then formats and
;; filters the result to only contain relavant packages.  Relevant
;; in this context means that we strip away any system/shim/generated
;; code and leave only the actionable packages
;;-----------------------------------------------------------------
(defn- go-list [path type pkg]
  (->> (go-cmd {:path path :silent true} "list" "-f" (str "{{ join ." type " \"\\n\"}}") pkg)
       string/split-lines
       (remove syslib?)
       (remove shimlib?)
       (remove generated?)))

;;-----------------------------------------------------------------
;; resolve-import - given an imported $pkg, return a list of all of
;; the direct and transitive dependencies and the pkg itself.
;;-----------------------------------------------------------------
(defn- resolve-import [path pkg]

  ;; Download the package (using "go get") if it is not already local
  (when-not (find-package path "GOPATH" pkg)
    (println (str "INFO: Downloading missing package \"" pkg "\""))
    (go-cmd {:path path :silent true} "get" "-d" "-v" pkg))

  ;; Finally build our list of [$pkg $dep1 $dep2 ... $depN]
  (->> (go-list path "Deps" pkg)
       (cons pkg)))

;;-----------------------------------------------------------------
;; vendor-dependencies - given a list of pkgs that we depend on,
;; resolve a list of source files vendored under our chaincode
;; package
;;-----------------------------------------------------------------
(defn- vendor-dependencies [path pkgs]
  (for [pkg pkgs]
    (let [pkgpath (find-package path "GOPATH" pkg)]

      (cond

        ;; Check if the package is missing and abort
        (not pkgpath)
        (util/abort -1 (str "Import \"" pkg "\" declared and not found."))

        ;; Check if the package is part of the project sources and skip it
        (project-pkg? path pkgpath)
        nil

        ;; Anything that remains needs to be vendored with our code
        :else
        (do
          (println (str "INFO: Auto-vendoring package \"" pkg "\""))

          (let [files (->> (fs/list-dir pkgpath)
                           (filter fs/file?)
                           (filter golang-src?)
                           (remove golang-test?))]

            ;; files need to be converted to {:handle :path} tuples with
            ;; the path remapped under src/chaincode/vendor/$pkg
            (for [file files]
              (-> (convert-file pkgpath file)
                  (update :path #(-> (io/file "src" "chaincode" "vendor" pkg %)
                                     io/as-relative-path))))))))))

;;-----------------------------------------------------------------
;;-----------------------------------------------------------------
;; GolangUserspacePlatform
;;-----------------------------------------------------------------
;; Supports "org.hyperledger.chaincode.golang" platform, a golang
;; based environment for standard chaincode applications.
;;-----------------------------------------------------------------
;;-----------------------------------------------------------------
(deftype GolangUserspacePlatform []
  platforms.api/Platform

  ;;-----------------------------------------------------------------
  ;; env - Emits the GOPATH used for building golang chaincode
  ;;-----------------------------------------------------------------
  (env [_ {:keys [path]}]
    (println (str "GOPATH=" (buildgopath path))))

  ;;-----------------------------------------------------------------
  ;; build - generates all golang platform artifacts within the
  ;; default location in the build area
  ;;-----------------------------------------------------------------
  (build [_ {:keys [path config output]}]
    (let [builddir (io/file path "build")]

      ;; run our code generator
      (generate {:base "hyperledger"
                 :ipath (io/file path "src/interfaces")
                 :opath (io/file builddir "src")
                 :config config})

      ;; install go dependencies
      (go-cmd {:path path} "get" "-d" "-v" "chaincode")

      ;; build the actual code
      (let [gobin (io/file builddir "bin")]
        (io/make-parents (io/file gobin ".dummy"))
        (io/make-parents output)
        (go-cmd {:path path
                 :env {"GOBIN" (.getCanonicalPath gobin)}}
                "build" "-o" (.getCanonicalPath output)
                "-ldflags" "-linkmode external -extldflags '-static'"
                "chaincode"))

      (println "Compilation complete")))

  ;;-----------------------------------------------------------------
  ;; clean - cleans up any artifacts from a previous build, if any
  ;;-----------------------------------------------------------------
  (clean [_ {:keys [path]}]
    (fileutils/recursive-delete (io/file path "build")))

  ;;-----------------------------------------------------------------
  ;; package - writes the chaincode package to the filesystem
  ;;-----------------------------------------------------------------
  (package [_ {:keys [path config outputfile compressiontype]}]
    (let [filespec ["src" config/configname]]

      (println "Writing CAR to:" (.getCanonicalPath outputfile))
      (println "Using path" path (str filespec))

      ;; compute dep/file list
      (let [deps (->> (go-list path "Imports" "chaincode")
                      (map #(resolve-import path %))
                      flatten)
            files (->> (concat
                        (vendor-dependencies path deps)
                        (get-project-files path filespec))
                       flatten
                       (remove nil?))]

        ;; generate the actual file
        (car/write files compressiontype outputfile))

      ;; re-use the ls function to display the contents
      (ls outputfile))))

(defn factory [version]
  (if (= version 1)
    (GolangUserspacePlatform.)
    (util/abort -1 (str "Version " version " not supported"))))
