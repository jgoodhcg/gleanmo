# Gleanmo Development Backlog

## 1. Consolidate User Settings into User Entity

**Goal**: Eliminate the separate `user-settings` entity and move all settings fields directly onto the user entity for simplicity.

**Current Problem**: The current implementation has a separate `user-settings` entity that creates unnecessary complexity with foreign key relationships, separate queries, and split form handling. Settings like `show-sensitive`, future `show-archived`, and `time-zone` should all be simple fields on the user entity.

**Changes Required**:

1. **Update User Schema** (`user_schema.clj`):
   - Add `:user/show-sensitive {:optional true} :boolean` field
   - Add `:user/show-archived {:optional true} :boolean` field (prep for future)
   - Keep existing `:user/time-zone` field

2. **Remove User Settings Schema** (`user_settings_schema.clj`):
   - Delete the entire file
   - Remove from schema registry

3. **Update Database Functions** (`db/queries.clj`):
   - Replace `get-show-sensitive-setting` to read directly from user entity
   - Remove `get-user-settings` function (no longer needed)
   - Update `all-for-user-query` to use user fields instead of settings lookup

4. **Update Mutations** (`db/mutations.clj`):
   - Remove `upsert-user-settings!` function
   - Update `update-user!` to handle all user fields including settings

5. **Update User Controller** (`app/user.clj`):
   - Simplify `edit!` to only call `update-user!` with all fields
   - Remove separate settings handling
   - Update `turn-off-sensitive!` to directly update user entity

6. **Update App Routes** (`app.clj`):
   - Keep the turn-off-sensitive endpoint but simplify the handler

**Benefits**:
- **Simpler**: No foreign key relationships, no separate entity management
- **Consistent**: All user data in one place
- **Fewer queries**: Single user lookup gets all settings
- **Less code**: Eliminates entire settings layer
- **Better performance**: No joins or multiple queries needed
- **Easier to reason about**: User settings are just user fields

**Migration Strategy**:
- Changes are backwards compatible (existing user-settings entities will just be ignored)
- Default values handle missing fields gracefully
- No data migration needed since we default to secure settings (false)

---

## 2. Archive Data Display User Setting

## Current Implementation Analysis

The current archive data filtering mechanism uses query parameters to determine whether to show entities with `archived: true` attributes:

- **Schema Implementation**: Several schemas include an `archived` boolean field (habits, cruddy test entity)
- **Query Parameter Logic**: `db/queries.clj:142-144` uses `(param-true? (:archived params))` to determine filtering
- **Filtering Logic**: `all-entities-for-user` removes entities where `archived-key` is true when `filter-archived` is false
- **UI Integration**: Archive filtering is implemented in habit log views with URL parameters

## Specification

### Goal
Replace query parameter-based archive data filtering with a persistent user setting that follows the same pattern as the recently implemented sensitive data setting:

1. Defaults to hiding archived items (`show-archived: false`)
2. Can be toggled to show archived items  
3. When enabled, provides a toggle to quickly turn off archived display
4. Toggle to enable is buried in a settings dialog

### User Experience Flow

1. **Default State**: Archived items are hidden, no indication they exist
2. **Settings Access**: User opens buried settings dialog (in user profile)
3. **Enable Archived**: User toggles "Show Archived Items" setting
4. **Active State**: 
   - Archived items now visible in all CRUD views
   - Quick toggle appears in navigation/header to temporarily hide
5. **Quick Hide**: User can use quick toggle to temporarily hide without going back to settings

### Technical Requirements

#### User Settings Schema Updates
- Add `user-settings/show-archived` boolean field to existing user-settings entity
- No default value needed - absence means false (hide archived)

#### Database Query Updates
- Modify `all-for-user-query` in `db/queries.clj:141-144` to lookup user settings instead of query parameters
- Create `get-show-archived-setting` function similar to `get-show-sensitive-setting`
- Remove dependency on `:archived` query parameter
- Default to false when no settings entity exists (secure by default)

#### UI Components
- **Settings Dialog**: Add toggle in user edit form (buried/advanced section)
- **Quick Toggle**: When archived is enabled, show quick hide/show toggle in appropriate views
- **Visual Indicators**: Optionally mark archived items with visual cues when displayed

## Implementation Plan

### Phase 1: User Settings Schema & Database Updates
1. **Update user settings schema** (`user_settings_schema.clj`)
   - Add `user-settings/show-archived` boolean field to existing schema

2. **Add archived setting helper** (`db/queries.clj`)
   - Create `get-show-archived-setting` function following same pattern as sensitive
   - Update `all-for-user-query` to use user setting instead of params
   - Replace lines 141-144 with user settings lookup

### Phase 2: UI Updates
3. **Update user edit form** (`app/user.clj`)
   - Add archived data toggle in settings section alongside sensitive toggle
   - Handle form submission to update user-settings entity
   - Lookup existing user-settings for current values

4. **Update habit log views** (`app/habit_log.clj`)
   - Remove query parameter handling for archived (lines 36-38, 231-233, etc.)
   - Remove URL parameter construction for archived filtering
   - Ensure views work with new user-setting-based approach

### Phase 3: Quick Toggle Feature (Optional Enhancement)
5. **Add quick toggle component**
   - Create reusable toggle component for header/navigation
   - Implement temporary session-based override
   - Add visual indicators for archived items when displayed

### Phase 4: Testing & Validation
6. **Integration testing**
   - Verify all CRUD views respect user setting
   - Test edge cases (user without settings entity)
   - Verify secure default behavior (no migration needed)

## Files to Modify

### Core Implementation
- `src/tech/jgood/gleanmo/schema/user_settings_schema.clj` - Add show-archived field
- `src/tech/jgood/gleanmo/db/queries.clj` - Add get-show-archived-setting, update all-for-user-query
- `src/tech/jgood/gleanmo/app/user.clj` - Update edit form and handler for archived setting

### UI Updates
- `src/tech/jgood/gleanmo/app/habit_log.clj` - Remove query param dependencies for archived
- `src/tech/jgood/gleanmo/app/shared.clj` - Quick toggle component (if implemented)

## Implementation Strategy

1. **Add Settings Field**: Add show-archived to user-settings schema
2. **Update Database Logic**: Replace param-based logic with settings lookup in queries.clj
3. **Update UI**: Add settings toggle to user edit form  
4. **Cleanup Views**: Remove query parameter support from habit log and other views
5. **No Migration Needed**: Missing settings default to false (secure)

## Benefits

- **Consistent Pattern**: Follows exact same approach as sensitive data setting
- **Persistent Preference**: User choice survives sessions
- **Better UX**: No need to remember query parameters
- **Security**: Archived data hidden by default, no migration needed
- **Clean Architecture**: Reuses existing settings entity
- **Extensible**: Easy to add more user preferences in future
- **Discoverability**: Settings are intentionally buried but accessible
- **Quick Access**: Optional quick toggle for power users

## Current Archive Parameter Usage

Based on code analysis, archive filtering is currently used in:
- `db/queries.clj:142-144` - Query parameter parsing
- `app/habit_log.clj` - Multiple locations for URL construction and parameter handling
- Schema files (`habit_schema.clj`, `cruddy.clj`) - Archive boolean fields

The migration will remove all query parameter dependencies and replace with persistent user settings, following the exact same pattern as the recent sensitive data changes.
