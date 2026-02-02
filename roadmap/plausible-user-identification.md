---
title: "Plausible Analytics User Identification"
status: idea
description: "Add user identifiers to Plausible analytics to distinguish individuals"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Plausible Analytics User Identification

## Work Unit Summary
- Problem / intent: Add user identifiers to Plausible analytics to distinguish different individuals and understand user-specific behavior patterns.
- Constraints: Must respect user privacy settings and comply with Plausible's privacy-focused approach; should not expose sensitive user data.
- Proposed approach: Use Plausible's custom properties API to send a user identifier hash with pageview and custom events.
- Open questions: What user identifier to use (email hash, UUID, or custom ID)? Should this be opt-in for privacy-conscious users?

## Notes

### Current State
- Plausible integration exists at `src/tech/jgood/gleanmo/ui.clj:49`
- Currently tracks pageviews with domain-based filtering only (`data-domain="gleanmo.com"`)
- No user-specific segmentation or identification

### Plausible Custom Properties

Plausible supports custom properties for events:
```javascript
// Pageview with custom properties
plausible('pageview', { props: { userId: 'abc-123', userType: 'paid' } });

// Custom event
plausible('Task Created', { props: { userId: 'abc-123', taskType: 'habit' } });
```

### Implementation Options

#### Option 1: User Email Hash (Privacy-Focused)
```clojure
(defn user-id-for-analytics [ctx]
  (let [user-id (:uid (:session ctx))]
    (sha256 user-id)))  ; Hash user ID before sending
```

**Pros:**
- Privacy-preserving (hashed identifier)
- Allows distinguishing users without exposing emails
- Consistent with Plausible's privacy philosophy

**Cons:**
- Cannot correlate with other systems
- Hash collisions possible (unlikely with SHA-256)

#### Option 2: Persistent Anonymous ID
```clojure
(defn generate-or-get-analytics-id [ctx]
  (or (get-in ctx [:session :analytics-id])
      (let [new-id (random-uuid)]
        ;; Persist to session
        new-id)))
```

**Pros:**
- Anonymous by design
- Persistent across sessions
- No PII concerns

**Cons:**
- Lost if user clears cookies/session
- Cannot link to actual user account

#### Option 3: User UUID (Direct)
```clojure
(defn user-id-for-analytics [ctx]
  (:uid (:session ctx)))
```

**Pros:**
- Simple implementation
- Correlates directly with user records

**Cons:**
- Exposes internal user IDs
- Privacy concern for sensitive user

### Proposed Approach

Use **Option 1 (User ID Hash)** as default with opt-out option:

1. **Client-side setup:**
   - Store hashed user ID in a JavaScript variable accessible to Plausible
   - Add `data-pageview-props` attribute to Plausible script tag
   - Create helper functions for custom events

2. **Server-side implementation:**
   - Generate SHA-256 hash of user ID in middleware or session setup
   - Pass hashed ID to frontend via page context
   - Respect user's analytics consent preference (user setting)

3. **Privacy controls:**
   - Add user setting: "Share anonymous usage data"
   - If disabled, don't send any user identifier to Plausible
   - Respects existing `:user/sensitive` pattern

### Integration Points

#### Pageviews with User ID
```clojure
;; In ui.clj base function
(let [user-props (when (and (user-consents-to-analytics? ctx)
                           (:uid (:session ctx)))
                   {:userId (hash-user-id (:uid (:session ctx)))})]
  [:script {:defer true
            :data-domain "gleanmo.com"
            :data-pageview-props (cheshire/generate-string user-props)
            :src "https://plausible.io/js/script.js"}])
```

#### Custom Events for Key Actions
```clojure
;; Track entity creation
(plausible('Entity Created', {
  props: {
    userId: 'hashed-id',
    entityType: 'habit',
    hasNotes: true
  }
}))
```

### User Setting

Add to user schema:
```clojure
[:user/anonymous-analytics {:optional true :default false} :boolean]
```

Add to user settings UI (e.g., alongside "Show BM logs" toggle):
```
[ ] Share anonymous usage data to improve product
```

### Benefits

1. **User segmentation:** See usage patterns by user type (paid vs free, heavy vs light users)
2. **Feature adoption:** Track which users use specific features
3. **Retention analysis:** Identify users who stop using the system
4. **Performance monitoring:** Track if issues affect specific users
5. **A/B testing readiness:** Enable user-based experiments

### Considerations

1. **GDPR/Privacy:** Hashed identifiers are still personal data; require user consent
2. **Data minimization:** Only send what's needed; avoid sending sensitive fields
3. **Session boundary:** User ID persists until session expiration
4. **Multi-user scenario:** Currently single-tenant; future-proof for multi-user

### Success Metrics

1. User consent rate for analytics participation
2. Ability to distinguish at least 80% of users in Plausible
3. Zero increase in reported privacy concerns
4. Performance impact <10ms per page load

### References

- Plausible Custom Properties: https://plausible.io/docs/custom-props
- Plausible Events API: https://plausible.io/docs/custom-event-goals
- GDPR guidance on hashed identifiers
