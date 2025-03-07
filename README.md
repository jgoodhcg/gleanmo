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

## Tech Stack

- **Backend**: Clojure with [Biff](https://biffweb.com/) framework
- **Database**: [XTDB](https://xtdb.com/) (bitemporal database)
- **Frontend**: [Rum](https://github.com/tonsky/rum) (React wrapper) + [HTMX](https://htmx.org/) + [Tailwind CSS](https://tailwindcss.com/)
- **Authentication**: Email-based with reCAPTCHA protection

## License

All rights reserved. This code is shared for demonstration and educational purposes.
