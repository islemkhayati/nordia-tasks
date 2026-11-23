# Design Note – Nordia Tasks REST Synchronization Service

## 1. Understanding and assumptions

The objective is to provide a REST API for synchronizing members and tasks. The service supports task creation, updates, deletion, version control, pagination, and idempotent write operations.

Deleted tasks are represented as tombstones instead of being physically removed. When an update is based on an outdated version, the API returns HTTP 409 rather than attempting an automatic merge.

## 2. Architecture and technical decisions

The application is built with Java 21 and Spring Boot, using a layered architecture:

- Controllers handle HTTP requests and expose REST endpoints.
- Entities represent tasks, members, and idempotency records.
- Repositories provide database access through Spring Data JPA.
- Data loaders initialize the database from JSON files.

The application uses an H2 file-based database for persistent storage.

Two important decisions were made:

1. Version-based conflict detection: each accepted task modification increments its version. PATCH and DELETE require a base version. If it does not match the current version, the API returns HTTP 409 with the current task.

2. Persistent idempotency: write requests use the Idempotency-Key header. The request and response are stored in the database. Repeated requests with the same key and payload replay the original response.

Pagination uses an opaque cursor based on the task update timestamp and identifier. Deleted tasks remain available in synchronization results.

## 3. Testing and verification

The project was tested using Maven:

`.\mvnw.cmd clean test`

The latest test execution completed successfully with 13 tests, 0 failures, and 0 errors.

Manual API checks included task pagination, task creation, and replaying a POST request with the same idempotency key. Persistence was also verified across an application restart: replaying the same POST request returned HTTP 201 with Idempotent-Replay: true and the original task response.

## 4. Limitations and possible improvements

Possible improvements include publishing an OpenAPI specification, adding Docker support, implementing an idempotency-record cleanup policy, and expanding integration tests for concurrent POST requests and database restart scenarios.

## 5. Use of AI assistance

ChatGPT was used as a development assistant to help review implementation choices, troubleshoot errors, and prepare test commands. The implementation was checked through local Maven tests and manual HTTP requests.

## 6. If I had one extra day

I would focus on additional concurrency and restart tests, improve API documentation, and review edge cases in pagination and idempotency handling.
