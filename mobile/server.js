// Mock API for the "Team tasks" case study (mobile / Java / Oracle APEX tracks). Node.js 18+, no dependency.
//   node server.js            (listens on http://localhost:3002)
//
// Routes (details in README.md):
//   GET    /members
//   GET    /tasks?since=<cursor>&limit=<1..50, default 20>     -> { items, nextCursor, hasMore }
//   GET    /tasks/:id
//   POST   /tasks                      header Idempotency-Key, body { id, title, note?, status?, assigneeId?, dueDate? }
//   PATCH  /tasks/:id                  header Idempotency-Key, body { baseVersion, <changed fields> }
//   DELETE /tasks/:id?baseVersion=n    header Idempotency-Key
//
// Behaviour on purpose, like a real mobile backend:
//   - random latency of 150-900 ms on every call;
//   - about 1 call in 8 fails with a 500. On a write (POST/PATCH/DELETE), half of those failures happen AFTER the change was
//     applied: the answer is lost, not the change. A 500 or a timeout never tells you whether the write was applied;
//   - the answer to an Idempotency-Key is final: the same key with the same request replays the original answer
//     (header "Idempotent-Replay: true"), the same key with a different request is a 422. After a 409 or a 422,
//     a new attempt is a new operation and needs a new key;
//   - data lives in memory: restarting the server resets it.
//
// Options (environment variables, all optional): PORT (3002), FAIL_RATE (0.125; use 0 for none), LATENCY (set to 0 for no delay),
// SEED (any integer: makes the failures and delays reproducible when calls are made one at a time).
//
// Test helpers, never slowed down and never failing (they do not exist in production):
//   POST /_test/reset                        reload the data, forget all idempotency keys
//   POST /_test/colleague-edit/:id           body { title?, note?, status?, assigneeId?, dueDate?, delete?: true }
//                                            a colleague changes the task: the version is bumped, no checks on versions
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 3002);
const FAIL_RATE = process.env.FAIL_RATE !== undefined ? Number(process.env.FAIL_RATE) : 1 / 8;
const NO_LATENCY = process.env.LATENCY === "0";
const STATUSES = ["todo", "doing", "done"];
const FIELDS = ["title", "note", "status", "assigneeId", "dueDate"];
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function makeRandom(seed) {
  // mulberry32
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const random = process.env.SEED !== undefined ? makeRandom(Number(process.env.SEED)) : Math.random;

let members, tasks, seq, idem;
function load() {
  members = JSON.parse(fs.readFileSync(path.join(__dirname, "members.json"), "utf8"));
  // "seq" is the server's change counter; it never leaves the server. The cursor is built from it.
  tasks = JSON.parse(fs.readFileSync(path.join(__dirname, "tasks.json"), "utf8")).map((t, i) => ({ ...t, seq: i + 1 }));
  seq = tasks.length;
  idem = new Map(); // Idempotency-Key -> { fingerprint, out }. Unbounded and volatile: this is a mock.
}
load();

const pub = ({ seq: _s, ...t }) => t;
const reply = (status, body, headers = {}) => ({ status, body, headers });

function send(res, code, body, extra = {}) {
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Idempotency-Key",
    "Access-Control-Expose-Headers": "Idempotent-Replay",
    ...extra,
  });
  res.end(code === 204 ? undefined : JSON.stringify(body));
}

