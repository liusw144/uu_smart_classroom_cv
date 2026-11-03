package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.uusmart.classroom.application.StorageLimit;
import cn.uusmart.classroom.application.pipeline.EdgeExecutionService;
import cn.uusmart.classroom.infrastructure.pipeline.FileJobArtifacts;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactBudgetTest {
  @TempDir Path directory;

  @Test
  void separateInstancesSeePhysicalUsageAndIdempotentRetryDoesNotGrowIt() {
    var first = new FileJobArtifacts(directory.toString(), 8, 4, 2, 1);
    var second = new FileJobArtifacts(directory.toString(), 8, 4, 2, 1);
    String job = UUID.randomUUID().toString(), lease = UUID.randomUUID().toString();
    byte[] bytes = {1, 2, 3, 4};
    String hash = EdgeExecutionService.hash(bytes);
    first.write(101, job, lease, bytes, hash);
    second.write(101, job, lease, bytes, hash);
    assertThrows(
        StorageLimit.class,
        () ->
            second.write(
                101, UUID.randomUUID().toString(), UUID.randomUUID().toString(), bytes, hash));
    second.write(102, UUID.randomUUID().toString(), UUID.randomUUID().toString(), bytes, hash);
    assertThrows(
        StorageLimit.class,
        () ->
            first.write(
                103, UUID.randomUUID().toString(), UUID.randomUUID().toString(), bytes, hash));
    first.removeJob(101, job);
    assertDoesNotThrow(
        () ->
            second.write(
                101, UUID.randomUUID().toString(), UUID.randomUUID().toString(), bytes, hash));
  }
}
