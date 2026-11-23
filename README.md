# Nordia Tasks – synchronisation service (Track B)

Spring Boot 4.1 / Java 21 / file-based H2. REST service implementing the provided contract
(S1–S6). Initial data is loaded at first start from members.json and tasks.json.

## Run
    .\mvnw.cmd spring-boot:run        # http://localhost:8081
The database file is created in ./data on first start. H2 console: /h2-console.

## Test
    .\mvnw.cmd test
12 integration tests (random port, in-memory H2, no sleeps, no randomness):
- same Idempotency-Key replayed (POST and PATCH): original answer, applied once
- same key with a different request: 422 idempotency_key_reuse
- two simultaneous PATCH with the same baseVersion: exactly one 200 and one 409 (with current)
- two simultaneous requests with the same key: applied once, same answer for both
- stale baseVersion: 409 version_conflict with the current task
- done -> todo refused, done -> doing accepted
- validation: empty/too long title, unknown member, impossible date, bad status, unknown field, empty patch
- delete is a tombstone, visible incrementally, later update gets 410 task_deleted
- pagination: no duplicates, last page still returns a usable cursor, invalid cursor -> 400
- missing Idempotency-Key -> 400
- idempotency record stored in the database

Checked by hand (not automated): an idempotency key replayed after an application restart
returns the original answer (Idempotent-Replay: true).

## Design choices
- **Versions (S1)**: server-managed integer, +1 per accepted change, deletion included.
- **Conflicts and concurrency (S3)**: pessimistic row lock (SELECT ... FOR UPDATE) inside the
  transaction; the second request then sees the new version and gets 409 with the current task.
- **Idempotency (S2)**: key, canonical JSON of the request and the answer are stored in the same
  transaction as the change, in the file database, so they survive a restart. Errors are not
  stored (a retry after 409/422 is a new operation). The key is checked again after the lock is
  acquired, so a concurrent replay gets the original answer instead of a false 409.
- **Cursor (S6)**: opaque Base64 of "updatedAt|id", ordered by (updatedAt, id); limit+1 rows are
  read to compute hasMore. A cursor is always returned (the received one if nothing is new).
- **Deleted tasks**: soft delete (deleted=true, version+1), returned by GET /tasks.
- **Errors**: always { code, error }, plus `current` for 409/410.

## Assumptions (not fixed by the contract, not compared with server.js)
- limit outside 1..50 -> 400 validation_error.
- DELETE without a valid baseVersion -> 422 validation_error.
- nextCursor is returned on the last page too.
- Existing data is served as is (for example a task assigned to an unknown member).

## Not done / known limits
- Two simultaneous POST with the same key and id are handled by a fallback in the exception
  handler, not covered by an automated test.
- The (updatedAt, id) cursor can miss a change committed late by a slow transaction; a monotonic
  sequence column would fix it.
- Business logic lives in the controller (no service layer).
- No OpenAPI, Dockerfile, purge of old idempotency keys or /health.