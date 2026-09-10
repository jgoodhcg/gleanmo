---
title: "Configuration File Cleanup"
status: draft
description: "Audit and consolidate configuration files (config.env, example.config.edn, example.secrets.env) to remove legacy artifacts"
created: 2026-03-16
updated: 2026-03-16
tags: [infrastructure, developer-experience]
priority: low
---

# Configuration File Cleanup

## Intent

Clarify which configuration files are actually required for the current version of the app and remove or document legacy artifacts from previous iterations.

## Specification

- Audit all configuration files: `config.env`, `example.config.edn`, `example.secrets.env`, `resources/config.template.env`
- Document which files are actively used by the current Biff version
- Identify and remove or archive legacy configuration options
- Update documentation to clearly explain the configuration system
- Ensure new developers can quickly understand what needs to be configured

## Validation

- [ ] All config files documented with their purpose
- [ ] Unused configuration options removed or archived
- [ ] README or docs updated with clear setup instructions
- [ ] Dev setup tested from scratch to verify nothing missing

## Scope

- Configuration file audit and cleanup only
- Not changing the configuration system itself
- Not adding new configuration options

## Context

Multiple configuration files exist with overlapping purposes. Some may be remnants of earlier Biff versions or previous project iterations. This creates confusion for onboarding and maintenance.

Relevant files:
- `config.env`
- `example.config.edn`
- `example.secrets.env`
- `resources/config.template.env`
- Biff configuration loading code

## Open Questions

- Which configuration file(s) does Biff actually read at startup?
- Are `example.*` files just templates or do they serve another purpose?
- Is `config.env` the canonical local config or is it overridden elsewhere?
- What secrets are actually required vs optional for local development?
- Should there be a single template file or multiple?

## Notes

[Design details, context, implementation notes as work progresses.]
