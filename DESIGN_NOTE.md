# Design Note – Nordia Tasks
**Track B: REST Synchronization Service**

## 1. Understanding of the need and assumptions

The objective is to build a REST service that allows technicians to synchronize tasks without losing updates or creating duplicates. The service must manage task versions, support idempotent operations, detect conflicts, validate business rules, and provide incremental task retrieval, including deleted tasks (tombstones).

I chose Track B because I wanted to work with Java and Spring Boot and learn how backend services manage data consistency and concurrent requests.

I made two assumptions about the synchronization rules:

- **Idempotency:** Reusing the same idempotency key with a different request payload should be rejected instead of replaying the previous response.
- **Pagination:** I used an opaque cursor based on the task update timestamp and identifier to retrieve changes incrementally. I assumed this combination would provide a stable ordering. More advanced handling of changes occurring during pagination could be added.

## 2. Architecture and technical decisions

The application is built with Java 21, Spring Boot, Spring Data JPA and a file-based H2 database.

It follows a layered architecture:
- Controllers handle HTTP requests.
- Entities represent tasks, members and idempotency records.
- Repositories provide database access.
- Data loaders initialize the database from the provided JSON files.

Two important technical decisions were:

1. **Version-based conflict detection:** Each accepted task modification increments its version. PATCH and DELETE requests provide a base version. If it does not match the current version, the service returns HTTP 409 with the current task.
2. **Persistent idempotency:** Write requests use the `Idempotency-Key` header. The request and response are stored in the database so that a repeated request with the same key and payload can replay the original response.

The service also supports task creation, reading, updating, deletion, validation and cursor-based pagination.

## 3. Testing and verification

The project was tested using Maven. The latest test execution completed successfully with 13 tests, 0 failures and 0 errors.

The tests cover important scenarios such as idempotency replay, version conflicts, concurrent updates using the same base version, validation, business rules, deletion and pagination.

Manual API checks also included task creation, pagination and replaying a POST request with the same idempotency key. Persistence was checked across an application restart.

I did not test production deployment or heavy-load scenarios.

## 4. Limitations and possible improvements

I prioritized the required REST synchronization features and automated tests. Optional features such as OpenAPI documentation, Docker support, a health endpoint and automatic cleanup of old idempotency records were not implemented.

Additional testing of concurrent POST requests and more advanced pagination scenarios would improve the solution.

## 5. Use of AI assistance

I used ChatGPT as a development assistant to understand the requirements, explore implementation approaches, generate and improve parts of the code, troubleshoot errors, and prepare tests and documentation.

I checked the application by running it, executing the automated tests and performing manual API checks. I also used the explanations to learn more about the implementation. AI suggestions were not treated as automatically correct.

## 6. What I would do with one more day

I would add more concurrency and pagination tests, improve API documentation, review edge cases in idempotency handling and improve the overall maintainability of the code.