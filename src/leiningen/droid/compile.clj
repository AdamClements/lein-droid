(ns leiningen.droid.compile
  "This part of the plugin is responsible for the project compilation."
  (:refer-clojure :exclude [compile])
  (:require [leiningen compile javac]
            [clojure.java.io :as io]
            [clojure.set :as sets]
            [leiningen.core.eval :as eval]
            [clojure.string :as st]
            [clostache.parser :as clostache])
  (:use [leiningen.droid.utils :only [get-sdk-android-jar sdk-binary
                                      ensure-paths sh dev-build?]]            
        [leiningen.new.templates :only [slurp-resource sanitize]]
        [leiningen.droid.manifest :only [get-package-name generate-manifest]]
        [leiningen.core
         [main :only [debug info abort]]
         [classpath :only [get-classpath]]]
        [bultitude.core :only [namespaces-on-classpath]]))

;; ### Pre-compilation tasks

(defn save-data-readers-to-resource
  "Save project's *data-readers* value to application's resources so
  it can be later retrieved in runtime. This is necessary to be able
  to use data readers when developing in REPL on the device."
  [{{:keys [assets-gen-path]} :android :as project}]
  (.mkdirs (io/file assets-gen-path))
  (eval/eval-in-project
   project
   `(spit (io/file ~assets-gen-path "data_readers.clj")
          (into {} (map (fn [[k# v#]]
                          [k# (symbol (subs (str v#) 2))])
                        *data-readers*)))))

(defn- java-type [x]
  (condp = (type x)
    Boolean "boolean"
    String  "String"
    Long    "long"
    Double  "double"
    (assert false ":build-config only supports boolean, String, long and double types")))

(defn map-constants [constants]
  (map (fn [[k v]]
         {:key k
          :value (pr-str v)
          :type (java-type v)})
       constants))

(defn generate-build-constants
  [{{:keys [manifest-path gen-path build-config]} :android :as project}]  
  (let [res                (io/resource "templates/BuildConfig.java")
        package-name       (get-package-name manifest-path)
        package-path       (apply io/file gen-path (st/split package-name #"\."))
        template-constants (map-constants build-config)]
    (ensure-paths package-path)
    (->> {:debug        (dev-build? project)
          :package-name package-name
          :constants    template-constants}
         (clostache/render (slurp-resource res))
         (spit (io/file package-path "BuildConfig.java"))))
  project)

(defn generate-resource-code
  "Generates the R.java file from the resources.

  This task is necessary if you define the UI in XML and also to gain
  access to your strings and images by their ID."
  [{{:keys [sdk-path target-version manifest-path res-path gen-path
            out-res-path external-res-paths library]} :android
    java-only :java-only :as project}]
  (info "Generating R.java...")
  (let [aapt-bin (sdk-binary project :aapt)
        android-jar (get-sdk-android-jar sdk-path target-version)
        manifest-file (io/file manifest-path)
        library-specific (if library "--non-constant-id" "--auto-add-overlay")
        external-resources (for [res external-res-paths] ["-S" res])]
    (ensure-paths manifest-path res-path android-jar)
    (.mkdirs (io/file gen-path))
    (.mkdirs (io/file out-res-path))
    (sh aapt-bin "package" library-specific "-f" "-m"
        "-M" manifest-path
        "-S" out-res-path
        "-S" res-path
        external-resources
        "-I" android-jar
        "-J" gen-path
        "--generate-dependencies"))
  project)

(defn code-gen
  "Generates R.java and builds a manifest with the appropriate version
  code and substitutions."
  [project]
  (doto project generate-manifest generate-resource-code generate-build-constants))

;; ### Compilation

;; Stores a set of namespaces that should always be compiled
;; regardless of the build type. Since these namespaces are used in
;; `eval-in-project` call they naturally don't get AOT-compiled during
;; automatic dependency resolution, so we have to make sure they are
;; compiled anyway.
;;
(def ^:private always-compile-ns
  '#{clojure.core clojure.core.protocols clojure.string
     clojure.java.io})

(defn namespaces-to-compile
  "Takes project and returns a set of namespaces that should be AOT-compiled."
  [{{:keys [aot aot-exclude-ns]} :android :as project}]
  (-> (case aot
        :all
          (seq (leiningen.compile/stale-namespaces (assoc project :aot :all)))
        :all-with-unused
          (namespaces-on-classpath :classpath
                                   (map io/file (get-classpath project)))
        ;; else
          (map symbol aot))
      set
      (sets/union always-compile-ns)
      (sets/difference (set (map symbol aot-exclude-ns)))))

(defn compile-clojure
  "Compiles Clojure files into .class files.

  If `:aot` project parameter equals `:all` then compiles the
  necessary dependencies. If `:aot` equals `:all-with-unused` then
  compiles all namespaces of the dependencies whether they were
  referenced in the code or not. The latter is useful for the
  REPL-driven development.

  Uses neko to set compilation flags. Some neko macros and
  subsequently project code depends on them to eliminate
  debug-specific code when building the release."
  [{{:keys [enable-dynamic-compilation start-nrepl-server
            manifest-path repl-device-port ignore-log-priority]}
    :android
    {:keys [nrepl-middleware]} :repl-options
    :as project}]
  (info "Compiling Clojure files...")
  (ensure-paths manifest-path)
  (debug "Project classpath:" (get-classpath project))
  (let [nses (namespaces-to-compile project)
        dev-build (dev-build? project)
        opts (cond-> {:neko.init/release-build (not dev-build)
                      :neko.init/start-nrepl-server start-nrepl-server
                      :neko.init/nrepl-port repl-device-port
                      :neko.init/enable-dynamic-compilation
                      enable-dynamic-compilation
                      :neko.init/ignore-log-priority ignore-log-priority
                      :neko.init/nrepl-middleware (list 'quote nrepl-middleware)
                      :neko.init/package-name (get-package-name manifest-path)}
                     (not dev-build) (assoc :elide-meta
                                       [:doc :file :line :column :added :arglists]))]
    (info (format "Build type: %s, dynamic compilation: %s, remote REPL: %s."
                  (if dev-build "debug" "release")
                  (if (or dev-build start-nrepl-server
                          enable-dynamic-compilation)
                    "enabled" "disabled")
                  (if (or dev-build start-nrepl-server) "enabled" "disabled")))
    (let [form
          `(binding [*compiler-options* ~opts]
             (doseq [namespace# '~nses]
               (println "Compiling" namespace#)
               (clojure.core/compile namespace#)))
          project (update-in project [:prep-tasks]
                             (partial remove #{"compile"}))]
      (.mkdirs (io/file (:compile-path project)))
      (try (eval/eval-in-project project form)
           (info "Compilation succeeded.")
           (catch Exception e
             (abort "Compilation failed."))))))

(defn compile
  "Compiles both Java and Clojure source files."
  [{{:keys [sdk-path gen-path]} :android, java-only :java-only :as project} & args]
  (ensure-paths sdk-path)
  (let [project (update-in project [:java-source-paths] conj gen-path)]
    (when-not java-only
      (save-data-readers-to-resource project))
    (apply leiningen.javac/javac project args)
    (when-not java-only
      (compile-clojure project))))
