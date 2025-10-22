# AI Agent Development Instructions for GLEANMO

## CRITICAL: Server Management Rules

### 🚫 NEVER RUN THE DEV SERVER
**ABSOLUTELY DO NOT** run any development server commands, including but not limited to:
- `clj -M:dev dev`
- `lein dev`
- `clojure -M:dev`
- Any similar server startup commands

### Why This Rule Exists
When AI agents run the dev server, it creates orphaned processes that:
- Lock the rocks.db database file
- Prevent the human developer from running their own server instance
- Require a complete computer restart to recover
- Cause significant workflow disruption

### ✅ What AI Agents SHOULD Do

#### Code Validation Only
- **ONLY use test commands** to validate code compilation and syntax
- Run appropriate test commands (check project docs for specific commands)
- Use linting and type checking commands when available
- Validate syntax through compilation checks only

#### Safe Development Practices
- Read and analyze code without running it
- Make code changes and validate through tests
- Use static analysis tools
- Check formatting and style without running servers

#### Examples of Safe Commands
```bash
# Test validation (GOOD)
clj -M:test
lein test
clojure -M:test

# Linting/formatting (GOOD)  
clj -M:cljfmt
lein cljfmt fix

# Compilation checks (GOOD)
clj -M:compile
```

#### Examples of FORBIDDEN Commands
```bash
# Server startup (FORBIDDEN)
clj -M:dev dev
lein dev
clojure -M:dev

# REPL startup (FORBIDDEN)
clj -M:repl
lein repl
```

## Project Context

This is a Clojure project using the tech.jgood.gleanmo namespace structure. The human developer prefers to run the server locally for validation and testing purposes.

## Emergency Recovery

If you accidentally run a server command and the database becomes locked:
1. Stop all agent processes immediately
2. The human developer will need to restart their computer
3. This is why the rule above is so critical

## Summary

**Test commands only, never servers.** This ensures smooth collaboration between AI agents and human developers.