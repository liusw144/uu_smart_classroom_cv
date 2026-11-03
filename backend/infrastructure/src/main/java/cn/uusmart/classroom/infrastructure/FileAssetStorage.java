package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.UUID;
import java.util.function.Supplier;
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
      if (Files.exists(target)) throw ResourceProblem.conflict("素材编号已存在");
      Path temporary = target.resolveSibling(id + ".part");
      try (var output =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) output.write(buffer);
        output.force(true);
      }
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException error) {
      throw new ServiceUnavailable("素材写入暂不可用");
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
      Files.deleteIfExists(path(tenant, id).resolveSibling(id + ".part"));
    } catch (IOException error) {
      throw new ServiceUnavailable("素材清理暂不可用，已保留清理账本");
    }
  }

  public void requireCapacity(long pending, long incoming, long minimumFree) {
    try {
      Files.createDirectories(root);
      long free = Files.getFileStore(root).getUsableSpace();
      if (free < minimumFree
          || free - minimumFree < pending
          || free - minimumFree - pending < incoming) throw new StorageLimit(true);
    } catch (IOException error) {
      throw new ServiceUnavailable("无法核验存储容量");
    }
  }

  public <T> T exclusively(long tenant, String id, Supplier<T> action) {
    path(tenant, id);
    Path directory = root.resolve(".locks");
    try {
      Files.createDirectories(directory);
      // 固定条带锁文件永久保留，避免删除锁文件后不同进程锁住不同的文件实体。
      long stripe = (tenant + ":" + id).hashCode() & 65535;
      try (var channel =
              FileChannel.open(
                  directory.resolve("assets.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var lock = channel.tryLock(stripe, 1, false)) {
        if (lock == null) throw ResourceProblem.conflict("素材正在处理，请稍后重试");
        return action.get();
      }
    } catch (OverlappingFileLockException error) {
      throw ResourceProblem.conflict("素材正在处理，请稍后重试");
    } catch (IOException error) {
      throw new ServiceUnavailable("素材排他锁暂不可用");
    }
  }
}
