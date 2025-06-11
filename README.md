# Gleanmo

A personal tracking and lifestyle management application built with Clojure. 

## About

I'm obsessed with tracking personal metrics. I find quantified information motivating. This project is an attempt to consolidate all the aspects of my life that I want to track digitally. It's also a playground to play with data visualizations of that information.

## Project Structure

- `/src/tech/jgood/gleanmo/` - Core application code
  - `/app/` - Domain-specific modules (habits, meditation, etc.)
  - `/schema/` - Data models and validation
  - `/crud/` - Generic CRUD operations
- `/resources/` - Configuration and static assets
- `/dev/` - Development utilities
- `/test/` - Unit and integration tests

## Tech Stack

- **Backend**: Clojure with [Biff](https://biffweb.com/) framework
- **Database**: [XTDB](https://xtdb.com/) (bitemporal database)
- **Frontend**: [Rum](https://github.com/tonsky/rum) (React wrapper) + [HTMX](https://htmx.org/) + [Tailwind CSS](https://tailwindcss.com/)
- **Authentication**: Email-based with reCAPTCHA protection

## Development

### Development Environment

The project can be run locally with a _standalone_ XTDB topology stored in `storage/xtdb`. Unit tests will run automatically on file saves.

To run the development environment:
```bash
clj -M:dev dev
```

### Running Tests

The project uses the Clojure CLI with a custom test runner defined in `dev/tasks.clj`. Tests are organized under the `tech.jgood.gleanmo.test` namespace.

To run all tests:
```bash
clj -M:dev test
```

To run a specific test namespace just provide the namespace as the next argument:
```bash
clj -M:dev test tech.jgood.gleanmo.test.crud.handlers-test
```

Tests use an in-memory XTDB database via `test-xtdb-node` from the Biff framework, making them fast and isolated from the development database.

## License

All rights reserved. This code is shared for demonstration and educational purposes.
