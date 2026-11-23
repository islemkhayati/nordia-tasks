
package nordia_tasks.repository;

import nordia_tasks.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository
        extends JpaRepository<IdempotencyRecord, String> {
}
