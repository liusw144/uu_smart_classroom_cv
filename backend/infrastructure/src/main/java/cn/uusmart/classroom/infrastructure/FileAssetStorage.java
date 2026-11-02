package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileAssetStorage implements AssetStorage {
  private final Path root;

  public FileAssetStorage(@Value("${classroom.assets.root:../runtime/assets}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  private Path path(long tenant, String id) {
    if (tenant < 1 || !UUID.fromString(id).toString().equals(id))
      throw ResourceProblem.invalid("素材编号无效");
    Path result = root.resolve(Long.toString(tenant)).resolve(id).normalize();
    if (!result.startsWith(root)) throw ResourceProblem.invalid("素材路径无效");
    return result;
  }

  public void write(long tenant, String id, byte[] content) {
    try {
      Path target = path(tenant, id);
      Files.createDirectories(target.getParent());
      Files.write(target, content, StandardOpenOption.CREATE_NEW);
    } catch (IOException error) {
      throw new IllegalStateException("素材写入失败", error);
    }
  }

  public byte[] read(long tenant, String id) {
    try {
      return Files.readAllBytes(path(tenant, id));
    } catch (NoSuchFileException error) {
      throw ResourceProblem.missing();
    } catch (IOException error) {
      throw new IllegalStateException("素材读取失败", error);
    }
  }

  public void remove(long tenant, String id) {
    try {
      Files.deleteIfExists(path(tenant, id));
    } catch (IOException error) {
      throw new IllegalStateException("素材清理失败", error);
    }
  }
}
