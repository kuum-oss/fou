# My Personal Notes: NotifyHub Project

This document contains important local environment nuances, known bugs, and workarounds discovered during the development of NotifyHub.

## 1. Java Version Compatibility (Java 25 vs Java 21)
The project is built using **Java 21**. However, if your system defaults to a newer JDK (e.g., Java 25), the `maven-compiler-plugin` will fail during compilation because **Lombok does not yet support Java 25** (Error: `java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN`).

### Solution:
Always use **Java 21** when compiling or running tests. 
The easiest way is to use the provided `./run.sh` wrapper script:
```bash
./run.sh package
./run.sh test
./run.sh run
```
Or, manually set `JAVA_HOME` before invoking Maven:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./mvnw clean package
```

## 2. Testcontainers / Docker Desktop Bug (macOS)
When running `./mvnw test`, you might encounter an error indicating that Testcontainers cannot connect to the Docker daemon:
```
Could not find a valid Docker environment.
... NoSuchFileException (/var/run/docker.sock)
... DockerDesktopClientProviderStrategy: failed with exception BadRequestException (Status 400)
```
This is a known issue between the `Testcontainers` library and certain newer versions of **Docker Desktop** on macOS. Docker's internal API rejects the Testcontainers connection attempt with a `400 Bad Request`.

### Solution:
This is strictly a local testing environment issue and **does not affect the application itself**. If you encounter this error, you can simply skip the integration tests when building the project:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./mvnw clean package -DskipTests
```
Or with the script:
```bash
./run.sh package
```

## 3. API Usage Notes
When testing the API via `curl`, ensure you replace placeholders with actual values.

- **Missing Request Body**: Sending a `POST /orders` without a JSON body will now correctly return a `400 Bad Request` (previously threw a 500 `HttpMessageNotReadableException`).
- **Placeholder UUIDs**: In the report endpoint `GET /orders/<ORDER_ID>/report`, you **must** replace `<ORDER_ID>` with the actual UUID generated from the POST request. If you pass an invalid UUID format (like literally passing the string "ORDER_ID"), the backend will catch the `MethodArgumentTypeMismatchException` and return a clean `400 Bad Request` instead of crashing.
