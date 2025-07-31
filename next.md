# Sensitive Data Display User Setting

## Current Implementation Analysis

The current sensitive data filtering mechanism uses query parameters to determine whether to show entities with `sensitive: true` attributes:

- **Schema Implementation**: Several schemas include a `sensitive` boolean field (habits, locations)
- **Query Parameter Logic**: `db/queries.clj:121-123` uses `(param-true? (:sensitive params))` to determine filtering
- **Filtering Logic**: `all-entities-for-user` removes entities where `sensitive-key` is true when `filter-sensitive` is false

## Specification

### Goal
Replace query parameter-based sensitive data filtering with a persistent user setting that:
1. Defaults to hiding sensitive items (`show-sensitive: false`)
2. Can be toggled to show sensitive items
3. When enabled, provides a toggle to quickly turn off sensitive display
4. Toggle to enable is buried in a settings dialog

### User Experience Flow

1. **Default State**: Sensitive items are hidden, no indication they exist
2. **Settings Access**: User opens buried settings dialog (in user profile)
3. **Enable Sensitive**: User toggles "Show Sensitive Items" setting
4. **Active State**: 
   - Sensitive items now visible in all CRUD views
   - Quick toggle appears in navigation/header to temporarily hide
5. **Quick Hide**: User can use quick toggle to temporarily hide without going back to settings

### Technical Requirements

#### User Settings Schema
- Create new `user-settings` entity with relationship to user
- Fields: `user/id` (foreign key), `user-settings/show-sensitive` boolean
- No default value needed - absence means false (hide sensitive)

#### Database Query Updates
- Modify `all-for-user-query` to lookup user settings instead of query parameters
- Remove dependency on `:sensitive` query parameter
- Default to false when no settings entity exists (secure by default)

#### UI Components
- **Settings Dialog**: Add toggle in user edit form (buried/advanced section)
- **Quick Toggle**: When sensitive is enabled, show quick hide/show toggle in appropriate views
- **Visual Indicators**: Optionally mark sensitive items with visual cues when displayed

## Implementation Plan

### Phase 1: User Settings Schema & UI
1. **Create user settings schema** (`user_settings_schema.clj`)
   - Create new schema file with user-settings entity
   - Fields: `xt/id`, `user/id`, `user-settings/show-sensitive`, meta fields

2. **Update user edit form** (`app/user.clj`)
   - Add sensitive data toggle in settings section
   - Handle form submission to create/update user-settings entity
   - Lookup existing user-settings for current values

### Phase 2: Query Logic Updates  
3. **Modify database queries** (`db/queries.clj`)
   - Update `all-for-user-query` to lookup user-settings entity
   - Add helper function to get user's show-sensitive setting (defaults to false)
   - Remove `:sensitive` parameter dependency

4. **Update CRUD views** (`crud/views.clj`)
   - Remove any query parameter handling for sensitive
   - Ensure views work with new user-setting-based approach

### Phase 3: Quick Toggle Feature (Optional Enhancement)
5. **Add quick toggle component**
   - Create reusable toggle component for header/navigation
   - Implement temporary session-based override
   - Add visual indicators for sensitive items when displayed that can also be used to disable sensitive toggle

### Phase 4: Testing & Validation
6. **Integration testing**
   - Verify all CRUD views respect user setting
   - Test edge cases (user without settings entity)
   - Verify secure default behavior (no migration needed)

7. **Performance testing**
   - Test query performance impact of settings lookup
   - Ensure graceful handling of missing settings

## Files to Modify

### Core Implementation
- `src/tech/jgood/gleanmo/schema/user_settings_schema.clj` - New schema file for settings entity
- `src/tech/jgood/gleanmo/schema.clj` - Register new schema
- `src/tech/jgood/gleanmo/app/user.clj` - Update edit form and handler for settings
- `src/tech/jgood/gleanmo/db/queries.clj` - Replace param logic with settings lookup

### Potentially Affected
- `src/tech/jgood/gleanmo/crud/views.clj` - Remove query param dependencies
- `src/tech/jgood/gleanmo/app/shared.clj` - Quick toggle component (if implemented)

## Implementation Strategy

1. **Create Settings Schema**: Add new user-settings entity and schema
2. **Update UI**: Add settings toggle to user edit form
3. **Switch Query Logic**: Replace param-based logic with settings lookup
4. **Cleanup**: Remove query parameter support
5. **No Migration Needed**: Missing settings default to false (secure)

## Benefits

- **Persistent Preference**: User choice survives sessions
- **Better UX**: No need to remember query parameters
- **Security**: Sensitive data hidden by default, no migration needed
- **Clean Architecture**: Separate settings entity follows domain separation
- **Extensible**: Easy to add more user preferences in future
- **Discoverability**: Settings are intentionally buried but accessible
- **Quick Access**: Optional quick toggle for power users
