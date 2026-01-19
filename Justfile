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
