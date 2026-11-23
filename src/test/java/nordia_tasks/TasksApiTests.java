package nordia_tasks;

import nordia_tasks.repository.IdempotencyRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:nordia_test;DB_CLOSE_DELAY=-1")
class TasksApiTests {

    @Value("${local.server.port}")
    int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired IdempotencyRepository idempotencyRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    // ------------------------------------------------------------- helpers

    record Resp(int status, Map<String, Object> body, String replay) {}

    @SuppressWarnings("unchecked")
    private Resp send(String method, String path, String key, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));

        HttpResponse<String> r = http.send(b.build(), BodyHandlers.ofString());
        String text = r.body();
        Map<String, Object> body = (text == null || text.isBlank())
                ? Map.of()
                : objectMapper.readValue(text, Map.class);
        return new Resp(r.statusCode(), body, r.headers().firstValue("Idempotent-Replay").orElse(null));
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private Resp createTask(String id, String extraJson) throws Exception {
        String json = "{\"id\":\"" + id + "\",\"title\":\"Tache de test\"" + extraJson + "}";
        return send("POST", "/tasks", uuid(), json);
    }

    private Resp patch(String id, String key, String json) throws Exception {
        return send("PATCH", "/tasks/" + id, key, json);
    }

    private int version(Resp r) {
        return ((Number) r.body().get("version")).intValue();
    }

    // Deux appels lancés en même temps (départ synchronisé par un latch)
    private List<Resp> parallel(Callable<Resp> a, Callable<Resp> b) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (Callable<Resp> c : List.of(a, b)) {
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    return c.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                out.add(f.get(15, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    // Parcourt toutes les pages et renvoie le dernier curseur
    private String cursorAtEnd() throws Exception {
        String cursor = null;
        while (true) {
            String path = "/tasks?limit=50" + (cursor == null ? "" : "&since=" + cursor);
            Resp r = send("GET", path, null, null);
            cursor = (String) r.body().get("nextCursor");
            if (!Boolean.TRUE.equals(r.body().get("hasMore"))) {
                return cursor;
            }
        }
    }

    // --------------------------------------------------------------- tests

    @Test
    void postReplayWithSameKeyReturnsOriginalAnswer() throws Exception {
        String id = uuid();
        String key = uuid();
        String json = "{\"id\":\"" + id + "\",\"title\":\"Replay\"}";

        Resp first = send("POST", "/tasks", key, json);
        Resp second = send("POST", "/tasks", key, json);

        assertEquals(201, first.status());
        assertEquals(201, second.status());
        assertNull(first.replay());
        assertEquals("true", second.replay());
        assertEquals(first.body(), second.body());
        assertEquals(1, version(send("GET", "/tasks/" + id, null, null)));
    }

    @Test
    void patchReplayDoesNotApplyTwice() throws Exception {
        String id = uuid();
        createTask(id, "");
        String key = uuid();
        String json = "{\"baseVersion\":1,\"title\":\"Nouveau titre\"}";

        Resp first = patch(id, key, json);
        Resp second = patch(id, key, json);

        assertEquals(200, first.status());
        assertEquals(200, second.status());
        assertEquals("true", second.replay());
        assertEquals(2, version(first));
        assertEquals(2, version(second));
        assertEquals(2, version(send("GET", "/tasks/" + id, null, null)));
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() throws Exception {
        String key = uuid();
        Resp first = send("POST", "/tasks", key, "{\"id\":\"" + uuid() + "\",\"title\":\"A\"}");
        Resp second = send("POST", "/tasks", key, "{\"id\":\"" + uuid() + "\",\"title\":\"B\"}");

        assertEquals(201, first.status());
        assertEquals(422, second.status());
        assertEquals("idempotency_key_reuse", second.body().get("code"));
    }

    @Test
    void twoConcurrentUpdatesWithSameBaseVersion_onlyOneWins() throws Exception {
        String id = uuid();
        createTask(id, "");

        List<Resp> results = parallel(
                () -> patch(id, uuid(), "{\"baseVersion\":1,\"title\":\"Version A\"}"),
                () -> patch(id, uuid(), "{\"baseVersion\":1,\"title\":\"Version B\"}"));

        List<Integer> statuses = new ArrayList<>(results.stream().map(Resp::status).toList());
        Collections.sort(statuses);
        assertEquals(List.of(200, 409), statuses);

        Resp loser = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertEquals("version_conflict", loser.body().get("code"));
        assertNotNull(loser.body().get("current"));
        assertEquals(2, version(send("GET", "/tasks/" + id, null, null)));
    }

    @Test
    void twoConcurrentRequestsWithSameKey_appliedOnce() throws Exception {
        String id = uuid();
        createTask(id, "");
        String key = uuid();
        String json = "{\"baseVersion\":1,\"title\":\"Meme cle\"}";

        List<Resp> results = parallel(
                () -> patch(id, key, json),
                () -> patch(id, key, json));

        assertEquals(200, results.get(0).status());
        assertEquals(200, results.get(1).status());
        assertEquals(2, version(results.get(0)));
        assertEquals(2, version(results.get(1)));
        assertEquals(2, version(send("GET", "/tasks/" + id, null, null)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void staleBaseVersionReturns409WithCurrentTask() throws Exception {
        String id = uuid();
        createTask(id, "");
        assertEquals(200, patch(id, uuid(), "{\"baseVersion\":1,\"title\":\"Premier\"}").status());

        Resp stale = patch(id, uuid(), "{\"baseVersion\":1,\"title\":\"Perime\"}");

        assertEquals(409, stale.status());
        assertEquals("version_conflict", stale.body().get("code"));
        Map<String, Object> current = (Map<String, Object>) stale.body().get("current");
        assertEquals(2, ((Number) current.get("version")).intValue());
        assertEquals("Premier", current.get("title"));
    }

    @Test
    void doneCannotGoBackToTodoButCanGoToDoing() throws Exception {
        String id = uuid();
        createTask(id, ",\"status\":\"done\"");

        Resp back = patch(id, uuid(), "{\"baseVersion\":1,\"status\":\"todo\"}");
        assertEquals(422, back.status());
        assertEquals("invalid_transition", back.body().get("code"));

        Resp doing = patch(id, uuid(), "{\"baseVersion\":1,\"status\":\"doing\"}");
        assertEquals(200, doing.status());
        assertEquals("doing", doing.body().get("status"));
    }

    @Test
    void invalidValuesAreRejectedWith422() throws Exception {
        String id = uuid();
        createTask(id, "");

        List<String> badBodies = List.of(
                "{\"baseVersion\":1,\"title\":\"\"}",
                "{\"baseVersion\":1,\"title\":\"" + "x".repeat(121) + "\"}",
                "{\"baseVersion\":1,\"assigneeId\":\"m-99\"}",
                "{\"baseVersion\":1,\"dueDate\":\"2026-02-30\"}",
                "{\"baseVersion\":1,\"status\":\"finished\"}",
                "{\"baseVersion\":1,\"color\":\"red\"}",
                "{\"baseVersion\":1}");

        for (String body : badBodies) {
            Resp r = patch(id, uuid(), body);
            assertEquals(422, r.status(), "attendu 422 pour " + body);
            assertEquals("validation_error", r.body().get("code"), body);
        }
        // Un refus ne change rien
        assertEquals(1, version(send("GET", "/tasks/" + id, null, null)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void deletionIsATombstoneSeenIncrementallyAndLaterUpdatesGet410() throws Exception {
        String cursor = cursorAtEnd();
        String id = uuid();
        createTask(id, "");

        Resp deleted = send("DELETE", "/tasks/" + id + "?baseVersion=1", uuid(), null);
        assertEquals(200, deleted.status());
        assertEquals(true, deleted.body().get("deleted"));
        assertEquals(2, version(deleted));

        Resp page = send("GET", "/tasks?limit=50&since=" + cursor, null, null);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.body().get("items");
        Map<String, Object> tombstone = items.stream()
                .filter(t -> id.equals(t.get("id")))
                .findFirst().orElseThrow();
        assertEquals(true, tombstone.get("deleted"));
        assertEquals(2, ((Number) tombstone.get("version")).intValue());

        Resp afterDelete = patch(id, uuid(), "{\"baseVersion\":2,\"title\":\"Trop tard\"}");
        assertEquals(410, afterDelete.status());
        assertEquals("task_deleted", afterDelete.body().get("code"));
        assertNotNull(afterDelete.body().get("current"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void paginationHasNoDuplicatesAndAlwaysGivesAUsableCursor() throws Exception {
        Set<Object> seen = new HashSet<>();
        String cursor = null;
        boolean hasMore = true;
        while (hasMore) {
            String path = "/tasks?limit=7" + (cursor == null ? "" : "&since=" + cursor);
            Resp r = send("GET", path, null, null);
            assertEquals(200, r.status());
            for (Map<String, Object> item : (List<Map<String, Object>>) r.body().get("items")) {
                assertTrue(seen.add(item.get("id")), "doublon : " + item.get("id"));
            }
            cursor = (String) r.body().get("nextCursor");
            hasMore = Boolean.TRUE.equals(r.body().get("hasMore"));
        }
        assertTrue(seen.size() >= 45);
        assertNotNull(cursor, "la dernière page doit aussi donner un curseur");

        Resp nothingNew = send("GET", "/tasks?limit=7&since=" + cursor, null, null);
        assertTrue(((List<?>) nothingNew.body().get("items")).isEmpty());
        assertEquals(cursor, nothingNew.body().get("nextCursor"));

        Resp bad = send("GET", "/tasks?since=not-a-cursor", null, null);
        assertEquals(400, bad.status());
        assertEquals("invalid_cursor", bad.body().get("code"));
    }

    @Test
    void writeWithoutIdempotencyKeyIsRejected() throws Exception {
        Resp r = send("POST", "/tasks", null, "{\"id\":\"" + uuid() + "\",\"title\":\"Sans cle\"}");
        assertEquals(400, r.status());
        assertEquals("missing_idempotency_key", r.body().get("code"));
    }

    @Test
    void idempotencyRecordIsStoredInTheDatabase() throws Exception {
        String key = uuid();
        send("POST", "/tasks", key, "{\"id\":\"" + uuid() + "\",\"title\":\"Persistee\"}");
        assertTrue(idempotencyRepository.findById(key).isPresent());
    }
}