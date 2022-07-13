(ns metabase.actions
  "Code related to the new writeback Actions."
  (:require
   [clojure.spec.alpha :as s]
   [metabase.api.common :as api]
   [metabase.driver :as driver]
   [metabase.mbql.normalize :as mbql.normalize]
   [metabase.mbql.schema :as mbql.s]
   [metabase.models.database :refer [Database]]
   [metabase.models.setting :as setting]
   [metabase.util :as u]
   [metabase.util.i18n :as i18n]
   [schema.core :as schema]
   [metabase.query-processor.middleware.normalize-query :as normalize]))

(setting/defsetting experimental-enable-actions
  (i18n/deferred-tru "Whether to enable using the new experimental Actions features globally. (Actions must also be enabled for each Database.)")
  :default false
  :type :boolean
  :visibility :public)

(setting/defsetting database-enable-actions
  (i18n/deferred-tru "Whether to enable using the new experimental Actions for a specific Database.")
  :default false
  :type :boolean
  :visibility :public
  :database-local :only)

(defn +check-actions-enabled
  "Ring middleware that checks that the [[metabase.actions/experimental-enable-actions]] feature flag is enabled, and
  returns a 403 Unauthorized response "
  [handler]
  (fn [request respond raise]
    (if (experimental-enable-actions)
      (handler request respond raise)
      (raise (ex-info (i18n/tru "Actions are not enabled.")
                      {:status-code 400})))))

