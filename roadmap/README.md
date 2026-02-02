# Gleanmo Roadmap

## North Star

Build a personal quantified-self system that is fast, reliable, and fully owned. No external dependencies (exit Airtable), no subscription tracking services, complete data sovereignty.

Gleanmo tracks habits, tasks, medication, exercise, calendar events, projects, and other life data through a schema-driven CRUD system with timer integration and visualizations.

---

## Roadmap System

The canonical roadmap lives in `roadmap/index.md`. The `roadmap/README.md` file describes the system and conventions.

## Structure

```
roadmap/
├── README.md          # This file - system documentation
├── index.md           # Canonical roadmap index (with frontmatter)
├── _template.md       # Work unit template
├── *.md               # Individual work unit files (with frontmatter)
└── archived/          # Completed or dropped work units
```

## Work Unit Frontmatter

Every work unit file must begin with YAML frontmatter:

```yaml
---
title: "Feature Name"
status: idea | planned | active | paused | done | dropped
description: "One-line summary of what this work unit accomplishes"
tags: [area/frontend, type/feature]
priority: medium
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

Required fields:
- `title`
- `status`
- `description`

Recommended fields:
- `tags` (use prefixes `area/`, `type/`, `tech/`)
- `priority` (high | medium | low)
- `created`
- `updated` (update whenever the work unit changes)

Status lives in frontmatter, not in prose.

## Status Definitions

| Status | Meaning |
|--------|---------|
| `idea` | Captured but not yet scoped |
| `planned` | Scoped and ready to start |
| `active` | Currently being worked on |
| `paused` | Started but blocked or deprioritized |
| `done` | Shipped and working |
| `dropped` | Decided not to pursue |

## Conventions

- One file per work unit.
- Keep the narrative in the body; status stays in frontmatter.
- Small ideas can live as bullets in `index.md`; promote to files when they need detail.
- When a work unit reaches `done` or `dropped`, move the file to `archived/`.
- Update the `updated` frontmatter field whenever you edit a work unit.

## Adding a New Work Unit

1. Copy `roadmap/_template.md` to a new file with a descriptive name (e.g., `feature-name.md`).
2. Fill in the frontmatter and body sections.
3. Add an entry to `roadmap/index.md` in the appropriate section.
4. Add detailed notes as needed.

## Updating Status

1. Change the `status` field in the work unit frontmatter.
2. Move the entry to the correct section in `roadmap/index.md`.
3. When a work unit reaches `done` or `dropped`, move the file to `archived/`.
