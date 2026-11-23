package nordia_tasks.controller;

import nordia_tasks.entity.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Erreur API qui peut porter la tâche courante (409 / 410). */
public class ApiException extends ResponseStatusException {

    private final Task current;

    public ApiException(HttpStatus status, String code, Task current) {
        super(status, code);
        this.current = current;
    }

    public Task getCurrent() {
        return current;
    }
}