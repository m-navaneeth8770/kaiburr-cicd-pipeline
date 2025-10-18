package com.kaiburr.task_api;

// KUBERNETES CLIENT IMPORTS
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping; // <-- REQUIRED IMPORT
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

@CrossOrigin(origins = "*") // <-- APPLIED ONCE AT THE CLASS LEVEL
@RestController
@RequestMapping("/tasks")
public class TaskController {

    @Autowired
    private TaskRepository taskRepository;

    // ... All your other methods (getTasks, findTasksByName, etc.) go here ...
    // ... No changes are needed inside the other methods ...
    
    @GetMapping
    public ResponseEntity<?> getTasks(@RequestParam(required = false) String id) {
        if (id != null) {
            Optional<Task> task = taskRepository.findById(id);
            if (task.isPresent()) {
                return ResponseEntity.ok(task.get());
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Task with ID " + id + " not found.");
            }
        } else {
            List<Task> tasks = taskRepository.findAll();
            return ResponseEntity.ok(tasks);
        }
    }

    @GetMapping("/findByName")
    public ResponseEntity<List<Task>> findTasksByName(@RequestParam String name) {
        List<Task> tasks = taskRepository.findByNameContaining(name);
        if (tasks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(tasks);
    }

    @PutMapping
    public ResponseEntity<Task> createTask(@RequestBody Task task) {
        Task savedTask = taskRepository.save(task);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedTask);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteTask(@PathVariable String id) {
        if (!taskRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Task with ID " + id + " not found.");
        }
        taskRepository.deleteById(id);
        return ResponseEntity.ok("Task with ID " + id + " deleted successfully.");
    }

    @PutMapping("/{id}/executions")
    public ResponseEntity<?> executeTask(@PathVariable String id) {
        Optional<Task> taskOptional = taskRepository.findById(id);
        if (taskOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Task with ID " + id + " not found.");
        }
    
        Task task = taskOptional.get();
        TaskExecution execution = new TaskExecution();
        execution.setStartTime(new Date());
    
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            final String podName = "task-runner-" + task.getId() + "-" + System.currentTimeMillis();
    
            Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never") 
                    .addNewContainer()
                        .withName("task-runner-container")
                        .withImage("busybox")
                        .withCommand("sh", "-c", task.getCommand())
                    .endContainer()
                .endSpec()
                .build();
    
            client.pods().inNamespace("default").create(pod);
    
            client.pods().inNamespace("default").withName(podName).waitUntilCondition(
                p -> p != null && ("Succeeded".equals(p.getStatus().getPhase()) || "Failed".equals(p.getStatus().getPhase())),
                5, TimeUnit.MINUTES);
    
            String podLogs = client.pods().inNamespace("default").withName(podName).getLog();
            execution.setOutput(podLogs);
    
            client.pods().inNamespace("default").withName(podName).delete();
    
        } catch (Exception e) {
            execution.setOutput("Failed to execute in Kubernetes pod: " + e.getMessage());
            e.printStackTrace();
        }
    
        execution.setEndTime(new Date());
    
        if (task.getTaskExecutions() == null) {
            task.setTaskExecutions(new ArrayList<>());
        }
        task.getTaskExecutions().add(execution);
        Task updatedTask = taskRepository.save(task);
    
        return ResponseEntity.ok(updatedTask);
    }
}