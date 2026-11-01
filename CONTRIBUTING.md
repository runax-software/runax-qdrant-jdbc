# Contributing to the Runax JDBC Driver for Qdrant

Thanks for your interest in contributing! The Runax JDBC Driver for Qdrant lets SQL
tools such as DataGrip query Qdrant collections over JDBC. Here's how to get started.

## Prerequisites

- JDK 17 or newer (the driver targets Java 17)
- [Docker](https://docs.docker.com/get-docker/), to run Qdrant locally
- A code editor (IntelliJ IDEA recommended; DataGrip to try the driver end to end)

You don't need to install Gradle: the Gradle wrapper (`./gradlew`) downloads the right
version.

## Getting started

1. Fork the repository
2. Clone your fork
    ```bash
    git clone https://github.com/<your-username>/runax-qdrant-jdbc.git
    cd runax-qdrant-jdbc
    ```
3. Build
    ```bash
    ./gradlew build
    ```
4. Run the unit tests (no Qdrant or Docker needed)
    ```bash
    ./gradlew test
    ```
5. Start Qdrant locally and run the integration tests against it
    ```bash
    docker compose up -d
    ./gradlew integrationTest
    ```
    `compose.yml` starts two servers:

    | Server | REST (what the driver uses) and dashboard | gRPC | API key |
    | --- | --- | --- | --- |
    | `qdrant` | <http://localhost:6333/dashboard> | `localhost:6334` | none |
    | `qdrant-secured` | `localhost:6335` | `localhost:6336` | `integration-test-key` |

    The gRPC ports are open only so the tests can check the error the driver gives when
    pointed at the wrong port.

    The integration tests create and delete their own collections. `integrationTest` also
    builds the shaded jar and checks it on its own, in a separate class loader, the way
    DataGrip loads drivers. To test against other servers, set `QDRANT_JDBC_URL` and
    `QDRANT_API_KEY`, and `QDRANT_SECURED_JDBC_URL` and `QDRANT_SECURED_API_KEY` for the
    authentication tests. `docker compose down -v` stops both servers and deletes the stored
    collections.
6. Load sample collections to try the driver by hand, for example in DataGrip
    ```bash
    ./gradlew seedQdrant
    ```
    This creates `sample_articles`, `sample_events`, and `sample_products`, replacing any
    collections with those names.

## Project structure

```
src/main/java/com/runaxsoftware/qdrant/jdbc/
  QdrantDriver, DriverInfo                     # The driver's entry point and version
  connection/                                  # URL, settings, the connection, call log
  gateway/                                     # Calls to Qdrant's REST API, and its models
  schema/                                      # How a collection maps to columns
  metadata/                                    # DatabaseMetaData: tables, columns, indexes
  statement/                                   # Statements: plan and run SQL
  resultset/                                   # Read-only result sets
  sql/                                         # SQL parser, planner, and executor
  system/                                      # The system schema's read-only tables
  embedding/                                   # Text embedding providers
src/test/java/com/runaxsoftware/qdrant/jdbc/   # Unit tests, in the package of the class
                                               # they test, and *IntegrationTest classes
  support/                                     # Fake Qdrant server, sample collections,
                                               # and test server settings
build.gradle.kts                                # Build, test tasks, and the shaded driver jar
gradle/libs.versions.toml                       # Dependency versions
gradle/wrapper/                                 # Gradle wrapper (pins the Gradle version)
compose.yml                                     # Local Qdrant for tests and trying the driver
```

Integration tests are tagged `integration`: `./gradlew test` skips them and
`./gradlew integrationTest` runs only them.

Unit tests that need a server use `support/FakeQdrantServer`, an in-process stand-in for
Qdrant's REST API built on the JDK's HTTP server. It answers with Qdrant's own response
shapes, can fail or delay any call, require an API key, and records requests and headers,
so error handling and edge cases are tested without Docker. Use it for anything that depends on how the server answers; use an
integration test to check behaviour against real Qdrant.

## Making changes

1. Create a branch from `main`
    ```bash
    git checkout -b feature/my-change
    ```
2. Make your changes
3. Ensure the project builds cleanly
    ```bash
    ./gradlew build
    ```
4. Run tests
    ```bash
    ./gradlew test
    ```
5. Commit using [conventional commits](https://www.conventionalcommits.org/)
    - `feat:` new feature
    - `fix:` bug fix
    - `refactor:` code change that neither fixes a bug nor adds a feature
    - `chore:` CI, deps, tooling, cleanup
    - `docs:` documentation only
6. Open a pull request against `main`

## Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request to `main` and every
push to `main`:

- `build-and-test-matrix` runs `./gradlew build` on JDK 17, 21, and 25, and
  `build-and-test` reports their combined result.
- `integration-tests` starts both Qdrant servers with `docker compose up -d` and runs
  `./gradlew integrationTest` on JDK 17. When tests fail, the test reports are attached to
  the run.

`build-and-test` and `integration-tests` are the checks to require on `main`. The per-JDK
`build-and-test-matrix (…)` checks aren't, so changing the JDK list never leaves a pull
request waiting for a check that no longer runs.

## Adding a dependency

Every runtime dependency ends up inside the single driver jar that users drop into
DataGrip, so keep them few and small.

1. Prefer the JDK or Jackson, which the driver already uses, over a new library.
2. Open an issue before adding a new runtime dependency.
3. Declare its version in `gradle/libs.versions.toml` and reference it from
   `build.gradle.kts` through `libs`.
4. If other tools commonly ship the same library, relocate it in the `shadowJar` task so
   the versions can't clash.
5. Test-only dependencies (`testImplementation`) are fine.

## Code style

- Follow existing conventions in the codebase; formatting is governed by `.editorconfig`
- Make classes `final` unless they're designed for inheritance
- Add Javadoc to public types and members
- SQL the driver can't run must fail with a clear `SQLException`
  (`SQLFeatureNotSupportedException` where it fits) — never fall back to scanning a whole
  collection on the client
- Keep methods focused and small

## Questions?

Open an [issue](https://github.com/runax-software/runax-qdrant-jdbc/issues).
