package cn.uusmart.classroom.api.teaching;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SaCheckPermission("session:read")
public class TeachingController {
  private final TeachingService service;
  private final CurrentActor current;

  public TeachingController(TeachingService service, CurrentActor current) {
    this.service = service;
    this.current = current;
  }

  public record ClassInput(
      @NotBlank @Size(max = 128) String name,
      @NotBlank @Size(max = 64) String grade,
      @NotNull @Min(0) @Max(500) Integer studentCount,
      @Positive Long ownerId) {}

  public record CourseInput(
      @NotBlank @Size(max = 128) String name,
      @Positive long classId,
      @NotBlank @Size(max = 64) String term) {}

  public record TaskInput(
      @NotBlank @Size(max = 128) String name,
      @Positive long courseId,
      Instant scheduledAt,
      Instant scheduledEndAt,
      @Size(max = 128) String roomName,
      @Min(0) Long version) {}

  public record TransitionInput(
      @NotBlank @Pattern(regexp = "START|COMPLETE|ARCHIVE") String action,
      @NotNull @Min(0) Long version,
      Instant occurredAt) {}

  @GetMapping("/classes")
  public ApiResponse<Page<SchoolClass>> classes(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.classes(current.get(), q, page, size));
  }

  @GetMapping("/classes/{id}")
  public ApiResponse<SchoolClass> schoolClass(@PathVariable long id) {
    return new ApiResponse<>(service.schoolClass(current.get(), id));
  }

  @PostMapping("/classes")
  public ApiResponse<SchoolClass> createClass(@Valid @RequestBody ClassInput input) {
    return saveClass(null, input);
  }

  @PutMapping("/classes/{id}")
  public ApiResponse<SchoolClass> updateClass(
      @PathVariable long id, @Valid @RequestBody ClassInput input) {
    return saveClass(id, input);
  }

  private ApiResponse<SchoolClass> saveClass(Long id, ClassInput input) {
    return new ApiResponse<>(
        service.saveClass(
            current.get(),
            id,
            input.ownerId(),
            input.name().trim(),
            input.grade().trim(),
            input.studentCount()));
  }

  @DeleteMapping("/classes/{id}")
  public ApiResponse<Boolean> deleteClass(@PathVariable long id) {
    service.deleteClass(current.get(), id);
    return new ApiResponse<>(true);
  }

  @GetMapping("/courses")
  public ApiResponse<Page<Course>> courses(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.courses(current.get(), q, page, size));
  }

  @GetMapping("/courses/{id}")
  public ApiResponse<Course> course(@PathVariable long id) {
    return new ApiResponse<>(service.course(current.get(), id));
  }

  @PostMapping("/courses")
  public ApiResponse<Course> createCourse(@Valid @RequestBody CourseInput input) {
    return saveCourse(null, input);
  }

  @PutMapping("/courses/{id}")
  public ApiResponse<Course> updateCourse(
      @PathVariable long id, @Valid @RequestBody CourseInput input) {
    return saveCourse(id, input);
  }

  private ApiResponse<Course> saveCourse(Long id, CourseInput input) {
    return new ApiResponse<>(
        service.saveCourse(
            current.get(), id, input.name().trim(), input.classId(), input.term().trim()));
  }

  @DeleteMapping("/courses/{id}")
  public ApiResponse<Boolean> deleteCourse(@PathVariable long id) {
    service.deleteCourse(current.get(), id);
    return new ApiResponse<>(true);
  }

  @GetMapping("/tasks")
  public ApiResponse<Page<LessonTask>> tasks(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.tasks(current.get(), q, page, size));
  }

  @GetMapping("/tasks/{id}")
  public ApiResponse<LessonTask> task(@PathVariable long id) {
    return new ApiResponse<>(service.task(current.get(), id));
  }

  @PostMapping("/tasks")
  public ApiResponse<LessonTask> createTask(@Valid @RequestBody TaskInput input) {
    return saveTask(null, input);
  }

  @PutMapping("/tasks/{id}")
  public ApiResponse<LessonTask> updateTask(
      @PathVariable long id, @Valid @RequestBody TaskInput input) {
    return saveTask(id, input);
  }

  private ApiResponse<LessonTask> saveTask(Long id, TaskInput input) {
    return new ApiResponse<>(
        service.saveTask(
            current.get(),
            id,
            new LessonTaskEdit(
                input.name().trim(),
                input.courseId(),
                input.scheduledAt(),
                input.scheduledEndAt(),
                input.roomName(),
                input.version())));
  }

  @PostMapping("/tasks/{id}/transition")
  public ApiResponse<LessonTask> transition(
      @PathVariable long id, @Valid @RequestBody TransitionInput input) {
    return new ApiResponse<>(
        service.transitionTask(
            current.get(), id, input.action(), input.version(), input.occurredAt()));
  }

  @GetMapping("/tasks/{id}/events")
  public ApiResponse<Page<LessonTaskEvent>> events(
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.taskEvents(current.get(), id, page, size));
  }

  @DeleteMapping("/tasks/{id}")
  public ApiResponse<Boolean> deleteTask(@PathVariable long id) {
    service.deleteTask(current.get(), id);
    return new ApiResponse<>(true);
  }
}
