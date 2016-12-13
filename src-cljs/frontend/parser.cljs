(ns frontend.parser
  (:require [compassus.core :as compassus]
            [frontend.analytics.core :as analytics]
            [frontend.components.app :as app]
            [frontend.routes :as routes]
            [om.next :as om-next]
            [om.next.impl.parser :as parser]
            [om.util :as om-util]))

(defn- recalculate-query
  "Each node of an AST has a :query key which is the query form of
  its :children. This is a kind of denormalization. If we change the :children,
  the :query becomes out of date, and Om will use the old :query rather than the
  new :children. This probably represents an Om bug of some kind.

  As a workaround, any time you change an AST's :children, run it through
  recalculate-query before returning it."
  [ast]
  (-> ast
      om-next/ast->query
      parser/expr->ast))


;; Many of our keys have the same local reading behavior, and many of our keys
;; have the same remote reading behavior, but they're not always the same keys.
;; For our app, then, it makes sense to divide the read function into two
;; multimethods, each with a default: read-local and read-remote.
;;
;; The values they return will become the values of the :value and :remote keys,
;; respectively, in the read function's result.

(defmulti read-local om-next/dispatch)
(defmulti read-remote om-next/dispatch)

;; Most keys resolve locally as an ordinary db->tree.
(defmethod read-local :default
  [{:keys [state query] :as env} key params]
  (let [st @state]
    (om-next/db->tree query (get st key) st)))

;; Most keys pass their queries on to the remote. We add :query-root true so
;; that the key will be sent to the remote as one of the root keys in the
;; query (using om-next/process-roots).
;;
;; This matters because our parser sits inside Compassus. The query that goes to
;; the send function will have a single root key, which will be the route key,
;; and the route component's remote query will be joined on that key. The send
;; function will then use om-next/process-roots to raise the :query-roots to the
;; root, so it can ignore UI concerns.
(defmethod read-remote :default
  [{:keys [ast] :as env} key params]
  (assoc ast :query-root true))


(def
  ^{:private true
    :doc
    "Keys under :app/current-user which are fed by the page's renderContext,
     and shouldn't be fetched from the remote by API"}
  render-context-keys
  #{:user/login
    :user/bitbucket-authorized?})


;; :app/current-user works much like :default, but it's a bit of a special case,
;; as some of its data is never fetched by the remote, and only exists in the
;; initial app state, added from the page's renderContext. We exclude those keys
;; here so we don't try to read them remotely.
(defmethod read-remote :app/current-user
  [{:keys [ast] :as env} key params]
  (-> ast
      (assoc :query-root true)
      ;; Don't pass renderContext keys on to the remote. They're
      ;; local-only.
      (update :children
              (fn [children]
                (into []
                      (remove #(contains? render-context-keys (:key %)))
                      children)))
      recalculate-query))

;; :legacy/state reads the entire map under :legacy/state in the app state. It
;; does no db->tree massaging, because the legacy state lives in the om.core
;; world and doesn't expect anything like that.
(defmethod read-local :legacy/state
  [{:keys [state] :as env} key params]
  ;; Don't include :inputs; it's not meant to be passed into the top of the
  ;; legacy app, but instead is accessed directly by
  ;; frontend.components.inputs/get-inputs-from-app-state.
  (dissoc (get @state key) :inputs))

;; The :legacy/state is never read remotely.
(defmethod read-remote :legacy/state
  [env key params]
  nil)

;; The keys in :app/route-data have idents for values. If we query through
;; them, we replace the key with the current ident value before passing it
;; on to the remote. That is, if the UI queries for
;;
;; [{:app/route-data [{:route-data/widget [:widget/name]}]}]
;;
;; and the app state contains
;;
;; {;; ...
;;  :app/route-data {:route-data/widget [:widget/by-id 5]}
;;  ;; ...
;;  }
;;
;; we rewrite the query for the remote to be
;;
;; [{:app/route-data [^:query-root {[:widget/by-id 5] [:widget/name]}]}]
;;
;; Then the remote will look up the name of the current widget.
;;
;; Note that the :default case already handles the read-local
;; for :app/route-data perfectly.
(defmethod read-remote :app/route-data
  [{:keys [state ast] :as env} key params]
  (let [st @state
        new-ast (update ast :children
                        (partial
                         into [] (keep
                                  #(let [ident (get-in st [key (:key %)])]
                                     (when ident
                                       (assert (om-util/ident? ident)
                                               (str "The values stored in " key " must be idents."))
                                       ;; Replace the :key and :dispatch-key with the
                                       ;; ident we've found, and make them :query-roots.
                                       (assoc %
                                              :key ident
                                              :dispatch-key (first ident)
                                              :query-root true))))))]

    ;; Only include this in the remote query if there are any children.
    (if (seq (:children new-ast))
      (recalculate-query new-ast)
      nil)))


(defn read [{:keys [target] :as env} key params]
  ;; Dispatch to either read-local or read-remote. Remember that, despite the
  ;; fact that a read function can return both a :value and a :remote entry in a
  ;; single map, the parser is actually only looking for one or the other during
  ;; a given call, and the presence or absence of target tells you which it is.
  (case target
    nil {:value (read-local env key params)}
    :remote {:remote (read-remote env key params)}))


(defmulti mutate om-next/dispatch)

;; frontend.routes/set-data sets the :app/route-data during navigation.
(defmethod mutate `routes/set-data
  [{:keys [state route] :as env} key params]
  {:action (fn []
             (let [route-data (cond-> {}
                                (contains? params :organization)
                                (assoc :route-data/organization
                                       [:organization/by-vcs-type-and-name
                                        (select-keys (:organization params)
                                                     [:organization/vcs-type :organization/name])]))]
               (swap! state #(-> %
                                 (assoc :app/route-data route-data)

                                 ;; Clean up the legacy state so it doesn't leak
                                 ;; from the previous page. This goes away when
                                 ;; the legacy state dies. In the Om Next world,
                                 ;; all route data is in :app/route-data, and is
                                 ;; replaced completely on each route change.
                                 (update :legacy/state dissoc
                                         :navigation-point
                                         :navigation-data
                                         :current-build-data
                                         :current-org-data
                                         :current-project-data)))
               (analytics/track {:event-type :pageview
                                 :navigation-point route
                                 :subpage :default
                                 :properties {:user (get-in @state [:app/current-user :user/login])
                                              :view route
                                              :org (get-in params [:organization :organization/name])}})))})

(def parser (compassus/parser {:read read
                               :mutate mutate
                               :route-dispatch false}))
