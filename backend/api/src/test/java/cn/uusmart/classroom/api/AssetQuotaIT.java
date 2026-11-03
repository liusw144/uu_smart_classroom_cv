package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import cn.uusmart.classroom.infrastructure.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@TestPropertySource(
    properties = {"classroom.storage.owner-bytes=16384", "classroom.storage.owner-files=2"})
class AssetQuotaIT extends MediaFlowIT {
  @Autowired PlatformTransactionManager transactions;

  JdbcAssetLifecycle lifecycle(
      FileAssetStorage storage, long ownerBytes, long schoolBytes, long globalBytes, long count) {
    return new JdbcAssetLifecycle(
        jdbc,
        transactions,
        storage,
        ownerBytes,
        schoolBytes,
        globalBytes,
        count,
        10000,
        100000,
        1,
        5);
  }

  FileAssetStorage storage() {
    return new FileAssetStorage(System.getenv("CLASSROOM_ASSETS_ROOT"));
  }

  MediaAsset record(long tenant, long owner) throws Exception {
    byte[] bytes = png();
    return new MediaAsset(
        UUID.randomUUID().toString(),
        tenant,
        owner,
        "quota.png",
        "image/png",
        bytes.length,
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
  }

  @Test
  void byteAndFileLimitsAreIndependentlyEnforced() throws Exception {
    var actor = new Actor(1101, 101, Role.TEACHER);
    var bytes = lifecycle(storage(), 8192, 1000000, 10000000, 100);
    var a = record(101, 1101);
    var denied = record(101, 1101);
    bytes.create(a, png());
    assertThrows(StorageLimit.class, () -> bytes.create(denied, png()));
    bytes.delete(actor, a.id());
    var count = lifecycle(storage(), 1000000, 1000000, 10000000, 1);
    var b = record(101, 1101);
    count.create(b, png());
    assertThrows(StorageLimit.class, () -> count.create(denied, png()));
    count.delete(actor, b.id());
    var school =
        new JdbcAssetLifecycle(
            jdbc, transactions, storage(), 1000000, 1000000, 10000000, 100, 1, 100000, 1, 5);
    var c = record(101, 1101);
    var peerAsset = record(101, 1102);
    school.create(c, png());
    assertThrows(StorageLimit.class, () -> school.create(peerAsset, png()));
    school.delete(actor, c.id());
    long baseline =
        jdbc.queryForObject(
            "SELECT file_count FROM media_storage_usage WHERE tenant_id=0 AND owner_id=0",
            Long.class);
    var global =
        new JdbcAssetLifecycle(
            jdbc,
            transactions,
            storage(),
            1000000,
            1000000,
            10000000,
            100,
            100,
            baseline + 1,
            1,
            5);
    var d = record(101, 1101);
    var otherAsset = record(102, 2201);
    global.create(d, png());
    assertThrows(StorageLimit.class, () -> global.create(otherAsset, png()));
    global.delete(actor, d.id());
  }

  @Test
  void deleteReleasesOnceAndForeignKeyRejectionKeepsBudget() throws Exception {
    String first = asset(teacher);
    asset(teacher);
    assertEquals(413, upload(png(), "third.png", "image/png", teacher).statusCode());
    data(
        call(
            "PUT",
            "/profile",
            "{\"display_name\":\"配额\",\"email\":\"\",\"avatar_asset_id\":\"" + first + "\"}",
            teacher));
    assertEquals(409, call("DELETE", "/assets/" + first, null, teacher).statusCode());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT file_count FROM media_storage_usage WHERE tenant_id=101 AND owner_id=1101",
            Integer.class));
    data(
        call(
            "PUT",
            "/profile",
            "{\"display_name\":\"配额\",\"email\":\"\",\"avatar_asset_id\":null}",
            teacher));
    data(call("DELETE", "/assets/" + first, null, teacher));
    assertEquals(404, call("DELETE", "/assets/" + first, null, teacher).statusCode());
    assertEquals(200, upload(png(), "replacement.png", "image/png", teacher).statusCode());
  }

  @Test
  void physicalCapacityFloorRejectsBeforeReservation() throws Exception {
    var service =
        new JdbcAssetLifecycle(
            jdbc,
            transactions,
            storage(),
            1000000,
            1000000,
            10000000,
            100,
            100,
            100,
            Long.MAX_VALUE,
            5);
    var a = record(101, 1101);
    assertTrue(assertThrows(StorageLimit.class, () -> service.create(a, png())).capacity());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id=?", Integer.class, a.id()));
  }

  @Test
  void separateLifecycleInstancesShareSchoolAndGlobalBudgets() throws Exception {
    long baseline =
        jdbc.queryForObject(
            "SELECT used_bytes FROM media_storage_usage WHERE tenant_id=0 AND owner_id=0",
            Long.class);
    var first = lifecycle(storage(), 1000000, 16384, baseline + 1000000, 100);
    var second = lifecycle(storage(), 1000000, 16384, baseline + 1000000, 100);
    var a = record(101, 1101);
    var b = record(101, 1102);
    first.create(a, png());
    second.create(b, png());
    var c = record(101, 1199);
    assertThrows(StorageLimit.class, () -> second.create(c, png()));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT file_count FROM media_storage_usage WHERE tenant_id=101 AND owner_id=0",
            Integer.class));
    var global = lifecycle(storage(), 1000000, 1000000, baseline + 16384, 100);
    var d = record(102, 2201);
    assertThrows(StorageLimit.class, () -> global.create(d, png()));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE tenant_id=102", Integer.class));
  }

  @Test
  void failedPhysicalDeleteRetainsChargeUntilSuccessfulRetry() throws Exception {
    String id = asset(teacher);
    var failing = new AtomicBoolean(true);
    var files =
        new FileAssetStorage(System.getenv("CLASSROOM_ASSETS_ROOT")) {
          public void remove(long tenant, String asset) {
            if (failing.get()) throw new ServiceUnavailable("注入删除失败");
            super.remove(tenant, asset);
          }
        };
    var service = lifecycle(files, 16384, 1000000, 10000000, 2);
    var actor = new Actor(1101, 101, Role.TEACHER);
    assertThrows(ServiceUnavailable.class, () -> service.delete(actor, id));
    assertEquals(
        8192,
        jdbc.queryForObject(
            "SELECT used_bytes FROM media_storage_usage WHERE tenant_id=101 AND owner_id=1101",
            Long.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM media_storage_cleanup WHERE asset_id=?", Integer.class, id));
    assertEquals(404, call("GET", "/assets/" + id, null, teacher).statusCode());
    failing.set(false);
    service.recover();
    service.recover();
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT used_bytes FROM media_storage_usage WHERE tenant_id=101 AND owner_id=1101",
            Long.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM media_storage_cleanup WHERE asset_id=?", Integer.class, id));
    assertFalse(
        java.nio.file.Files.exists(
            java.nio.file.Path.of(System.getenv("CLASSROOM_ASSETS_ROOT"), "101", id)));
  }

  @Test
  void recoveryCannotReapWriterHoldingFileLock() throws Exception {
    String id = asset(teacher);
    jdbc.update(
        "UPDATE media_asset SET storage_state='PENDING',created_at=TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(6)) WHERE id=?",
        id);
    var files = storage();
    var service = lifecycle(files, 16384, 1000000, 10000000, 2);
    files.exclusively(
        101,
        id,
        () -> {
          service.recover();
          return null;
        });
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id=?", Integer.class, id));
    service.recover();
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id=?", Integer.class, id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT file_count FROM media_storage_usage WHERE tenant_id=101 AND owner_id=1101",
            Integer.class));
  }

  @Test
  void failedWriteReleasesReservationAfterPartialFileRemoval() throws Exception {
    var files =
        new FileAssetStorage(System.getenv("CLASSROOM_ASSETS_ROOT")) {
          public void write(long tenant, String id, byte[] bytes) {
            super.write(tenant, id, bytes);
            throw new ServiceUnavailable("注入发布前失败");
          }
        };
    var service = lifecycle(files, 16384, 1000000, 10000000, 2);
    var a = record(101, 1101);
    assertThrows(ServiceUnavailable.class, () -> service.create(a, png()));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id=?", Integer.class, a.id()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT file_count FROM media_storage_usage WHERE tenant_id=101 AND owner_id=1101",
            Integer.class));
  }

  @Test
  void concurrentRepeatedUploadsCannotExceedOwnerBudget() throws Exception {
    var pool = Executors.newFixedThreadPool(4);
    try {
      var futures = new java.util.ArrayList<Future<Integer>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            pool.submit(() -> upload(png(), "same.png", "image/png", teacher).statusCode()));
      var statuses = new java.util.ArrayList<Integer>();
      for (var future : futures) statuses.add(future.get(20, TimeUnit.SECONDS));
      assertEquals(2, statuses.stream().filter(code -> code == 200).count());
      assertEquals(2, statuses.stream().filter(code -> code == 413).count());
      assertEquals(2, data(call("GET", "/assets", null, teacher)).path("total").asInt());
      assertEquals(0, data(call("GET", "/assets", null, peer)).path("total").asInt());
    } finally {
      pool.shutdownNow();
    }
  }
}
