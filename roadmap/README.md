# Gleanmo Roadmap

## North Star

Build a personal quantified-self system that is fast, reliable, and fully owned. No external dependencies (exit Airtable), no subscription tracking services, complete data sovereignty.

Gleanmo tracks habits, tasks, medication, exercise, calendar events, projects, and other life data through a schema-driven CRUD system with timer integration and visualizations.

---

## Roadmap System

This section describes how the roadmap documentation works. For the directory of work units, see [index.md](./index.md).

## Structure

```
roadmap/
├── README.md          # This file - system documentation
├── index.md           # Directory of all work units by state
├── _template.md       # Starting point for new work units
├── *.md               # Individual work unit files
└── archived/          # Completed or dropped work units
```

## Work Unit Format

Each work unit file follows a consistent structure:

```markdown
# Work Unit Title

## Work Unit Summary
- Status: idea | active | paused | done | dropped
- Problem / intent: One-line description of what and why
- Constraints: Hard requirements or limitations
- Proposed approach: High-level solution direction
- Open questions: Unresolved decisions

## Notes
(Detailed content, specs, implementation notes, etc.)
```

## Status Definitions

| Status | Meaning |
|--------|---------|
| `idea` | Captured but not yet prioritized or designed |
| `active` | Currently being worked on or recently touched |
| `paused` | Started but intentionally set aside |
| `done` | Shipped and stable |
| `dropped` | Abandoned (move to `archived/`) |

## Conventions

- **One file per work unit.** Keep all details for a feature in its own file.
- **Start with the summary block.** Makes scanning easy and keeps status visible.
- **Avoid checklists and subtasks.** Use narrative notes instead; status lives in the summary.
- **No time estimates.** Focus on what, not when.
- **Archive when done.** Move completed or dropped work units to `archived/` to keep the main directory clean.

## Adding a New Work Unit

1. Copy `_template.md` to a new file with a descriptive name (e.g., `feature-name.md`)
2. Fill in the Work Unit Summary section
3. Add an entry to `index.md` in the appropriate state section
4. Add detailed notes as needed

## Updating Status

1. Change the `Status:` line in the work unit file
2. Move the entry to the correct section in `index.md`
3. When a work unit reaches `done` or `dropped`, move the file to `archived/`