function isCalendarDate(s) {
  if (typeof s !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const d = new Date(s + "T00:00:00Z");
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

// Returns a list of messages ([] when valid). Only the fields present in "f" are checked.
function validate(f, creating) {
  const errs = [];
  if (creating || "title" in f) {
    if (typeof f.title !== "string" || f.title.trim().length < 1 || f.title.trim().length > 120) errs.push("title: 1 to 120 characters");
  }
  if ("note" in f && f.note !== null && (typeof f.note !== "string" || f.note.length > 500)) errs.push("note: null or at most 500 characters");
  if ("status" in f && !STATUSES.includes(f.status)) errs.push("status: one of " + STATUSES.join(", "));
  if ("assigneeId" in f && f.assigneeId !== null && !members.some((m) => m.id === f.assigneeId)) errs.push("assigneeId: null or the id of a member");
  if ("dueDate" in f && f.dueDate !== null && !isCalendarDate(f.dueDate)) errs.push("dueDate: null or a calendar date YYYY-MM-DD");
  return errs;
}
const validation = (errs) => reply(422, { code: "validation_error", error: errs.join("; "), details: errs });

function touch(t) {
  t.version += 1;
  t.seq = ++seq;
  t.updatedAt = new Date().toISOString();
}

function applyFields(t, f) {
  let changed = false;
  for (const k of FIELDS) {
    if (!(k in f)) continue;
    const v = k === "title" ? f.title.trim() : f[k];
    if (t[k] !== v) { t[k] = v; changed = true; }
  }
  return changed;
}

function listTasks(url) {
  const sinceParam = url.searchParams.get("since") || "";
  let since = 0;
  if (sinceParam) {
    const m = sinceParam.match(/^c(\d+)$/);
    if (!m) return reply(400, { code: "invalid_cursor", error: "Unknown cursor" });
    since = Number(m[1]);
  }
  let limit = 20;
  if (url.searchParams.has("limit")) {
    const raw = url.searchParams.get("limit");
    limit = /^\d+$/.test(raw) ? Number(raw) : NaN;
    if (!(limit >= 1 && limit <= 50)) return reply(422, { code: "validation_error", error: "limit: integer from 1 to 50" });
  }
  const matching = tasks.filter((t) => t.seq > since).sort((a, b) => a.seq - b.seq);
  const items = matching.slice(0, limit);
  const nextCursor = items.length ? "c" + items[items.length - 1].seq : sinceParam || null;
  return reply(200, { items: items.map(pub), nextCursor, hasMore: matching.length > items.length });
}

function createTask(body) {
  const known = ["id", ...FIELDS];
  const unknown = Object.keys(body).filter((k) => !known.includes(k));
  const errs = validate(body, true);
  if (typeof body.id !== "string" || !UUID_RE.test(body.id)) errs.push("id: a UUID chosen by the client");
  if (unknown.length) errs.push("unknown fields: " + unknown.join(", "));
  if (errs.length) return validation(errs);
  const existing = tasks.find((t) => t.id === body.id);
  if (existing) return reply(409, { code: "id_exists", error: "A task with this id already exists", current: pub(existing) });
  const t = {
    id: body.id,
    title: body.title.trim(),
    note: "note" in body ? body.note : null,
    status: "status" in body ? body.status : "todo",
    assigneeId: "assigneeId" in body ? body.assigneeId : null,
    dueDate: "dueDate" in body ? body.dueDate : null,
    version: 1,
    deleted: false,
    updatedAt: new Date().toISOString(),
    seq: ++seq,
  };
  tasks.push(t);
  return reply(201, pub(t));
}

function patchTask(id, body) {
  const unknown = Object.keys(body).filter((k) => k !== "baseVersion" && !FIELDS.includes(k));
  const errs = validate(body, false);
  if (!Number.isInteger(body.baseVersion) || body.baseVersion < 1) errs.push("baseVersion: integer, the version you started from");
  if (unknown.length) errs.push("unknown fields: " + unknown.join(", "));
  if (!FIELDS.some((k) => k in body)) errs.push("nothing to change");
  if (errs.length) return validation(errs);
  const t = tasks.find((x) => x.id === id);
  if (!t) return reply(404, { code: "not_found", error: "Task not found" });
  if (t.deleted) return reply(410, { code: "task_deleted", error: "This task was deleted", current: pub(t) });
  if (body.baseVersion !== t.version) return reply(409, { code: "version_conflict", error: "The task changed since version " + body.baseVersion, current: pub(t) });
  if (t.status === "done" && body.status === "todo") return reply(422, { code: "invalid_transition", error: "A done task cannot go back to todo (use doing)" });
  // A request that changes nothing is accepted and does not bump the version.
  if (applyFields(t, body)) touch(t);
  return reply(200, pub(t));
}

function deleteTask(id, url) {
  const raw = url.searchParams.get("baseVersion");
  if (!/^\d+$/.test(raw || "") || Number(raw) < 1) return validation(["baseVersion: integer, the version you started from"]);
  const t = tasks.find((x) => x.id === id);
  if (!t) return reply(404, { code: "not_found", error: "Task not found" });
  if (t.deleted) return reply(200, pub(t)); // already deleted: nothing to do, whatever the version
  if (Number(raw) !== t.version) return reply(409, { code: "version_conflict", error: "The task changed since version " + raw, current: pub(t) });
  t.deleted = true;
  touch(t);
  return reply(200, pub(t));
}

// Canonical form of a JSON value (sorted keys) so that a retry with the same content but another key order is the same request.
function canonical(v) {
  if (Array.isArray(v)) return "[" + v.map(canonical).join(",") + "]";
  if (v && typeof v === "object") return "{" + Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + canonical(v[k])).join(",") + "}";
  return JSON.stringify(v);
}

function write(method, url, headers, raw, id) {
  const key = headers["idempotency-key"];
  if (!key || key.length < 8 || key.length > 64) return reply(400, { code: "missing_idempotency_key", error: "Header Idempotency-Key is required (8 to 64 characters)" });
  let body = {};
  if (method !== "DELETE") {
    try { body = JSON.parse(raw || "{}"); } catch { return reply(400, { code: "invalid_json", error: "Invalid JSON" }); }
    if (body === null || typeof body !== "object" || Array.isArray(body)) return reply(400, { code: "invalid_json", error: "The body must be a JSON object" });
  }
  const fingerprint = method + " " + url.pathname + url.search + " " + canonical(body);
  const previous = idem.get(key);
  if (previous) {
    if (previous.fingerprint !== fingerprint) return reply(422, { code: "idempotency_key_reuse", error: "This Idempotency-Key was already used for a different request" });
    return { ...previous.out, headers: { ...previous.out.headers, "Idempotent-Replay": "true" } };
  }
  const out = method === "POST" ? createTask(body) : method === "PATCH" ? patchTask(id, body) : deleteTask(id, url);
  idem.set(key, { fingerprint, out });
  return out;
}

function handle(method, url, headers, raw) {
  const m = url.pathname.match(/^\/(members|tasks)(?:\/([^/]+))?$/);
  if (!m) return reply(404, { code: "not_found", error: "Not found" });
  const [, resource, id] = m;
  if (resource === "members") return method === "GET" && !id ? reply(200, members) : reply(405, { code: "method_not_allowed", error: "Method not allowed" });
  if (method === "GET" && !id) return listTasks(url);
  if (method === "GET") {
    const t = tasks.find((x) => x.id === id);
    return t ? reply(200, pub(t)) : reply(404, { code: "not_found", error: "Task not found" });
  }
  if ((method === "POST" && !id) || ((method === "PATCH" || method === "DELETE") && id)) return write(method, url, headers, raw, id);
  return reply(405, { code: "method_not_allowed", error: "Method not allowed" });
}

function handleTest(method, url, raw) {
  if (method !== "POST") return reply(405, { code: "method_not_allowed", error: "Method not allowed" });
  if (url.pathname === "/_test/reset") { load(); return reply(200, { ok: true, tasks: tasks.length }); }
  const m = url.pathname.match(/^\/_test\/colleague-edit\/([^/]+)$/);
  if (!m) return reply(404, { code: "not_found", error: "Not found" });
  let body;
  try { body = JSON.parse(raw || "{}"); } catch { return reply(400, { code: "invalid_json", error: "Invalid JSON" }); }
  const t = tasks.find((x) => x.id === m[1]);
  if (!t) return reply(404, { code: "not_found", error: "Task not found" });
  const errs = validate(body, false);
  if (errs.length) return validation(errs);
  const changed = applyFields(t, body);
  if (body.delete === true && !t.deleted) { t.deleted = true; touch(t); } else if (changed) touch(t);
  return reply(200, pub(t));
}

const server = http.createServer((req, res) => {
  if (req.method === "OPTIONS") return send(res, 204, {});
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    const url = new URL(req.url, "http://localhost");
    if (url.pathname.startsWith("/_test/")) {
      const out = handleTest(req.method, url, raw);
      return send(res, out.status, out.body, out.headers);
    }
    const delay = NO_LATENCY ? 0 : 150 + Math.floor(random() * 750);
    setTimeout(() => {
      const fails = random() < FAIL_RATE;
      const lostAnswer = fails && req.method !== "GET" && random() < 0.5;
      if (fails && !lostAnswer) return send(res, 500, { code: "simulated_error", error: "Simulated server error, please retry" });
      const out = handle(req.method, url, req.headers, raw);
      if (lostAnswer) return send(res, 500, { code: "simulated_error", error: "Simulated server error, please retry" });
      send(res, out.status, out.body, out.headers);
    }, delay);
  });
});
server.listen(PORT, () => console.log("Mock API on http://localhost:" + PORT + "/tasks"));
