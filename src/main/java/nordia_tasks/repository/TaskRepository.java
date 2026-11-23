package nordia_tasks.repository;

import nordia_tasks.entity.Task;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") String id);

    // Première page (pas de curseur)
    List<Task> findAllByOrderByUpdatedAtAscIdAsc(Pageable pageable);

    // Pages suivantes : tout ce qui est strictement après (updatedAt, id)
    @Query("""
            SELECT t FROM Task t
            WHERE t.updatedAt > :time
               OR (t.updatedAt = :time AND t.id > :id)
            ORDER BY t.updatedAt ASC, t.id ASC
            """)
    List<Task> findChangedAfter(@Param("time") Instant time,
                                @Param("id") String id,
                                Pageable pageable);
}