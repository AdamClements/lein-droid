(ns test.leindroid.sample.main
  (:use [neko.activity :only [defactivity do-ui set-content-view!]]
        [neko.notify :only [toast]]
        [neko.ui :only [defui by-id]]
        [neko.application :only [defapplication]]))

(declare ^android.app.Activity a
         ^android.widget.EditText user-input)

;; This line defines the Application class and automatically
;; initializies neko and nREPL.
(defapplication test.leindroid.sample.Application)

(defn notify-from-edit [_]
  (toast (str "Your input: " (.getText user-input))
         :long))

(defactivity test.leindroid.sample.MainActivity
  :def a
  :create
  (fn [this bundle]
    (do-ui
     (set-content-view! a
      (defui [:linear-layout {:orientation :vertical
                              :layout-width :fill
                              :layout-height :wrap}
              [:edit {:def user-input
                      :layout-width :fill}]
              [:button {:text "Touch me"
                        :on-click notify-from-edit}]])))))
