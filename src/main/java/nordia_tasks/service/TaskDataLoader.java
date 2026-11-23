
package nordia_tasks.service;

import nordia_tasks.entity.Task;
import nordia_tasks.repository.TaskRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

@Component
public class TaskDataLoader implements CommandLineRunner {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public TaskDataLoader(
            TaskRepository taskRepository,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {

        if (taskRepository.count() == 0) {

            try (InputStream input =
                         new ClassPathResource("tasks.json").getInputStream()) {

                List<Task> tasks = objectMapper.readValue(
                        input,
                        new TypeReference<List<Task>>() {}
                );

                taskRepository.saveAll(tasks);

                System.out.println("Tasks imported: " + tasks.size());
            }
        }
    }
}
