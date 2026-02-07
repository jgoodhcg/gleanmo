# Run `just` to see all commands

default:
    @just --list

# Check formatting (non-destructive)
fmt-check:
    clj -M:cljfmt check src test dev

# Fix formatting
fmt-fix:
    clj -M:cljfmt fix src test dev

# Run linter
lint:
    clj -M:lint --lint src --lint test --lint dev

# Quick validation (format + lint) - PRIMARY COMMAND FOR AGENTS
# Run after every code change to catch syntax errors, unresolved symbols, invalid arity, etc.
check: fmt-check lint

# Run all tests
test:
    clj -M:dev test

# Full validation (format + lint + tests)
validate: check test

# Install e2e dependencies and browsers
e2e-install:
    cd e2e && npm install && npm run install-browsers

# Screenshot a page (requires dev server running)
# Usage: just e2e-screenshot /app/habits
e2e-screenshot path="/app":
    cd e2e && npm run screenshot -- {{path}}

# Screenshot full page
# Usage: just e2e-screenshot-full /app/habits
e2e-screenshot-full path="/app":
    cd e2e && npm run screenshot -- {{path}} --full

# Run a UI flow
# Usage: just e2e-flow example
e2e-flow name="example":
    cd e2e && npm run flow -- {{name}}

# Run Today page reorder test
e2e-test-reorder:
    cd e2e && npm run test:today-reorder

# Run Today page toggle test
e2e-test-today-toggle:
    cd e2e && npm run test:today-toggle

# Run Today page quick add test
e2e-test-today-quick-add:
    cd e2e && npm run test:today-quick-add

# Run Focus page Today filter test
e2e-test-today-filter:
    cd e2e && npm run test:today-filter
