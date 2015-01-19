(defproject rukkit "1.0.0-SNAPSHOT"
  :description "Awesome super benri cool plugin"
  :dependencies [[org.bukkit/bukkit "1.7.9-R0.2"]
                 [org.jruby/jruby-complete "1.7.16.1"]
                 [org.javassist/javassist "3.18.2-GA"]
                 [redis.clients/jedis "2.6.0"]
                 [com.google.guava/guava "18.0"]]
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :repositories {"org.bukkit"
                 "http://repo.bukkit.org/content/groups/public/"}
  :javac-target "1.8"
  :javac-source "1.8"
  :javac-options ["-d" "classes/" "-Xlint:all"]
  :java-source-paths ["javasrc"])
