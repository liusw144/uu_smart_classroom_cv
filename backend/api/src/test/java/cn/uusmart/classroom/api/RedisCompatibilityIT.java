package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.dev33.satoken.dao.SaTokenDao;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "classroom.bootstrap.enabled=false")
class RedisCompatibilityIT {
  @Autowired SaTokenDao dao;

  @Test
  void sessionStorageWorksWithLocalRedis() {
    String key = "uu-classroom-compatibility-" + UUID.randomUUID();
    try {
      dao.set(key, "人工测试值", 30);
      assertEquals("人工测试值", dao.get(key));
      dao.setObject(key + ":object", new cn.dev33.satoken.session.SaSession(key), 30);
      assertNotNull(dao.getObject(key + ":object"));
      dao.update(key, "更新后的人工测试值");
      assertEquals("更新后的人工测试值", dao.get(key));
      assertTrue(dao.getTimeout(key) > 0 && dao.getTimeout(key) <= 30);
      dao.update(key + ":missing", "不得复活");
      assertNull(dao.get(key + ":missing"));
      dao.updateObject(key + ":object", new cn.dev33.satoken.session.SaSession(key));
      dao.updateTimeout(key, 60);
      assertTrue(dao.getTimeout(key) > 0);
    } finally {
      dao.delete(key);
      dao.deleteObject(key + ":object");
    }
  }
}
