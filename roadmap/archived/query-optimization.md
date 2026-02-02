---
title: "Query Optimization: Unbatched Related Entity Lookups"
status: done
description: "Batched related entity lookups - 93% query reduction"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Query Optimization: Unbatched Related Entity Lookups

## Work Unit Summary
- Problem / intent: `all-for-user-query` makes excessive database queries due to unbatched related entity lookups, causing slow page loads.
- Constraints: Must not regress existing functionality; changes should be validated by existing tests plus new query-count test.
- Proposed approach: Batch all related entity lookups into a single query, use lookup map for filtering.
- Open questions: None currently.

## Problem Analysis

The `should-remove-related-entity` function in `db/queries.clj:76-103` queries each related entity individually:

```clojure
;; Current: N * M * K individual queries
(let [related-entities (mapv
                        (fn [id]
                          (first (q db
                                    {:find  '(pull ?e [*]),
                                     :where [['?e :xt/id id]],
                                     :in    '[id]}
                                    id)))
                        (vec related-ids))]
  ...)
```

**Query count formula:** `1 (main) + N × M × K`
- N = number of entities returned
- M = number of relationship fields per entity
- K = average related IDs per field

**Observed:** 5 entities × 3 fields × ~2 IDs = 31 total queries (should be ~3-5)

## Progress

### Completed
- [x] Added `tortue/spy` library to `deps.edn` for test spying
- [x] Created `query-count-with-relationship-filtering-test` in `queries_test.clj`
- [x] Documented baseline: 31 queries for 5 entities with relationship filtering
- [x] Updated AGENTS.md with Clojure editing best practices
- [x] Implemented batched lookup with `collect-related-ids` and `batch-fetch-entities`
- [x] Updated `should-remove-related-entity` to use lookup map instead of querying
- [x] Test now asserts query count <= 5 (down from 31)
- [x] All 9 functional tests pass (65 assertions, 0 failures)

### Results
- **Before:** 31 queries for 5 entities with 3 relationship fields
- **After:** 2 queries (93% reduction)

**Complexity analysis:**
- **Query/network round-trips:** O(N × M × K) → O(1). This is the key win - each round-trip incurs network latency (~10-50ms), so eliminating 29 round-trips saves ~300-600ms.
- **Data processing:** Still O(n) - we process the same entities in memory, but that's fast compared to network I/O.

## Test Validation

The `query-count-with-relationship-filtering-test` uses `tortue/spy` to count `biff/q` calls:

```clojure
(with-redefs [biff/q (spy/spy biff/q)]
  (let [entities (queries/all-entities-for-user ...)
        query-count (spy/call-count biff/q)]
    ;; Currently observes ~31 queries
    ;; After fix, should be < 10
    (is (< query-count 10))))
```

## Proposed Fix

Replace individual lookups with batch query:

```clojure
;; Step 1: Collect all related IDs across all entities
(defn- collect-all-related-ids [entities relationship-fields]
  (into #{}
    (for [entity entities
          {:keys [field-key input-type]} relationship-fields
          :let [ids (if (= input-type :many-relationship)
                      (get entity field-key)
                      #{(get entity field-key)})]
          id ids
          :when id]
      id)))

;; Step 2: Batch fetch all related entities
(defn- batch-fetch-entities [db ids]
  (when (seq ids)
    (let [results (q db
                     {:find '[(pull ?e [*])]
                      :where '[[?e :xt/id ?id]]
                      :in '[?ids]}
                     ids)]
      (into {} (map (fn [e] [(:xt/id e) e]) (map first results))))))

;; Step 3: Use lookup map in should-remove-related-entity
```

## Files Involved

- `src/tech/jgood/gleanmo/db/queries.clj` - Main fix location
- `test/tech/jgood/gleanmo/test/db/queries_test.clj` - Test validation
- `deps.edn` - Added tortue/spy dependency
