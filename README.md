# Biff example project

This is the example/template project for Biff.

Run `bb dev` to get started. See `bb tasks` for other commands.

## Migrations
Example of running against a local container
``` shell
docker run --name local-ledger \
  -e POSTGRES_USER=user \
  -e POSTGRES_PASSWORD=password \
  -e POSTGRES_DB=ledger \
  -p 5432:5432 \
  -d postgres

export DB_HOST=localhost DB_PORT=5432 DB_NAME=ledger DB_USER=user DB_PASSWORD=password

clj -M:dev migrate --help
```

