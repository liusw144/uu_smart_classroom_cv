package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.uusmart.classroom.infrastructure.pipeline.JsonLockedModels;
import org.junit.jupiter.api.Test;

class ModelManifestTest {
  @Test
  void containerCanReadPackagedManifestWithoutWorkspaceFiles() {
    var models = new JsonLockedModels("classpath:models/demo-assets.lock.json");
    assertEquals("yolox-tiny-rtmpose-s-v1", models.version());
    assertEquals(2, models.hashes().size());
    assertEquals(64, models.aiHash().length());
  }
}
