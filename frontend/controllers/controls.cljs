(ns frontend.controllers.controls
  (:require [cljs.reader :as reader]
            [frontend.api :as api]
            [frontend.async :refer [put!]]
            [frontend.models.project :as project-model]
            [frontend.models.build :as build-model]
            [frontend.intercom :as intercom]
            [frontend.state :as state]
            [frontend.analytics :as analytics]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :refer [mlog]]
            [goog.string :as gstring]
            goog.style)
  (:require-macros [dommy.macros :refer [sel sel1]]
                   [frontend.utils :refer [inspect]])
  (:import [goog.fx.dom.Scroll]))

;; --- Helper Methods ---

(defn container-id [container]
  (int (last (re-find #"container_(\d+)" (.-id container)))))

;; --- Navigation Multimethod Declarations ---

(defmulti control-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message args state] message))

(defmulti post-control-event!
  (fn [target message args previous-state current-state] message))

;; --- Navigation Multimethod Implementations ---

(defmethod control-event :default
  [target message args state]
  (mlog "Unknown controls: " message)
  state)

(defmethod post-control-event! :default
  [target message args previous-state current-state]
  (mlog "No post-control for: " message))


(defmethod control-event :user-menu-toggled
  [target message _ state]
  (update-in state [:settings :menus :user :open] not))


(defmethod control-event :show-all-branches-toggled
  [target message project-id state]
  (update-in state [:settings :projects project-id :show-all-branches] not))

(defmethod post-control-event! :show-all-branches-toggled
  [target message project-id previous-state current-state]
  ;;; XXX This should happen on routing, obviously
  ;; (print project-id
  ;;        " show-all-branches-toggled "
  ;;        (get-in previous-state [:settings :projects project-id :show-all-branches])
  ;;        " => "
  ;;        (get-in current-state [:settings :projects project-id :show-all-branches]))
  )


(defmethod control-event :state-restored
  [target message path state]
  (let [str-data (.getItem js/localStorage "circle-state")]
    (if (seq str-data)
      (-> str-data
          reader/read-string
          (assoc :comms (:comms state)))
      state)))


(defmethod control-event :usage-queue-why-toggled
  [target message {:keys [build-id]} state]
  (update-in state state/show-usage-queue-path not))

(defmethod post-control-event! :usage-queue-why-toggled
  [target message {:keys [username reponame
                          build_num build-id]} previous-state current-state]
  (when (get-in current-state state/show-usage-queue-path)
    (let [api-ch (get-in current-state [:comms :api])]
      (api/get-usage-queue (get-in current-state state/build-path) api-ch))))


(defmethod control-event :selected-add-projects-org
  [target message args state]
  (-> state
      (assoc-in [:settings :add-projects :selected-org] args)
      (assoc-in [:settings :add-projects :repo-filter-string] "")))

(defmethod post-control-event! :selected-add-projects-org
  [target message args previous-state current-state]
  (let [login (:login args)
        type (:type args)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :get
              (gstring/format "/api/v1/user/%s/%s/repos" (name type) login)
              :repos
              api-ch
              :context args)))


(defmethod control-event :show-artifacts-toggled
  [target message build-id state]
  (update-in state state/show-artifacts-path not))

(defmethod post-control-event! :show-artifacts-toggled
  [target message _ previous-state current-state]
  (when (get-in current-state state/show-artifacts-path)
    (let [api-ch (get-in current-state [:comms :api])
          build (get-in current-state state/build-path)]
      (utils/ajax :get
                  (gstring/format "/api/v1/project/%s/%s/artifacts"
                                  (vcs-url/project-name (:vcs_url build))
                                  (:build_num build))
                  :build-artifacts
                  api-ch
                  :context (build-model/id build)))))


(defmethod control-event :container-selected
  [target message container-id state]
  (assoc-in state state/current-container-path container-id))

(defmethod post-control-event! :container-selected
  [target message container-id previous-state current-state]
  (when-let [parent (sel1 target "#container_parent")]
    (let [container (sel1 target (str "#container_" container-id))
          current-scroll-top (.-scrollTop parent)
          current-scroll-left (.-scrollLeft parent)
          new-scroll-left (int (.-x (goog.style.getContainerOffsetToScrollInto container parent)))
          scroller (or (.-scroll_handler parent)
                       (set! (.-scroll_handler parent)
                             ;; Store this on the parent so that we don't handle parent scroll while
                             ;; the animation is playing
                             (goog.fx.dom.Scroll. parent
                                                  #js [0 0]
                                                  #js [0 0]
                                                  250)))]
      (set! (.-startPoint scroller) #js [current-scroll-left current-scroll-top])
      (set! (.-endPoint scroller) #js [new-scroll-left current-scroll-top])
      (.play scroller))))


(defmethod control-event :action-log-output-toggled
  [target message {:keys [index step]} state]
  (update-in state (state/show-action-output-path index step) not))

(defmethod post-control-event! :action-log-output-toggled
  [target message {:keys [index step] :as args} previous-state current-state]
  (let [action (get-in current-state (state/action-path index step))]
    (when (and (:show-output action)
               (:has_output action)
               (not (:output action)))
      (let [api-ch (get-in current-state [:comms :api])
            build (get-in current-state state/build-path)
            url (if (:output_url action)
                  (:output_url action)
                  (gstring/format "/api/v1/project/%s/%s/output/%s/%s"
                                  (vcs-url/project-name (:vcs_url build))
                                  (:build_num build)
                                  step
                                  index))]
        (utils/ajax :get
                    url
                    :action-log
                    api-ch
                    :context args)))))


(defmethod control-event :selected-project-parallelism
  [target message {:keys [project-id parallelism]} state]
  (assoc-in state (conj state/project-path :parallel) parallelism))

(defmethod post-control-event! :selected-project-parallelism
  [target message {:keys [project-id parallelism]} previous-state current-state]
  (when (not= (get-in previous-state state/project-path)
              (get-in current-state state/project-path))
    (let [project-name (vcs-url/project-name project-id)
          api-ch (get-in current-state [:comms :api])]
      ;; TODO: edit project settings api call should respond with updated project settings
      (utils/ajax :put
                  (gstring/format "/api/v1/project/%s/settings" project-name)
                  :update-project-parallelism
                  api-ch
                  :params {:parallel parallelism}
                  :context {:project-id project-id}))))


(defmethod control-event :dismiss-invite-form
  [target message _ state]
  (assoc-in state state/dismiss-invite-form-path true))


(defmethod control-event :invite-selected-all
  [target message _ state]
  (update-in state state/build-github-users-path (fn [users]
                                                   (vec (map #(assoc % :checked true) users)))))


(defmethod control-event :invite-selected-none
  [target message _ state]
  (update-in state state/build-github-users-path (fn [users]
                                                   (vec (map #(assoc % :checked false) users)))))


(defmethod control-event :dismiss-config-errors
  [target message _ state]
  (assoc-in state state/dismiss-config-errors-path true))


(defmethod control-event :edited-input
  [target message {:keys [value path]} state]
  (assoc-in state path value))


(defmethod control-event :toggled-input
  [target message {:keys [path]} state]
  (update-in state path not))


(defmethod post-control-event! :intercom-dialog-raised
  [target message dialog-message previous-state current-state]
  (intercom/raise-dialog (get-in current-state [:comms :errors]) dialog-message))


(defmethod post-control-event! :intercom-user-inspected
  [target message criteria previous-state current-state]
  (if-let [url (intercom/user-link)]
    (js/window.open url)
    (print "No matching url could be found from current window.location.pathname")))


(defmethod post-control-event! :state-persisted
  [target message channel-id previous-state current-state]
  (.setItem js/localStorage "circle-state"
            (pr-str (dissoc current-state :comms))))


(defmethod post-control-event! :retry-build-clicked
  [target message {:keys [build-num build-id vcs-url] :as args} previous-state current-state]
  (let [api-ch (-> current-state :comms :api)
        org-name (vcs-url/org-name vcs-url)
        repo-name (vcs-url/repo-name vcs-url)]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/%s/%s/retry" org-name repo-name build-num)
                :retry-build
                api-ch)))


(defmethod post-control-event! :followed-repo
  [target message repo previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/follow" (vcs-url/project-name (:vcs_url repo)))
                :follow-repo
                api-ch
                :context repo)))


(defmethod post-control-event! :followed-project
  [target message {:keys [vcs-url project-id]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/follow" (vcs-url/project-name vcs-url))
                :follow-project
                api-ch
                :context {:project-id project-id})))


(defmethod post-control-event! :unfollowed-repo
  [target message repo previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/unfollow" (vcs-url/project-name (:vcs_url repo)))
                :unfollow-repo
                api-ch
                :context repo)))


(defmethod post-control-event! :unfollowed-project
  [target message {:keys [vcs-url project-id]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/unfollow" (vcs-url/project-name vcs-url))
                :unfollow-project
                api-ch
                :context {:project-id project-id})))


;; XXX: clean this up
(defmethod post-control-event! :container-parent-scroll
  [target message _ previous-state current-state]
  (let [controls-ch (get-in current-state [:comms :controls])
        current-container-id (get-in current-state state/current-container-path 0)
        parent (sel1 target "#container_parent")
        parent-scroll-left (.-scrollLeft parent)
        current-container (sel1 target (str "#container_" current-container-id))
        current-container-scroll-left (int (.-x (goog.style.getContainerOffsetToScrollInto current-container parent)))
        ;; XXX stop making (count containers) queries on each scroll
        containers (sort-by (fn [c] (Math/abs (- parent-scroll-left (.-x (goog.style.getContainerOffsetToScrollInto c parent)))))
                            (sel parent ".container-view"))
        ;; if we're scrolling left, then we want the container whose rightmost portion is showing
        ;; if we're scrolling right, then we want the container whose leftmost portion is showing
        new-scrolled-container-id (if (= parent-scroll-left current-container-scroll-left)
                                    current-container-id
                                    (if (< parent-scroll-left current-container-scroll-left)
                                      (apply min (map container-id (take 2 containers)))
                                      (apply max (map container-id (take 2 containers)))))]
    ;; This is kind of dangerous, we could end up with an infinite loop. Might want to
    ;; do a swap here (or find a better way to structure this!)
    (when (not= current-container-id new-scrolled-container-id)
      (put! controls-ch [:container-selected new-scrolled-container-id]))))


(defmethod post-control-event! :started-edit-settings-build
  [target message {:keys [project-id branch]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    ;; TODO: edit project settings api call should respond with updated project settings
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/tree/%s" project-name (gstring/urlEncode branch))
                :start-build
                api-ch)))


(defmethod post-control-event! :created-env-var
  [target message {:keys [project-id env-var]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/envvar" project-name)
                :create-env-var
                api-ch
                :params env-var
                :context {:project-id project-id})))


(defmethod post-control-event! :deleted-env-var
  [target message {:keys [project-id env-var-name]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/envvar/%s" project-name env-var-name)
                :delete-env-var
                api-ch
                :context {:project-id project-id
                          :env-var-name env-var-name})))


(defmethod post-control-event! :saved-dependencies-commands
  [target message {:keys [project-id settings]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-dependencies-commands
                api-ch
                :params settings
                :context {:project-id project-id})))


(defmethod post-control-event! :saved-test-commands
  [target message {:keys [project-id settings]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-test-commands
                api-ch
                :params settings
                :context {:project-id project-id})))


(defmethod post-control-event! :saved-test-commands-and-build
  [target message {:keys [project-id settings branch]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-test-commands-and-build
                api-ch
                :params settings
                :context {:project-id project-id
                          :branch branch})))


(defmethod post-control-event! :saved-notification-hooks
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])
        settings (project-model/notification-settings (get-in current-state state/project-path))]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-notification-hooks
                api-ch
                :params settings
                :context {:project-id project-id})))


(defmethod post-control-event! :saved-ssh-key
  [target message {:keys [project-id ssh-key]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/ssh-key" project-name)
                :save-ssh-key
                api-ch
                :params ssh-key
                :context {:project-id project-id})))


(defmethod post-control-event! :deleted-ssh-key
  [target message {:keys [project-id fingerprint]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/ssh-key" project-name)
                :delete-ssh-key
                api-ch
                :params {:fingerprint fingerprint}
                :context {:project-id project-id
                          :fingerprint fingerprint})))


(defmethod post-control-event! :saved-project-api-token
  [target message {:keys [project-id api-token]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/token" project-name)
                :save-project-api-token
                api-ch
                :params api-token
                :context {:project-id project-id})))


(defmethod post-control-event! :deleted-project-api-token
  [target message {:keys [project-id token]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/token/%s" project-name token)
                :delete-project-api-token
                api-ch
                :context {:project-id project-id
                          :token token})))


(defmethod post-control-event! :set-heroku-deploy-user
  [target message {:keys [project-id login]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/heroku-deploy-user" project-name)
                :set-heroku-deploy-user
                api-ch
                :context {:project-id project-id
                          :login login})))


(defmethod post-control-event! :removed-heroku-deploy-user
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/heroku-deploy-user" project-name)
                :remove-heroku-deploy-user
                api-ch
                :context {:project-id project-id})))


(defmethod post-control-event! :set-user-session-setting
  [target message {:keys [setting value]} previous-state current-state]
  (set! (.. js/window -location -search) (str "?" (name setting) "=" value)))


(defmethod post-control-event! :load-first-green-build-github-users
  [target message {:keys [project-name]} previous-state current-state]
  (utils/ajax :get
              (gstring/format "/api/v1/project/%s/users" project-name)
              :first-green-build-github-users
              (get-in current-state [:comms :api])
              :context {:project-name project-name}))


(defmethod post-control-event! :invited-github-users
  [target message {:keys [project-name invitees]} previous-state current-state]
  (utils/ajax :post
              (gstring/format "/api/v1/project/%s/users/invite" project-name)
              :invite-github-users
              (get-in current-state [:comms :api])
              :context {:project-name project-name}
              :params invitees)
  (analytics/track "Sent invitations" {:first_green_build true
                                       :project project-name
                                       :users (map :login invitees)})
  (doseq [u invitees]
    (analytics/track "Sent invitation" {:first_green_build true
                                        :project project-name
                                        :login (:login u)
                                        :id (:id u)
                                        :email (:email u)})))


(defmethod post-control-event! :report-build-clicked
  [target message {:keys [build-url]} previous-state current-state]
  (intercom/raise-dialog (get-in current-state [:comms :errors])
                         (gstring/format "I think I found a bug in Circle at %s" build-url)))


(defmethod post-control-event! :enabled-project
  [target message {:keys [project-name project-id]} previous-state current-state]
  (utils/ajax :post
              (gstring/format "/api/v1/project/%s/enable" project-name)
              :enable-project
              (get-in current-state [:comms :api])
              :context {:project-name project-name
                        :project-id project-id}))
