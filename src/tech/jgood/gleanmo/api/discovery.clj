(ns tech.jgood.gleanmo.api.discovery
  "What an agent reads before it has a token: a setup guide at `/agents`
   (mirrored at `/llms.txt`), an installable Agent Skill, a generated OpenAPI
   document, an unauthenticated index, and an RFC 9727 api-catalog.

   Everything here is public, so it describes only the API's shape: endpoints,
   parameter rules, and the fixed permission groups. Per-field detail stays
   behind auth at `/api/v1/types`, which reflects the token's grants. The
   documents are built from `access/groups` and the limits `tech.jgood.gleanmo.api`
   passes in, so they cannot drift from what the handlers enforce. See
   `.decisions/agent-discovery.json`."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [tech.jgood.gleanmo.api.access :as access]))

(def token-env-var "GLEANMO_TOKEN")
(def url-env-var "GLEANMO_URL")

(def list-param-docs
  "The list endpoint's named query params. `tech.jgood.gleanmo.api` checks
   requests against the same names; a test keeps the two in step."
  [{:name "limit", :type "integer"
    :doc  "Page size, 1 to max-limit (default default-limit)."}
   {:name "offset", :type "integer"
    :doc  "Items to skip. A response with `next-offset` has another page."}
   {:name "order-by", :type "string"
    :doc  "One of the type's own fields to sort by; records with no value for it are left out. Default: newest first by the type's timestamp field, else by creation time."}
   {:name "order", :type "string", :enum ["asc" "desc"]
    :doc  "Sort direction (default desc)."}
   {:name "since", :type "string"
    :doc  "Only items whose order-by value is at or after this. Requires order-by."}
   {:name "q", :type "string"
    :doc  "Case-insensitive substring match over the item's text fields."}
   {:name "include-sensitive", :type "boolean"
    :doc  "Include sensitive items. Needs a token granted sensitive records."}
   {:name "include-archived", :type "boolean"
    :doc  "Include archived items. Needs a token granted archived records."}])

(defn- fill
  "Replace `{placeholders}` in `s` from `m` (string keys)."
  [s m]
  (str/replace s #"\{([a-z-]+)\}" (fn [[whole k]] (str (get m k whole)))))

(defn- api-root
  [base-url]
  (str base-url "/api/v1"))

;; ---------------------------------------------------------------------------
;; Markdown
;; ---------------------------------------------------------------------------

(defn- groups-table
  []
  (str/join
   "\n"
   (concat
    ["| Group | Types | Notes |"
     "|-------|-------|-------|"]
    (for [{:keys [key types read-only]} access/groups]
      (str "| `" (name key) "` | "
           (str/join ", " (map #(str "`" (name %) "`") types)) " | "
           (if read-only "read-only (synced data)" "") " |")))))

(defn- list-params-table
  [{:keys [default-limit max-limit]}]
  (str/join
   "\n"
   (concat
    ["| Param | Meaning |"
     "|-------|---------|"]
    (for [{param :name, :keys [doc]} list-param-docs]
      (str "| `" param "` | "
           (-> doc
               (str/replace "max-limit" (str max-limit))
               (str/replace "default-limit" (str default-limit)))
           " |")))))

(def ^:private usage-template
  "## Calling the API

Every request sends `Authorization: Bearer $GLEANMO_TOKEN`. Responses are JSON.
Read `GLEANMO_TOKEN` from the environment at call time; never print it, and
never paste its value into a command you show the user.

```sh
curl -s -H \"Authorization: Bearer $GLEANMO_TOKEN\" \"$GLEANMO_URL/api/v1/types\"
```

### GET /api/v1/types — start here

The token's grants, and every type it can read with its fields: name, type,
whether it is required and writable, enum `values`, numeric `scale`s,
`relation` targets, and labels. Use these exact field names; do not guess.
Field names are namespaced by type (`task/label`, `task/state`).

### GET /api/v1/entities/{type}

A page of the user's records of one type.

{list-params}

Any other param is an equality filter on one of the type's own fields, e.g.
`?task/state=now` (for a set-valued field, membership). Unknown params are a
400, not ignored. Filter values are plain strings: booleans `true`/`false`,
instants ISO-8601 (`2026-10-15T14:00:00Z`), dates `YYYY-MM-DD`, ids as uuids.

Each item has `id`, `type`, `created-at`, and its own fields by full name.
Sensitive and archived items are left out unless asked for; ask only when the
user wants them.

### GET /api/v1/entities/{type}/{id}

One record. 404 when it does not exist or the token cannot see it.

### POST /api/v1/tx — every write

```json
{\"dry_run\": true,
 \"ops\": [
   {\"op\": \"update\", \"type\": \"task\", \"id\": \"<uuid>\",
    \"set\": {\"task/state\": \"done\"}, \"expect\": {\"task/state\": \"now\"}},
   {\"op\": \"create\", \"type\": \"task\", \"set\": {\"task/label\": \"Call the dentist\"}},
   {\"op\": \"delete\", \"type\": \"task\", \"id\": \"<uuid>\"}]}
```

- Up to {max-ops} ops; the batch is checked whole and written all-or-nothing.
- `set` uses full field names; `null` removes a field. Only fields `/types`
  marks writable are accepted.
- `expect` (optional) names values you read; if any changed since, the op is
  rejected as stale instead of overwriting.
- A create may supply its own `id` (a uuid) so later ops in the same batch can
  relate to it. Deletes are soft.
- `dry_run: true` writes nothing and returns each op's `changes`
  (`{field: {from, to}}`) and `errors`. Without it, a valid batch returns
  `committed: true`; an invalid one returns 422 with per-op errors and writes
  nothing.

### Rules for agents

1. Read `/types` before anything else in a session that will write.
2. Before any write, send the batch with `dry_run: true`, show the user the
   changes in plain language, and commit only what they approve.
3. Put the values you relied on in `expect`, so a record edited elsewhere
   since your read is not silently overwritten.
4. Read in pages; follow `next-offset` instead of raising `limit` to the max.
5. Treat health groups (mood, symptoms, medication, bm) and sensitive records
   as private: use them only for what the user asked.

### Errors

`{\"error\": \"...\", \"details\": [...]}`. 400: the request is malformed and
`details` lists every problem. 401: missing, unknown, or revoked token. 404:
unknown type, a type the token cannot read (the two look the same), or no
such record. 409: a create's id is taken. 422: the batch failed checks; nothing
was written.")

(defn- usage
  [base-url limits]
  (fill usage-template
        {"base"        base-url
         "max-ops"     (:max-ops limits)
         "list-params" (list-params-table limits)}))

(def ^:private guide-template
  "# Gleanmo

> Gleanmo is a personal quantified-self app: tasks and projects, habits,
> exercise, bouldering, meditation, reading, calendar, goals, health logs, and
> synced nutrition and biometrics. This document tells an AI agent how to
> connect the user's Gleanmo account to the agent, and how to use the HTTP API.

- Base URL: {base}
- API root: {base}/api/v1
- This guide: {base}/agents (also {base}/llms.txt)
- Agent Skill: {base}/agents/SKILL.md
- OpenAPI 3.1: {base}/api/v1/openapi.json

## Setup (agents: follow this)

The user wants you to set up a Gleanmo integration with as little effort on
their part as possible. Decide the approach that fits your own harness, tell
the user in a few sentences what you will do and where the token will live,
and wait for their approval. Then walk them through it.

1. **Token.** Only the user can create one. Send them to {base}/app/api-tokens.
   They give it a label and choose none/read/write for each permission group
   (below). Recommend the least access that covers what they want from you:
   read-only unless they want you to change things, and health groups and
   sensitive records left off unless they ask. The token (`gmn_…`) is shown
   once.
2. **Where it goes.** Ask the user to store it themselves as the environment
   variable `{token-env}`, with `{url-env}={base}`, wherever your harness reads
   environment or secrets: a shell profile such as `~/.zshrc`, your harness's
   env or secrets setting, or a secrets manager. Give them the exact line or
   setting for your harness. Do not ask them to paste the token into this
   conversation, never echo or log it, and never write it into a repository.
   If your harness cannot read environment variables or secrets, say so rather
   than working around it.
3. **Remember how.** If your harness supports Agent Skills (`SKILL.md`), save
   {base}/agents/SKILL.md into its user-level skills directory as
   `gleanmo/SKILL.md` (Claude Code: `~/.claude/skills/gleanmo/SKILL.md`).
   Otherwise, add one line to your persistent instructions: \"Gleanmo API: see
   {base}/agents; token in ${token-env}\". Harnesses that import OpenAPI tools can
   use {base}/api/v1/openapi.json instead.
4. **Verify.** Once the user says the token is in place, call
   `GET {base}/api/v1/types` and tell them, in a sentence, which groups you can
   read and write. A 401 means the token is missing, mistyped, or revoked.

## Permission groups

A token is granted groups, not individual types. Your account, calendar feed
URLs, and tokens belong to no group and are never reachable. Read-only groups
hold synced data that the next sync would overwrite, so they cannot be
written whatever the token says.

{groups}

{usage}
")

(defn guide
  "The setup guide and API reference served at `/agents` and `/llms.txt`.
   `limits` carries the API's `:default-limit`, `:max-limit`, `:max-ops`."
  [base-url limits]
  (fill guide-template
        {"base"      base-url
         "token-env" token-env-var
         "url-env"   url-env-var
         "groups"    (groups-table)
         "usage"     (usage base-url limits)}))

(def ^:private skill-template
  "---
name: gleanmo
description: Read and update the user's Gleanmo data (tasks and projects, habits, exercise, bouldering, meditation, reading, calendar, goals, health logs, nutrition, biometrics) through the Gleanmo HTTP API. Use when the user mentions Gleanmo or asks about their own tasks, habits, workouts, or logs tracked there.
---

# Gleanmo

The user's personal quantified-self app at {base}. The token is in
`${token-env}` and the base URL in `${url-env}` (fall back to {base}). If the
token is missing or a call returns 401, send the user to {base}/agents to set
it up again; never ask for the token in chat.

The full reference, with permission groups, is {base}/agents.

{usage}
")

(defn skill
  "An Agent Skills `SKILL.md` for `/agents/SKILL.md`."
  [base-url limits]
  (fill skill-template
        {"base"      base-url
         "token-env" token-env-var
         "url-env"   url-env-var
         "usage"     (usage base-url limits)}))

;; ---------------------------------------------------------------------------
;; Token page prompt
;; ---------------------------------------------------------------------------

(defn setup-prompt
  "A prompt the user pastes into an agent after creating a token. It names the
   token's grants so the agent can check them, and never contains the token."
  [base-url token-doc]
  (let [{:keys [read write sensitive archived]} (access/permissions-summary token-doc)
        read-only (remove (set write) read)]
    (str "Set up my Gleanmo integration. Read " base-url "/agents and follow its "
         "setup steps. I just created a token labelled \""
         (:api-token/label token-doc) "\" with "
         (str/join "; "
                   (cond-> []
                     (seq write)     (conj (str "write: " (str/join ", " write)))
                     (seq read-only) (conj (str "read: " (str/join ", " read-only)))
                     (empty? read)   (conj "no group access")
                     true            (conj (str "sensitive records: "
                                                (if sensitive "granted" "not granted")))
                     true            (conj (str "archived records: "
                                                (if archived "granted" "not granted")))))
         ". I will store the token myself; tell me where.")))

;; ---------------------------------------------------------------------------
;; Machine-readable
;; ---------------------------------------------------------------------------

(defn index
  "The unauthenticated `GET /api/v1` body."
  [base-url]
  {"name"     "Gleanmo agent API"
   "version"  "v1"
   "docs"     (str base-url "/agents")
   "openapi"  (str (api-root base-url) "/openapi.json")
   "auth"     {"type"        "bearer"
               "header"      "Authorization: Bearer gmn_..."
               "obtain"      (str base-url "/app/api-tokens")
               "environment" token-env-var}
   "start"    (str (api-root base-url) "/types")
   "endpoints" ["GET /api/v1/types"
                "GET /api/v1/entities/{type}"
                "GET /api/v1/entities/{type}/{id}"
                "POST /api/v1/tx"]})

(defn- error-response
  [description]
  {"description" description
   "content"     {"application/json"
                  {"schema" {"$ref" "#/components/schemas/Error"}}}})

(def ^:private common-errors
  {"401" (error-response "Missing, unknown, or revoked token.")})

(defn openapi
  "An OpenAPI 3.1 description of `/api/v1`. Field-level schemas per type are
   per-token, so they stay at `/types`; this names the types and operations."
  [base-url {:keys [default-limit max-limit max-ops]}]
  (let [type-names (mapv name (mapcat :types access/groups))
        json-ok    (fn [description schema]
                     {"description" description
                      "content"     {"application/json" {"schema" schema}}})]
    {"openapi"      "3.1.0"
     "info"         {"title"       "Gleanmo agent API"
                     "version"     "1"
                     "description" (str "Bearer-token JSON API over a user's Gleanmo data. "
                                        "Agents: read " base-url "/agents first; it explains "
                                        "token setup, field names, and the dry-run-then-commit "
                                        "write flow.")}
     "externalDocs" {"description" "Agent setup guide"
                     "url"         (str base-url "/agents")}
     "servers"      [{"url" (api-root base-url)}]
     "security"     [{"bearer" []}]
     "paths"
     {"/types"
      {"get" {"operationId" "getTypes"
              "summary"     "The token's grants and every readable type's fields. Call first."
              "responses"   (merge {"200" (json-ok "Grants and field descriptions."
                                                   {"type" "object"})}
                                   common-errors)}}

      "/entities/{type}"
      {"get" {"operationId" "listEntities"
              "summary"     "A page of the user's records of one type."
              "parameters"
              (into [{"$ref" "#/components/parameters/type"}]
                    (concat
                     (for [{param :name, :keys [type doc enum]} list-param-docs]
                       {"name"        param
                        "in"          "query"
                        "required"    false
                        "description" (-> doc
                                          (str/replace "max-limit" (str max-limit))
                                          (str/replace "default-limit" (str default-limit)))
                        "schema"      (cond-> {"type" type}
                                        enum (assoc "enum" enum)
                                        (= "limit" param)
                                        (assoc "minimum" 1
                                               "maximum" max-limit
                                               "default" default-limit))})
                     [{"name"        "filters"
                       "in"          "query"
                       "required"    false
                       "style"       "form"
                       "explode"     true
                       "description" "Equality filters on the type's own fields, e.g. task/state=now."
                       "schema"      {"type"                 "object"
                                      "additionalProperties" {"type" "string"}}}]))
              "responses"
              (merge {"200" (json-ok "A page of records."
                                     {"type"       "object"
                                      "properties" {"type"        {"type" "string"}
                                                    "limit"       {"type" "integer"}
                                                    "offset"      {"type" "integer"}
                                                    "count"       {"type" "integer"}
                                                    "next-offset" {"type" "integer"}
                                                    "items"       {"type"  "array"
                                                                   "items" {"$ref" "#/components/schemas/Entity"}}}})
                      "400" (error-response "Invalid params; details lists each.")
                      "404" (error-response "Unknown type, or the token cannot read it.")}
                     common-errors)}}

      "/entities/{type}/{id}"
      {"get" {"operationId" "getEntity"
              "summary"     "One record by id."
              "parameters"  [{"$ref" "#/components/parameters/type"}
                             {"name" "id", "in" "path", "required" true
                              "schema" {"type" "string", "format" "uuid"}}]
              "responses"   (merge {"200" (json-ok "The record."
                                                   {"$ref" "#/components/schemas/Entity"})
                                    "404" (error-response "Not found or not visible to the token.")}
                                   common-errors)}}

      "/tx"
      {"post" {"operationId" "applyTx"
               "summary"     (str "Check a batch of up to " max-ops " create/update/delete ops "
                                  "and write all or none. Send dry_run first and commit "
                                  "only what the user approves.")
               "requestBody" {"required" true
                              "content"  {"application/json"
                                          {"schema" {"$ref" "#/components/schemas/TxRequest"}}}}
               "responses"   (merge {"200" (json-ok "Dry-run plan, or the committed batch."
                                                    {"$ref" "#/components/schemas/TxResult"})
                                     "400" (error-response "Malformed body.")
                                     "409" (error-response "A create's id is already in use.")
                                     "422" (json-ok "The batch failed checks; nothing was written."
                                                    {"$ref" "#/components/schemas/TxResult"})}
                                    common-errors)}}}

     "components"
     {"securitySchemes" {"bearer" {"type"         "http"
                                   "scheme"       "bearer"
                                   "bearerFormat" "gmn_ opaque token"
                                   "description"  (str "Created by the user at " base-url
                                                       "/app/api-tokens.")}}
      "parameters"      {"type" {"name"     "type"
                                 "in"       "path"
                                 "required" true
                                 "schema"   {"type" "string", "enum" type-names}}}
      "schemas"
      {"Error"    {"type"       "object"
                   "required"   ["error"]
                   "properties" {"error"   {"type" "string"}
                                 "details" {}}}
       "Entity"   {"type"                 "object"
                   "required"             ["id" "type"]
                   "properties"           {"id"         {"type" "string", "format" "uuid"}
                                           "type"       {"type" "string"}
                                           "created-at" {"type" "string", "format" "date-time"}}
                   "additionalProperties" true
                   "description"          "Own fields appear under their full names (task/label); see /types."}
       "Op"       {"type"       "object"
                   "required"   ["op" "type"]
                   "properties" {"op"     {"type" "string", "enum" ["create" "update" "delete"]}
                                 "type"   {"type" "string", "enum" type-names}
                                 "id"     {"type" "string", "format" "uuid"}
                                 "set"    {"type"        "object"
                                           "description" "Full field names; null removes a field."}
                                 "expect" {"type"        "object"
                                           "description" "Values that must still hold, or the op is stale."}}}
       "TxRequest" {"type"       "object"
                    "required"   ["ops"]
                    "properties" {"dry_run" {"type" "boolean"}
                                  "ops"     {"type"     "array"
                                             "minItems" 1
                                             "maxItems" max-ops
                                             "items"    {"$ref" "#/components/schemas/Op"}}}}
       "TxResult" {"type"       "object"
                   "properties" {"dry_run"   {"type" "boolean"}
                                 "committed" {"type" "boolean"}
                                 "valid"     {"type" "boolean"}
                                 "ops"       {"type"  "array"
                                              "items" {"type"       "object"
                                                       "properties" {"index"   {"type" "integer"}
                                                                     "op"      {"type" "string"}
                                                                     "type"    {"type" "string"}
                                                                     "id"      {"type" ["string" "null"]}
                                                                     "errors"  {"type"  "array"
                                                                                "items" {"type" "string"}}
                                                                     "changes" {"type" "object"}
                                                                     "before"  {"type" "object"}}}}}}}}}))

(defn api-catalog
  "RFC 9727 `/.well-known/api-catalog` linkset."
  [base-url]
  {"linkset"
   [{"anchor"       (api-root base-url)
     "service-desc" [{"href" (str (api-root base-url) "/openapi.json")
                      "type" "application/vnd.oai.openapi+json;version=3.1"}]
     "service-doc"  [{"href" (str base-url "/agents")
                      "type" "text/plain"}]}]})

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(def ^:private public-headers
  ;; Nothing here is per-user, so any origin may read it (browser-based agents).
  {"Access-Control-Allow-Origin" "*"
   "Cache-Control"               "public, max-age=300"})

(defn- text
  [body]
  {:status  200
   :headers (assoc public-headers "Content-Type" "text/plain; charset=utf-8")
   :body    body})

(defn- base-url
  [ctx]
  (str/replace (str (:biff/base-url ctx)) #"/+$" ""))

(defn guide-handler
  [limits]
  (fn [ctx] (text (guide (base-url ctx) limits))))

(defn skill-handler
  [limits]
  (fn [ctx] (text (skill (base-url ctx) limits))))

(defn index-handler
  [ctx]
  {:status 200, :headers public-headers, :body (index (base-url ctx))})

(defn openapi-handler
  [limits]
  (fn [ctx]
    {:status 200, :headers public-headers, :body (openapi (base-url ctx) limits)}))

(defn api-catalog-handler
  [ctx]
  {:status  200
   :headers (assoc public-headers
                   "Content-Type" "application/linkset+json; charset=utf-8")
   :body    (json/generate-string (api-catalog (base-url ctx)))})

(defn routes
  "Public discovery routes, given the API's `limits`."
  [limits]
  [["/agents" {:get (guide-handler limits)}]
   ["/agents/SKILL.md" {:get (skill-handler limits)}]
   ["/llms.txt" {:get (guide-handler limits)}]
   ["/.well-known/api-catalog" {:get api-catalog-handler}]])
