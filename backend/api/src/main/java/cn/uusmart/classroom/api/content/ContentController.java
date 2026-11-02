package cn.uusmart.classroom.api.content;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.SchoolContentRepository.Catalog;
import cn.uusmart.classroom.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SaCheckPermission("session:read")
public class ContentController {
  private final SchoolContentService service;
  private final CurrentActor current;

  public ContentController(SchoolContentService service, CurrentActor current) {
    this.service = service;
    this.current = current;
  }

  public record NoticeInput(
      @NotBlank @Size(max = 128) String title,
      @NotBlank @Size(max = 20000) String content,
      @NotNull Boolean published) {}

  public record EntryInput(
      @NotBlank @Size(max = 128) String name,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{1,64}") String code,
      @NotNull @Size(max = 2000) String description,
      @NotNull Boolean enabled) {}

  @GetMapping("/notices")
  public ApiResponse<Page<Notice>> notices(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.notices(current.get(), q, page, size));
  }

  @GetMapping("/notices/{id}")
  public ApiResponse<Notice> notice(@PathVariable long id) {
    return new ApiResponse<>(service.notice(current.get(), id));
  }

  @PostMapping("/notices")
  public ApiResponse<Notice> createNotice(@Valid @RequestBody NoticeInput input) {
    return saveNotice(null, input);
  }

  @PutMapping("/notices/{id}")
  public ApiResponse<Notice> updateNotice(
      @PathVariable long id, @Valid @RequestBody NoticeInput input) {
    return saveNotice(id, input);
  }

  private ApiResponse<Notice> saveNotice(Long id, NoticeInput input) {
    return new ApiResponse<>(
        service.saveNotice(
            current.get(), id, input.title().trim(), input.content(), input.published()));
  }

  @DeleteMapping("/notices/{id}")
  public ApiResponse<Boolean> deleteNotice(@PathVariable long id) {
    service.deleteNotice(current.get(), id);
    return new ApiResponse<>(true);
  }

  private Catalog kind(String resource) {
    return resource.equals("models") ? Catalog.MODEL : Catalog.CATEGORY;
  }

  @GetMapping("/{resource:models|categories}")
  public ApiResponse<Page<CatalogEntry>> catalog(
      @PathVariable String resource,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.catalog(current.get(), kind(resource), q, page, size));
  }

  @GetMapping("/{resource:models|categories}/{id}")
  public ApiResponse<CatalogEntry> entry(@PathVariable String resource, @PathVariable long id) {
    return new ApiResponse<>(service.entry(current.get(), kind(resource), id));
  }

  @PostMapping("/{resource:models|categories}")
  public ApiResponse<CatalogEntry> createEntry(
      @PathVariable String resource, @Valid @RequestBody EntryInput input) {
    return saveEntry(resource, null, input);
  }

  @PutMapping("/{resource:models|categories}/{id}")
  public ApiResponse<CatalogEntry> updateEntry(
      @PathVariable String resource, @PathVariable long id, @Valid @RequestBody EntryInput input) {
    return saveEntry(resource, id, input);
  }

  private ApiResponse<CatalogEntry> saveEntry(String resource, Long id, EntryInput input) {
    return new ApiResponse<>(
        service.saveEntry(
            current.get(),
            kind(resource),
            id,
            input.name().trim(),
            input.code(),
            input.description(),
            input.enabled()));
  }

  @DeleteMapping("/{resource:models|categories}/{id}")
  public ApiResponse<Boolean> deleteEntry(@PathVariable String resource, @PathVariable long id) {
    service.deleteEntry(current.get(), kind(resource), id);
    return new ApiResponse<>(true);
  }
}
