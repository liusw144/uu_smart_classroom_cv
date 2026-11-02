package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileJobArtifacts implements JobArtifacts {
  private final Path root;

  public FileJobArtifacts(@Value("${classroom.artifacts.root:../runtime/artifacts}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  private Path directory(long tenant) {
    if (tenant < 1) throw ResourceProblem.invalid("学校编号无效");
    return root.resolve(Long.toString(tenant));
  }

  private Path target(long tenant, String job, String lease) {
    String name = UUID.fromString(job) + "-" + UUID.fromString(lease);
    Path target = directory(tenant).resolve(name).normalize();
    if (!target.startsWith(root) || Files.isSymbolicLink(target))
      throw ResourceProblem.invalid("产物路径无效");
    return target;
  }

  public void write(long tenant, String job, String lease, byte[] bytes, String hash) {
    Path path = target(tenant, job, lease);
    try {
      Files.createDirectories(path.getParent());
      if (Files.exists(path)) {
        if (!EdgeExecutionService.hash(Files.readAllBytes(path)).equals(hash))
          throw ResourceProblem.conflict("同一租约的产物内容发生变化");
        return;
      }
      Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".part");
      Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW);
      try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException error) {
      throw new IllegalStateException("产物保存失败", error);
    }
  }

  public byte[] read(long tenant, String job, String lease) {
    try {
      return Files.readAllBytes(target(tenant, job, lease));
    } catch (IOException error) {
      throw ResourceProblem.missing();
    }
  }

  public void removeJob(long tenant, String job) {
    String prefix = UUID.fromString(job).toString() + "-";
    Path directory = directory(tenant);
    if (!Files.isDirectory(directory)) return;
    try (var files = Files.newDirectoryStream(directory, prefix + "*")) {
      for (Path file : files) {
        if (file.toAbsolutePath().normalize().startsWith(root) && !Files.isSymbolicLink(file))
          Files.deleteIfExists(file);
      }
    } catch (IOException error) {
      throw new IllegalStateException("产物清理失败", error);
    }
  }
}
