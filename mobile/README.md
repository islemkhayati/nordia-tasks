# Files for the "Team tasks, offline" case study

Everything here is fictional. You choose **one** track (A, B or C) in the brief; this folder serves the three.

| File | Track A (mobile app) | Track B (REST service) | Track C (Oracle APEX / PL/SQL) |
|---|---|---|---|
| `server.js` | the API your app talks to | shows the expected behaviour of the contract (see the brief) | not needed |
| `tasks.json`, `members.json` | what the API serves at start | to load as initial data of your database | not needed |
| `schema.sql` | not needed | not needed | creates the tables and loads the sample data |

## The mock API (`server.js`)

Needs Node.js 18 or later. Nothing to install.

```
node server.js
```

It listens on http://localhost:3002. If you test from an Android emulator, the host machine is `10.0.2.2`, not `localhost`.

| Route | Description |
|---|---|
| `GET /members` | the six team members: `id`, `name` |
| `GET /tasks?since=<cursor>&limit=<n>` | tasks changed after the cursor, oldest change first, `limit` 1 to 50 (default 20). Answer: `{ items, nextCursor, hasMore }`. No `since` = from the beginning. The cursor is opaque: keep it as it is. Deleted tasks come back as tasks with `deleted: true`. |
| `GET /tasks/:id` | one task (a deleted task is returned with `deleted: true`) |
| `POST /tasks` | create. Header `Idempotency-Key`. Body: `id` (a UUID that the **client** chooses), `title`, and optionally `note`, `status`, `assigneeId`, `dueDate`. `201` with the task (version 1). |
| `PATCH /tasks/:id` | change. Header `Idempotency-Key`. Body: `baseVersion` and the fields you change (`title`, `note`, `status`, `assigneeId`, `dueDate`). `null` clears `note`, `assigneeId`, `dueDate`. `200` with the task. |
| `DELETE /tasks/:id?baseVersion=<n>` | delete. Header `Idempotency-Key`. `200` with the task (`deleted: true`, version + 1). |

Task fields: `id`, `title` (1 to 120 characters), `note` (null or up to 500), `status` (`todo`, `doing`, `done`), `assigneeId` (null or a member id), `dueDate` (null or `YYYY-MM-DD`, a calendar date), `version`, `deleted`, `updatedAt` (server time, UTC).

Error answers have the form `{ "code": "...", "error": "readable message" }`, plus `current` (the task as it is now) for 409 and 410.

| Status | `code` | When |
|---|---|---|
| 400 | `missing_idempotency_key`, `invalid_json`, `invalid_cursor` | malformed request |
| 404 | `not_found` | unknown task |
| 409 | `version_conflict` | `baseVersion` is not the current version (answer includes `current`) |
| 409 | `id_exists` | creating an id that already exists |
| 410 | `task_deleted` | changing a task that was deleted (answer includes `current`) |
| 422 | `validation_error` | invalid field, unknown field, nothing to change, assignee that is not a member |
| 422 | `invalid_transition` | a `done` task moved back to `todo` (`done` to `doing` is allowed) |
| 422 | `idempotency_key_reuse` | same key, different request |
| 500 | `simulated_error` | see below |

On purpose, the server behaves like a real mobile backend:

- it answers slowly (150 to 900 ms);
- about 1 call in 8 fails with a 500, and **a 500 or a timeout never tells you whether a write was applied**;
- the answer to an `Idempotency-Key` is final: the same key with the same request returns the original answer again (header `Idempotent-Replay: true`); after a 409 or a 422, a new attempt is a new operation;
- data lives in memory: restart the server to reset it.

Handy for your own tests (they never fail and are never slow, and they do not exist in the real service):

- `POST /_test/reset` reloads the data and forgets all keys;
- `POST /_test/colleague-edit/:id` with a body such as `{ "title": "New title" }` or `{ "delete": true }` makes a colleague change the task, so you can provoke a conflict.

Environment variables (optional): `PORT`, `FAIL_RATE` (`0` for no failure), `LATENCY=0` (no delay), `SEED=<integer>` (reproducible failures and delays when calls are made one at a time). Do not rely on the random failures in automated tests.

## The data

`tasks.json` has 45 tasks (3 of them deleted), `members.json` has 6 members. The data is deterministic (generated with a fixed seed) and, like real data, not perfect.

## `schema.sql` (track C)

Run it in the schema of your APEX workspace (SQL Workshop > SQL Scripts). It drops and recreates four tables, `NT_MEMBER`, `NT_TASK`, `NT_INCOMING_CHANGE` (15 changes received from phones, none processed yet) and `NT_PROCESSED_OP` (empty), and loads sample data. The comments in the script explain every column. The script was written for Oracle 19c or later and checked by hand only. If it fails on your instance, tell us which statement and why, and fix it in your copy.