(defmulti normalize-action-arg-map
  "Normalize the `arg-map` passed to [[perform-action!]] for a specific `action`."
  {:arglists '([action arg-map])}
  (fn [action _arg-map]
    (keyword action)))

(defmethod normalize-action-arg-map :default
  [_action arg-map]
  arg-map)

(defmulti action-arg-map-spec
  "Return the appropriate spec to use to validate the arg map passed to [[perform-action!*]].

    (action-arg-map-spec :row/create) => :actions.args.crud/row.create"
  {:arglists '([action])}
  keyword)

(defmethod action-arg-map-spec :default
  [_action]
  any?)

(defmulti perform-action!*
  "Multimethod for doing an Action. The specific `action` is a keyword like `:row/create` or `:bulk/create`; the shape
  of `arg-map` depends on the action being performed. [[action-arg-map-spec]] returns the appropriate spec to use to
  validate the args for a given action. When implementing a new action type, be sure to implement both this method
  and [[action-arg-map-spec]].

  At the time of this writing Actions are performed with either `POST /api/action/:action-namespace/:action-name`,
  which passes in the request body as `args-map` directly, or `POST
  /api/action/:action-namespace/:action-name/:table-id`, which passes in an `args-map` like

    {:table-id <table-id>, :arg <request-body>}

  The former endpoint is currently used for the various `:row/*` Actions while the version with `:table-id` as part of
  the route is currently used for `:bulk/*` Actions.

  DON'T CALL THIS METHOD DIRECTLY TO PERFORM ACTIONS -- use [[perform-action!]] instead which does normalization,
  validation, and binds Database-local values."
  {:arglists '([driver action database arg-map])}
  (fn [driver action _database _arg-map]
    [(driver/dispatch-on-initialized-driver driver)
     (keyword action)])
  :hierarchy #'driver/hierarchy)

(defn- known-actions
  "Set of all known actions."
  []
  (into #{}
        (comp (filter sequential?)
              (map second))
        (keys (methods perform-action!*))))

(defmethod perform-action!* :default
  [driver action _arg-map]
  (let [action        (keyword action)
        known-actions (known-actions)]
    ;; return 404 if the action doesn't exist.
    (when-not (contains? known-actions action)
      (throw (ex-info (i18n/tru "Unknown Action {0}. Valid Actions are: {1}"
                                action
                                (pr-str known-actions))
                      {:status-code 404})))
    ;; return 400 if the action does exist but is not supported by this DB
    (throw (ex-info (i18n/tru "Action {0} is not supported for {1} Databases."
                              action
                              (pr-str driver))
                    {:status-code 400}))))

(defn perform-action!
  "Perform an `action`. Invoke this function for performing actions, e.g. in API endpoints;
  implement [[perform-action!*]] to add support for a new driver/action combo. The shape of `arg-map` depends on the
  `action` being performed. [[action-arg-map-spec]] returns the specific spec used to validate `arg-map` for a given
  `action`."
  [action arg-map]
  ;; Validate the arg map.
  (let [action  (keyword action)
        spec    (action-arg-map-spec action)
        arg-map (normalize-action-arg-map arg-map)]
    (when (s/invalid? (s/conform spec arg-map))
      (throw (ex-info (format "Invalid Action arg map: %s" (s/explain-str spec arg-map))
                      (s/explain-data spec arg-map))))
    ;; Check that Actions are enabled globally.
    (when-not (experimental-enable-actions)
      (throw (ex-info (i18n/tru "Actions are not enabled.")
                      {:status-code 400})))
    ;; Check that Actions are enabled for this specific Database.
    (let [{database-id :database}                         arg-map
          {db-settings :settings, driver :engine, :as db} (Database database-id)]
      ;; make sure the Driver supports Actions.
      (when-not (driver/database-supports? driver :actions db)
        (throw (ex-info (i18n/tru "{0} Database {1} does not support actions."
                                  (u/qualified-name driver)
                                  (format "%d %s" (:id db) (pr-str (:name db))))
                        {:status-code 400, :database-id (:id db)})))
      ;; bind Database-local Settings for this Database
      (binding [setting/*database-local-values* db-settings]
        ;; make sure Actions are enabled for this Database
        (when-not (database-enable-actions)
          (throw (ex-info (i18n/tru "Actions are not enabled for Database {0}." database-id)
                          {:status-code 400})))
        ;; TODO -- need to check permissions once we have Actions-specific perms in place. For now just make sure the
        ;; current User is an admin. This check is only done if [[api/*current-user*]] is bound (which will always be
        ;; the case when invoked from an API endpoint) to make Actions testable separately from the API endpoints.
        (when api/*current-user*
          (api/check-superuser))
        ;; Ok, now we can hand off to [[perform-action!*]]
        (perform-action!* driver action db arg-map)))))

;;;; Action definitions.

;;; Common base spec for all Actions. All Actions at least require
;;;
;;;    {:database <id>, :query {:source-table <id>}}

(s/def :actions.args/id
  (s/and integer? pos?))

(s/def :actions.args.crud.common/database
  :actions.args/id)

(s/def :actions.args.crud.common.query/source-table
  :actions.args/id)

(s/def :actions.args.crud.common/query
  (s/keys :req-un [:actions.args.crud.common.query/source-table]))

(s/def :actions.args.crud/common
  (s/keys :req-un [:actions.args.crud.common/database
                   :actions.args.crud.common/query]))

;;; the various `:row/*` Actions all treat their args map as an MBQL query.

(defn- normalize-as-mbql-query [query]
  (let [query (assoc (mbql.normalize/normalize query)
                     :type :query)]
    (try
      (schema/validate mbql.s/Query query)
      (catch Exception e
        (throw (ex-info
                (ex-message e)
                {:exception-data (ex-data e)
                 :status-code    400}))))
    query))

;;;; `:row/create`

;;; row/create requires at least
;;;
;;;    {:database   <id>
;;;     :query      {:source-table <id>, :filter <mbql-filter-clause>}
;;;     :create-row <map>}

(defmethod normalize-action-arg-map :row/create
  [query]
  (normalize-as-mbql-query query))

(s/def :actions.args.crud.row.create/create-row
  (s/map-of keyword? any?))

(s/def :actions.args.crud/row.create
  (s/merge
   :actions.args.crud/common
   (s/keys :req-un [:actions.args.crud.row.create/create-row])))

(defmethod action-arg-map-spec :row/create
  [_action]
  :actions.args.crud/row.create)

;;;; `:row/update`

;;; row/update requires at least
;;;
;;;    {:database   <id>
;;;     :query      {:source-table <id>, :filter <mbql-filter-clause>}
;;;     :update-row <map>}

(defmethod normalize-action-arg-map :row/update
  [query]
  (normalize-as-mbql-query query))

(s/def :actions.args.crud.row.update.query/filter
  vector?) ; MBQL filter clause

(s/def :actions.args.crud.row.update/query
  (s/merge
   :actions.args.crud.common/query
   (s/keys :req-un [:actions.args.crud.row.update.query/filter])))

(s/def :actions.args.crud.row.update/update-row
  (s/map-of keyword? any?))

(s/def :actions.args.crud/row.update
  (s/merge
   :actions.args.crud/common
   (s/keys :req-un [:actions.args.crud.row.update/update-row
                    :actions.args.crud.row.update/query])))

(defmethod action-arg-map-spec :row/update
  [_action]
  :actions.args.crud/row.update)

;;;; `:row/delete`

;;; row/delete requires at least
;;;
;;;    {:database <id>
;;;     :query    {:source-table <id>, :filter <mbql-filter-clause>}}

(defmethod normalize-action-arg-map :row/delete
  [query]
  (normalize-as-mbql-query query))

(s/def :actions.args.crud.row.delete.query/filter
  vector?) ; MBQL filter clause

(s/def :actions.args.crud.row.delete/query
  (s/merge
   :actions.args.crud.common/query
   (s/keys :req-un [:actions.args.crud.row.delete.query/filter])))

(s/def :actions.args.crud/row.delete
  (s/merge
   :actions.args.crud/common
   (s/keys :req-un [:actions.args.crud.row.delete/query])))

(defmethod action-arg-map-spec :row/delete
  [_action]
  :actions.args.crud/row.delete)

(s/def :actions.args.crud/bulk.delete
  (s/coll-of :actions.args.crud/row.delete))

(defmethod action-arg-map-spec :bulk/delete
  [_action]
  :actions.args.crud/bulk.delete)

{:url "/bulk/delete/3"
 :body [[:= [:field 51 nil] 1]
        [:= [:field 51 nil] 2]]}


;; (s/explain-data :actions.args.crud/row.delete
;;                 {:database 2
;;                  :type :query
;;                  :query {:source-table 29
;;                          :filter [:= [:field 51 nil] 1]}})

;; (s/explain-data :actions.args.crud/bulk.delete
;;                [{:database 2
;;                  :type :query
;;                  :query {:source-table 29
;;                          :filter [:= [:field 51 nil] 1]}}
;;                 {:database 2
;;                  :type :query
;;                  :query {:source-table 29
;;                          :filter [:= [:field 51 nil] 2]}}])
