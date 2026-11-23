package nordia_tasks.controller;

import nordia_tasks.entity.IdempotencyRecord;
import nordia_tasks.entity.Task;
import nordia_tasks.repository.IdempotencyRepository;
import nordia_tasks.repository.MemberRepository;
import nordia_tasks.repository.TaskRepository;

import tools.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/tasks")
public class TasksController {

    private static final List<String> STATUSES = List.of("todo", "doing", "done");

    private final TaskRepository taskRepository;
    private final MemberRepository memberRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public TasksController(
            TaskRepository taskRepository,
            MemberRepository memberRepository,
            IdempotencyRepository idempotencyRepository,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.memberRepository = memberRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- helpers

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error");
    }

    // Millisecondes : tri et curseur stables
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // Requête en JSON à clés triées, pour comparer proprement les rejeux
    private String canonicalJson(Map<String, Object> request) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(request));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        }
    }

    private void checkIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_idempotency_key");
        }
    }

    private ResponseEntity<?> replayIfPresent(
            String key, String method, String resourceId, String requestJson) {

        Optional<IdempotencyRecord> optional = idempotencyRepository.findById(key);
        if (optional.isEmpty()) {
            return null;
        }

        IdempotencyRecord previous = optional.get();

        if (!method.equals(previous.getMethod())
                || !Objects.equals(resourceId, previous.getResourceId())
                || !requestJson.equals(previous.getRequestJson())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reuse");
        }

        try {
            Task response = objectMapper.readValue(previous.getResponseJson(), Task.class);
            return ResponseEntity
                    .status(previous.getStatusCode())
                    .header("Idempotent-Replay", "true")
                    .body(response);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        }
    }

    private void saveIdempotency(
            String key, String method, String resourceId,
            String requestJson, Task response, int statusCode) {
        try {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setIdempotencyKey(key);
            record.setMethod(method);
            record.setResourceId(resourceId);
            record.setRequestJson(requestJson);
            record.setResponseJson(objectMapper.writeValueAsString(response));
            record.setStatusCode(statusCode);
            record.setCreatedAt(Instant.now());
            idempotencyRepository.save(record);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        }
    }

    // Rejette les champs inconnus
    private void validateAllowedFields(Map<String, Object> request, Set<String> allowed) {
        for (String field : request.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid();
            }
        }
    }

    // Lit un champ String optionnel (null accepté)
    private String optionalString(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw invalid();
        }
        return (String) value;
    }

    private void validateAssignee(String assigneeId) {
        if (assigneeId != null && !memberRepository.existsById(assigneeId)) {
            throw invalid();
        }
    }

    // ------------------------------------------------------------------- GET

    // GET /tasks?since=...&limit=...
    @GetMapping
    public Map<String, Object> getTasks(
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "20") int limit) {

        // A comparer avec server.js (400 ou limite ramenée dans [1..50] ?)
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validation_error");
        }

        Instant cursorTime = null;
        String cursorId = null;

        // Décodage du curseur : "<updatedAt>|<id>" en Base64 URL
        if (since != null && !since.isBlank()) {
            try {
                String decoded = new String(
                        Base64.getUrlDecoder().decode(since), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                if (parts.length != 2 || parts[1].isBlank()) {
                    throw new IllegalArgumentException();
                }
                cursorTime = Instant.parse(parts[0]);
                cursorId = parts[1];
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_cursor");
            }
        }

        // limit + 1 lignes pour savoir s'il reste une page
        Pageable page = PageRequest.of(0, limit + 1);
        List<Task> rows = (cursorTime == null)
                ? taskRepository.findAllByOrderByUpdatedAtAscIdAsc(page)
                : taskRepository.findChangedAfter(cursorTime, cursorId, page);

        boolean hasMore = rows.size() > limit;
        List<Task> items = hasMore ? rows.subList(0, limit) : rows;

        // Toujours renvoyer un curseur utilisable pour la prochaine synchro :
        // celui du dernier élément, ou le curseur reçu s'il n'y a rien de neuf.
        String nextCursor = (since == null || since.isBlank()) ? null : since;
        if (!items.isEmpty()) {
            Task last = items.get(items.size() - 1);
            String cursorData = last.getUpdatedAt() + "|" + last.getId();
            nextCursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(cursorData.getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("nextCursor", nextCursor);
        response.put("hasMore", hasMore);
        return response;
    }

    // GET /tasks/{id}
    @GetMapping("/{id}")
    public ResponseEntity<?> getTaskById(@PathVariable String id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found"));
        return ResponseEntity.ok(task);
    }

    // ------------------------------------------------------------------ POST

    @PostMapping
    @Transactional
    public ResponseEntity<?> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> request) {

        checkIdempotencyKey(idempotencyKey);

        validateAllowedFields(request,
                Set.of("id", "title", "note", "status", "assigneeId", "dueDate"));

        String requestJson = canonicalJson(request);
        ResponseEntity<?> replay = replayIfPresent(idempotencyKey, "POST", "", requestJson);
        if (replay != null) {
            return replay;
        }

        String id = optionalString(request, "id");
        String title = optionalString(request, "title");
        String note = optionalString(request, "note");
        String status = request.containsKey("status")
                ? optionalString(request, "status")
                : "todo";
        String assigneeId = optionalString(request, "assigneeId");
        String dueDate = optionalString(request, "dueDate");

        // Champs obligatoires
        if (id == null || id.isBlank()
                || title == null || title.isBlank() || title.length() > 120
                || status == null) {
            throw invalid();
        }

        // id = UUID
        try {
            UUID.fromString(id);
        } catch (Exception e) {
            throw invalid();
        }

        // id déjà utilisé : 409 avec la tâche existante
        Optional<Task> existing = taskRepository.findById(id);
        if (existing.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "id_exists", existing.get());
        }

        if (!STATUSES.contains(status)) {
            throw invalid();
        }
        if (note != null && note.length() > 500) {
            throw invalid();
        }
        validateAssignee(assigneeId);

        LocalDate parsedDueDate = null;
        if (dueDate != null) {
            try {
                parsedDueDate = LocalDate.parse(dueDate);
            } catch (Exception e) {
                throw invalid();
            }
        }

        Task task = new Task();
        task.setId(id);
        task.setTitle(title);
        task.setNote(note);
        task.setStatus(status);
        task.setAssigneeId(assigneeId);
        task.setDueDate(parsedDueDate);
        task.setVersion(1);
        task.setDeleted(false);
        task.setUpdatedAt(now());

        Task saved = taskRepository.save(task);

        saveIdempotency(idempotencyKey, "POST", "", requestJson, saved,
                HttpStatus.CREATED.value());

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ----------------------------------------------------------------- PATCH

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateTask(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> request) {

        checkIdempotencyKey(idempotencyKey);

        validateAllowedFields(request,
                Set.of("baseVersion", "title", "note", "status", "assigneeId", "dueDate"));

        String requestJson = canonicalJson(request);
        ResponseEntity<?> replay = replayIfPresent(idempotencyKey, "PATCH", id, requestJson);
        if (replay != null) {
            return replay;
        }

        // Verrou d'écriture sur la ligne : un seul PATCH à la fois par tâche
        Task task = taskRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found"));

        // Une requête avec la même clé a pu être validée pendant l'attente du verrou :
        // on revérifie pour rejouer sa réponse au lieu de renvoyer un faux 409.
        ResponseEntity<?> replayAfterLock =
                replayIfPresent(idempotencyKey, "PATCH", id, requestJson);
        if (replayAfterLock != null) {
            return replayAfterLock;
        }

        if (task.isDeleted()) {
            throw new ApiException(HttpStatus.GONE, "task_deleted", task);
        }

        // baseVersion : entier obligatoire
        Object baseVersionValue = request.get("baseVersion");
        if (!(baseVersionValue instanceof Number number)) {
            throw invalid();
        }
        int baseVersion = number.intValue();
        if (number.doubleValue() != baseVersion) {
            throw invalid();
        }

        if (baseVersion != task.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "version_conflict", task);
        }

        boolean changed = false;

        if (request.containsKey("title")) {
            Object value = request.get("title");
            if (!(value instanceof String s) || s.isBlank() || s.length() > 120) {
                throw invalid();
            }
            task.setTitle(s);
            changed = true;
        }

        if (request.containsKey("note")) {
            Object value = request.get("note");
            if (value != null && (!(value instanceof String s) || s.length() > 500)) {
                throw invalid();
            }
            task.setNote((String) value);
            changed = true;
        }

        if (request.containsKey("status")) {
            Object value = request.get("status");
            if (!(value instanceof String s) || !STATUSES.contains(s)) {
                throw invalid();
            }
            // done -> todo interdit (done -> doing autorisé)
            if ("done".equals(task.getStatus()) && "todo".equals(s)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_transition");
            }
            task.setStatus(s);
            changed = true;
        }

        if (request.containsKey("assigneeId")) {
            Object value = request.get("assigneeId");
            if (value != null && !(value instanceof String)) {
                throw invalid();
            }
            validateAssignee((String) value);
            task.setAssigneeId((String) value);
            changed = true;
        }

        if (request.containsKey("dueDate")) {
            Object value = request.get("dueDate");
            if (value == null) {
                task.setDueDate(null);
            } else if (value instanceof String s) {
                try {
                    task.setDueDate(LocalDate.parse(s));
                } catch (Exception e) {
                    throw invalid();
                }
            } else {
                throw invalid();
            }
            changed = true;
        }

        // Au moins un champ doit changer
        if (!changed) {
            throw invalid();
        }

        task.setVersion(task.getVersion() + 1);
        task.setUpdatedAt(now());

        Task saved = taskRepository.save(task);

        saveIdempotency(idempotencyKey, "PATCH", id, requestJson, saved,
                HttpStatus.OK.value());

        return ResponseEntity.ok(saved);
    }

    // ---------------------------------------------------------------- DELETE

    // DELETE /tasks/{id}?baseVersion=1
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteTask(
            @PathVariable String id,
            @RequestParam int baseVersion,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        checkIdempotencyKey(idempotencyKey);

        Map<String, Object> deleteRequest = new LinkedHashMap<>();
        deleteRequest.put("baseVersion", baseVersion);
        String requestJson = canonicalJson(deleteRequest);

        ResponseEntity<?> replay = replayIfPresent(idempotencyKey, "DELETE", id, requestJson);
        if (replay != null) {
            return replay;
        }

        Task task = taskRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found"));

        // Revérification après le verrou (même raison que pour PATCH)
        ResponseEntity<?> replayAfterLock =
                replayIfPresent(idempotencyKey, "DELETE", id, requestJson);
        if (replayAfterLock != null) {
            return replayAfterLock;
        }

        if (task.isDeleted()) {
            throw new ApiException(HttpStatus.GONE, "task_deleted", task);
        }

        if (baseVersion != task.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "version_conflict", task);
        }

        // Suppression logique (tombstone) : la version avance aussi
        task.setDeleted(true);
        task.setVersion(task.getVersion() + 1);
        task.setUpdatedAt(now());

        Task saved = taskRepository.save(task);

        saveIdempotency(idempotencyKey, "DELETE", id, requestJson, saved,
                HttpStatus.OK.value());

        return ResponseEntity.ok(saved);
    }
}