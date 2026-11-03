package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileJobArtifacts implements JobArtifacts {
  private final Path root;
  private final long globalBytes, schoolBytes, maxFiles, minimumFree;

  public FileJobArtifacts(
      @Value("${classroom.artifacts.root:../runtime/artifacts}") String root,
      @Value("${classroom.artifacts.global-bytes:107374182400}") long globalBytes,
      @Value("${classroom.artifacts.school-bytes:10737418240}") long schoolBytes,
      @Value("${classroom.artifacts.max-files:500000}") long maxFiles,
      @Value("${classroom.artifacts.minimum-free-bytes:2147483648}") long minimumFree) {
    if (globalBytes < 1 || schoolBytes < 1 || maxFiles < 1 || minimumFree < 1)
      throw new IllegalArgumentException("产物存储预算无效");
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.globalBytes = globalBytes;
    this.schoolBytes = schoolBytes;
    this.maxFiles = maxFiles;
    this.minimumFree = minimumFree;
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

  private <T> T locked(Supplier<T> action) {
    try {
      Files.createDirectories(root);
      try (var channel =
              FileChannel.open(
                  root.resolve(".artifact-budget.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var lock = channel.tryLock()) {
        if (lock == null) throw new ServiceUnavailable("产物存储正忙，请重试");
        return action.get();
      }
    } catch (OverlappingFileLockException error) {
      throw new ServiceUnavailable("产物存储正忙，请重试");
    } catch (IOException error) {
      throw new ServiceUnavailable("产物存储容量无法核验");
    }
  }

  public void write(long tenant, String job, String lease, byte[] bytes, String hash) {
    if (bytes.length == 0
        || bytes.length > 32 * 1024 * 1024
        || !EdgeExecutionService.hash(bytes).equals(hash))
      throw ResourceProblem.invalid("产物大小或摘要无效");
    locked(
        () -> {
          writeLocked(tenant, job, lease, bytes, hash);
          return null;
        });
  }

  private void writeLocked(long tenant, String job, String lease, byte[] bytes, String hash) {
    Path path = target(tenant, job, lease);
    try {
      Files.createDirectories(path.getParent());
      if (Files.exists(path)) {
        if (!EdgeExecutionService.hash(Files.readAllBytes(path)).equals(hash))
          throw ResourceProblem.conflict("同一租约的产物内容发生变化");
        return;
      }
      long total = 0, school = 0, count = 0;
      try (var files = Files.walk(root)) {
        for (Path file : files.toList()) {
          if (Files.isSymbolicLink(file)) throw new ServiceUnavailable("产物卷不接受符号链接");
          if (!Files.isRegularFile(file)
              || file.getFileName().toString().equals(".artifact-budget.lock")) continue;
          long length = Files.size(file);
          total = Math.addExact(total, length);
          count++;
          if (file.startsWith(directory(tenant))) school = Math.addExact(school, length);
        }
      }
      if (total > globalBytes - bytes.length
          || school > schoolBytes - bytes.length
          || count >= maxFiles) throw new StorageLimit(false);
      if (Files.getFileStore(root).getUsableSpace() < minimumFree + bytes.length)
        throw new StorageLimit(true);
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
