# Meditation Timer Issues & Fixes

## Issue 1: Form Pre-population Bug

**Problem**: Creating a timer for a specific meditation doesn't prefill the meditation selection in the form.

**Root Cause**: The `start-timer-card` function in `timer/routes.clj` (lines 91-94) constructs the query parameter incorrectly:

```clojure
"/new?"       entity-str                    ; "meditation-log"
"/"           (name parent-entity-key)      ; "meditation"
"-id="        (:xt/id parent)
```

This generates: `/app/crud/form/meditation-log/new?meditation-log/meditation-id=<uuid>`

But the actual field in the schema is `:meditation-log/type-id`, not `:meditation-log/meditation-id`.

The schema declares: `{:timer/primary-rel :meditation-log/type-id}` but the code doesn't use this metadata properly.

**Fix**: Use the `relationship-key` from config instead of constructing it:

```clojure
; In start-timer-card function (line 79-101)
(defn start-timer-card
  "Render a start button for a parent entity."
  [parent {:keys [entity-str relationship-key]}]  ; Add relationship-key
  (let [label-key (schema-utils/entity-attr-key parent-entity-key "label")
        notes-key (schema-utils/entity-attr-key parent-entity-key "notes")]
    [:div.bg-dark-surface.rounded-lg.p-4.border.border-dark...
     ...
     [:a.bg-neon-yellow...
      {:href (str "/app/crud/form/" entity-str
                  "/new?"
                  (namespace relationship-key) "/"  ; Use actual field namespace
                  (name relationship-key) "="       ; Use actual field name
                  (:xt/id parent)
                  "&" entity-str "/beginning=" (java.net.URLEncoder/encode (str (t/now)) "UTF-8")
                  "&redirect=" (java.net.URLEncoder/encode (str "/app/timer/" entity-str) "UTF-8"))}
      "Start Timer"]]))
```

The `relationship-key` is already correctly resolved by `infer-primary-rel` using the `:timer/primary-rel` metadata, so we just need to use it.

## Issue 2: Timer Labels Outside Card

**Problem**: Timer labels are rendering outside the card container.

**Root Cause**: Likely CSS issue with the card structure in `active-timer-card` function (lines 103-135).

**Investigation Needed**:
1. Check the DOM structure when a timer is active
2. Look for missing container divs or incorrect flexbox layout
3. Check for conflicting Tailwind classes

**Possible Fix Areas**:
- Line 117-124: The card's inner structure may need adjustment
- The heading "Active Timers" (line 158) may be positioned incorrectly
- The "Start Timer" heading (line 165) may have similar issues

**Suggested Fix**: Need to inspect the actual rendered HTML to see which labels are escaping. Most likely the section headers (h2 elements) need to be inside their respective container divs.

Check if this is the issue:
```clojure
; Line 151-162 in timer-page
[:div.mb-8
 {:id "active-timers-section"
  :hx-get (str "/app/timer/" entity-str "/active")
  :hx-trigger "every 30s"
  :hx-swap "outerHTML"}
 (when (seq active-timers)
   [:div
    [:h2.text-xl.font-semibold.mb-4.text-neon-cyan "Active Timers"]  ; This h2 needs to be inside a container
    [:div.space-y-4
     (for [timer active-timers]
       ^{:key (:xt/id timer)}
       (active-timer-card timer parents ctx config))]])]
```

The h2 should probably be outside the conditional or wrapped in a proper container div with padding.

## Testing Plan

1. **Pre-population fix**:
   - Navigate to `/app/timer/meditation-log`
   - Click "Start Timer" on a meditation
   - Verify the form has the meditation pre-selected in the type-id dropdown
   - Submit and verify the timer appears with correct meditation name

2. **Visual fix**:
   - Start a meditation timer
   - Inspect the page layout
   - Verify "Active Timers" and "Start Timer" headings are properly positioned inside their containers
   - Check responsive behavior on mobile viewport
