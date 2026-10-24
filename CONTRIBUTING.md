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
4. Run the tests
    ```bash
    ./gradlew test
    ```
5. Start Qdrant locally when you want to try the driver against a real server
    ```bash
    docker run -p 6333:6333 qdrant/qdrant
    ```

## Project structure

```
src/main/java/com/runaxsoftware/qdrant/jdbc/   # The driver
src/test/java/com/runaxsoftware/qdrant/jdbc/   # Tests
build.gradle.kts                                # Build, dependencies, and packaging
settings.gradle.kts                             # Gradle project settings
gradle/wrapper/                                 # Gradle wrapper (pins the Gradle version)
```

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

## Adding a dependency

Every runtime dependency ends up inside the single driver jar that users drop into
DataGrip, so keep them few and small.

1. Prefer the JDK (`java.net.http`, `java.sql`) over a library.
2. Open an issue before adding a new runtime dependency.
3. Test-only dependencies (`testImplementation`) are fine.

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
