(ns {{package}}.main
  (:use [neko.activity :only [defactivity set-content-view!]]
        [neko.threading :only [on-ui]]
        [neko.ui :only [defui]]
        [neko.application :only [defapplication]]))

(defapplication {{package}}.Application)

(defactivity {{package}}.{{activity}}
  :def a
  :on-create
  (fn [this bundle]
    (on-ui
     (set-content-view! a
      (defui [:linear-layout {}
              [:text-view {:text "Hello from Clojure!"}]])))))