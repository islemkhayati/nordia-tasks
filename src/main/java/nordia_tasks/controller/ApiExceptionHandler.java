package nordia_tasks.controller;

import nordia_tasks.entity.IdempotencyRecord;
import nordia_tasks.entity.Task;
import nordia_tasks.repository.IdempotencyRepository;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final IdempotencyRepository idempotencyRepository;

    public ApiExceptionHandler(IdempotencyRepository idempotencyRepository) {
        this.idempotencyRepository = idempotencyRepository;
    }

    private ResponseEntity<Map<String, Object>> build(
            HttpStatusCode status, String code, Task current) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", getErrorMessage(code));
        if (current != null) {
            body.put("current", current);
        }
        return ResponseEntity.status(status).body(body);
    }

    // JSON invalide -> 400 invalid_json
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidJson(
            HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_json", null);
    }

    // Erreurs applicatives (avec ou sans "current")
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException e) {

        String code = e.getReason() != null ? e.getReason() : "error";
        Task current = (e instanceof ApiException ae) ? ae.getCurrent() : null;
        return build(e.getStatusCode(), code, current);
    }

    // Paramètre manquant (ex : baseVersion sur DELETE)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException e) {
        return paramError(e.getParameterName());
    }

    // Paramètre mal typé (ex : baseVersion=abc, limit=abc)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleBadParamType(
            MethodArgumentTypeMismatchException e) {
        return paramError(e.getName());
    }

    // baseVersion -> 422 ; le reste (limit...) -> 400. A comparer avec server.js.
    private ResponseEntity<Map<String, Object>> paramError(String name) {
        HttpStatus status = "baseVersion".equals(name)
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;
        return build(status, "validation_error", null);
    }

    // Route inconnue -> 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "not_found", null);
    }

    // Deux POST simultanés avec la même clé / le même id : le perdant rejoue
    // la réponse du gagnant (déjà validée en base) au lieu d'un 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleIntegrity(
            DataIntegrityViolationException e, HttpServletRequest request) {

        String key = request.getHeader("Idempotency-Key");
        if (key != null && !key.isBlank()) {
            Optional<IdempotencyRecord> record = idempotencyRepository.findById(key);
            if (record.isPresent()) {
                return ResponseEntity
                        .status(record.get().getStatusCode())
                        .header("Idempotent-Replay", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(record.get().getResponseJson());
            }
        }
        return build(HttpStatus.CONFLICT, "id_exists", null);
    }

    // Dernier recours : toujours le format {code, error}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", null);
    }

    private String getErrorMessage(String code) {
        return switch (code) {
            case "validation_error" -> "The request contains invalid data.";
            case "not_found" -> "The requested task was not found.";
            case "version_conflict" -> "The task version does not match the current version.";
            case "task_deleted" -> "The task has already been deleted.";
            case "id_exists" -> "A task with this ID already exists.";
            case "missing_idempotency_key" -> "The Idempotency-Key header is required.";
            case "invalid_cursor" -> "The supplied cursor is invalid.";
            case "invalid_transition" -> "This status transition is not allowed.";
            case "idempotency_key_reuse" -> "This idempotency key was already used for another request.";
            case "invalid_json" -> "The request contains invalid JSON.";
            case "internal_error" -> "An unexpected server error occurred.";
            default -> "The request could not be processed.";
        };
    }
}