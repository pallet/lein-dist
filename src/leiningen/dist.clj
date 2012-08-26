(ns leiningen.dist
  (:use
   [clojure.java.io :only [as-url copy file reader]]
   [clojure.java.shell :only [sh]]
   [clojure.pprint :only [pprint]]
   [leiningen.core.project :only [unmerge-profiles]])
  (:import
   [org.apache.tools.tar TarOutputStream TarEntry]
   [java.io File FileOutputStream ByteArrayOutputStream]))

(defn dist-project
  [project offline?]
  (let [n (inc (count (:root project)))
        subf #(subs % n)
        project (unmerge-profiles project [:user])
        project (assoc project
                  :local-repo "local-m2"
                  :offline? offline?)
        project (reduce
                 (fn [p k]
                   (update-in p [k] #(vec (map subf %))))
                 project
                 [:source-paths :resource-paths :test-paths])
        project (reduce
                 (fn [p k]
                   (update-in p [k] subf))
                 (assoc project :local-repo "local-m2")
                 [:native-path :target-path])]
    project))

(def ^:private keys-to-remove
  [:compile-path :name :group :version :root])

(defn write-project
  [output-dir {:keys [name group] :as project}]
  (let [proj-sym (if (= name group) (symbol name) (symbol group name))]
    (spit (.getCanonicalPath (file output-dir "project.clj"))
          (with-out-str
            (pprint `(~'defproject ~proj-sym ~(:version project)
                       ~@(apply concat
                                (apply dissoc project keys-to-remove))))))))

(defn write-profiles
  [output-dir]
  (let [user-repo (format
                   "file://%s/.m2/repository"
                   (System/getProperty "user.home"))
        f (file output-dir ".lein" "profiles.clj")]
    (.mkdirs (.getParentFile f))
    (spit (.getCanonicalPath f)
          (with-out-str
            (pprint `{:user
                      {:repositories
                       {"mylocal" {:url ~user-repo :checksum :ignore}}}})))))

(defn remove-profiles
  [output-dir]
  (.delete (file output-dir ".lein" "profiles.clj")))

(defn lein-script
  [output-dir]
  (let [lein-version (System/getenv "LEIN_VERSION")
        url (as-url
             (format
              "https://raw.github.com/technomancy/leiningen/%s/bin/lein"
              (if (.endsWith lein-version "SNAPSHOT") "preview" lein-version)))]
    (with-open [rdr (reader url)]
      (copy rdr (file output-dir "lein")))
    (let [{:keys [exit out err]} (sh "chmod" "755" "lein"  :dir output-dir)]
      (when-not (zero? exit)
        (binding [*out* *err*]
          (println "Failed to chmod lein script")
          (println err))))))

(defn build-local-repo
  [output-dir]
  (let [{:keys [exit err out]} (sh "./lein" "deps"
                                   :dir output-dir
                                   :env {"LEIN_HOME" "./.lein"})]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println "Failed to resolve dependencies")
        (println err)))))

(defn build-dist-project
  [output-dir project]
  (let [output-dir (doto (file (:target-path project) "lein-dist") (.mkdirs))]
    (write-project output-dir (dist-project project false))
    (write-profiles output-dir)
    (lein-script output-dir)
    (build-local-repo output-dir)
    (write-project output-dir (dist-project project true))
    (remove-profiles output-dir)))

(defn- add-file [^TarOutputStream tar prefix-path ^File f n]
  (when-not (.isDirectory f)
    (let [entry (TarEntry. f)
          byte-stream (ByteArrayOutputStream.)]
      (.setName
       entry (str prefix-path File/separator (subs (.getAbsolutePath f) n)))
      (when (.canExecute f)
        (.setMode entry 0755))
      (copy f byte-stream)
      (.putNextEntry tar entry)
      (.write tar (.toByteArray byte-stream))
      (.closeEntry tar))))

(defn tar
  [^File tar-file ^String prefix-path ^File dir]
  (.delete tar-file)
  (let [base-path (.getAbsolutePath dir)
        n (inc (count base-path))]
    (with-open [tar (TarOutputStream. (FileOutputStream. tar-file))]
      (.setLongFileMode tar TarOutputStream/LONGFILE_GNU)
      (doseq [p (file-seq dir)]
        (add-file tar prefix-path p n)))))

(defn dist
  "Build a self contained distribution tar file for the project."
  [project & args]
  (let [output-dir (doto (file (:target-path project) "lein-tar") (.mkdirs))]
    (build-dist-project output-dir project)
    (let [tar-name (str (:name project) "-" (:version project))
          tar-file (file (:target-path project) (format "%s.tar" tar-name))]
      (tar tar-file tar-name output-dir)
      (println "Created" (.getAbsolutePath tar-file)))))
