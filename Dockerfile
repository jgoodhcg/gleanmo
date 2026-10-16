# The default deploy instructions (https://biffweb.com/docs/reference/production/) don't
# use Docker, but this file is provided in case you'd like to deploy with containers.
#
# When running the container, make sure you set any environment variables defined in config.env,
# e.g. using whatever tools your deployment platform provides for setting environment variables.
#
# Run these commands to test this file locally:
#
#   docker build -t your-app .
#
# Run this command if building from arm macbook
#
#   docker buildx build --platform linux/amd64 -t your-app .
#
#   docker run --rm -e BIFF_PROFILE=dev -v $PWD/config.env:/app/config.env your-app

# This is the base builder image, construct the jar file in this one
# it uses alpine for a small image
FROM clojure:temurin-25-tools-deps-alpine AS jre-build

ENV TAILWIND_VERSION=v3.2.4
# SHA-256 of the v3.2.4 tailwindcss-linux-x64 release binary. That release
# publishes no checksum file; this is the hash taken on 2026-10-16. Change it
# together with TAILWIND_VERSION.
ENV TAILWIND_SHA256=cd52e757cb0bd15238f0207a215198d924811234028d056b7be39fde70491296

# Install the missing packages and applications in a single layer
RUN apk add curl rlwrap && curl -fL -o /usr/local/bin/tailwindcss \
  https://github.com/tailwindlabs/tailwindcss/releases/download/$TAILWIND_VERSION/tailwindcss-linux-x64 \
  && echo "$TAILWIND_SHA256  /usr/local/bin/tailwindcss" | sha256sum -c - \
  && chmod +x /usr/local/bin/tailwindcss

WORKDIR /app
COPY src ./src
COPY dev ./dev
COPY resources ./resources
COPY deps.edn .

# construct the application jar
RUN clj -M:dev uberjar && cp target/jar/app.jar . && rm -r target

# This stage (see multi-stage builds) is a bare Java container
# copy over the uberjar from the builder image and run the application
FROM eclipse-temurin:25-alpine

# Run as an unprivileged user. It owns /app because the app writes there at
# startup: XTDB's local index (storage/xtdb) and generated pages
# (target/resources/public). If a deploy fails on a permission error, revert
# this block first.
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app \
  && mkdir -p /app && chown app:app /app
WORKDIR /app

# Take the uberjar from the base image and put it in the final image
COPY --from=jre-build --chown=app:app /app/app.jar /app/app.jar
USER app

EXPOSE 8080

# By default, run in PROD profile
ENV BIFF_PROFILE=prod
ENV HOST=0.0.0.0
CMD ["/opt/java/openjdk/bin/java", "-XX:-OmitStackTraceInFastThrow", "-XX:+CrashOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-jar", "app.jar"]
