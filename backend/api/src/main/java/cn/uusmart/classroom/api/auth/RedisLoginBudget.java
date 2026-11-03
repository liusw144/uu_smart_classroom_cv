package cn.uusmart.classroom.api.auth;

import cn.uusmart.classroom.application.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public final class RedisLoginBudget implements LoginBudget {
  private static final DefaultRedisScript<Long> ACQUIRE =
      new DefaultRedisScript<>(
          """
      local now = redis.call('TIME')
      local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
      redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', ms)
      if redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[4]) then return 1000 end
      for i = 1, 3 do
        if tonumber(redis.call('GET', KEYS[i]) or '0') >= tonumber(ARGV[i]) then
          return math.max(redis.call('PTTL', KEYS[i]), 1)
        end
      end
      for i = 1, 3 do
        if redis.call('INCR', KEYS[i]) == 1 then redis.call('PEXPIRE', KEYS[i], ARGV[5]) end
      end
      redis.call('ZADD', KEYS[4], ms + tonumber(ARGV[6]), ARGV[7])
      redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[6]) + 1000)
      return 0
      """,
          Long.class);
  private static final DefaultRedisScript<Long> IDENTIFY =
      new DefaultRedisScript<>(
          """
      if tonumber(redis.call('GET', KEYS[1]) or '0') >= tonumber(ARGV[1]) then
        return math.max(redis.call('PTTL', KEYS[1]), 1)
      end
      if redis.call('INCR', KEYS[1]) == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
      return 0
      """,
          Long.class);
  private final StringRedisTemplate redis;
  private final String prefix;
  private final int accountLimit, sourceLimit, globalLimit, parallelism;
  private final long windowMillis, leaseMillis;
  private final Semaphore local;

  public RedisLoginBudget(
      StringRedisTemplate redis,
      @Value("${classroom.security.login.namespace:uu-login-v1}") String namespace,
      @Value("${classroom.security.login.account-limit:15}") int accountLimit,
      @Value("${classroom.security.login.source-limit:120}") int sourceLimit,
      @Value("${classroom.security.login.global-limit:300}") int globalLimit,
      @Value("${classroom.security.login.parallelism:4}") int parallelism,
      @Value("${classroom.security.login.window-seconds:60}") long window,
      @Value("${classroom.security.login.lease-seconds:120}") long lease) {
    if (!namespace.matches("[A-Za-z0-9:-]{1,80}")
        || accountLimit < 1
        || sourceLimit < 1
        || globalLimit < 1
        || parallelism < 1
        || parallelism > 32
        || window < 1
        || window > 3600
        || lease < 5
        || lease > 600) throw new IllegalArgumentException("登录预算配置无效");
    this.redis = redis;
    this.prefix = "{" + namespace + "}:";
    this.accountLimit = accountLimit;
    this.sourceLimit = sourceLimit;
    this.globalLimit = globalLimit;
    this.parallelism = parallelism;
    this.windowMillis = window * 1000;
    this.leaseMillis = lease * 1000;
    this.local = new Semaphore(parallelism);
  }

  public Permit acquire(String school, String username, String source) {
    if (!local.tryAcquire()) throw new RequestThrottled(1);
    String id = UUID.randomUUID().toString();
    try {
      // 全部键共用哈希标签；先全局准入，避免随机账号无限创建预算键。
      check(
          redis.execute(
              ACQUIRE,
              List.of(
                  prefix + "global",
                  prefix + "source:" + hash(source),
                  prefix + "name:" + hash(normalize(school) + "\u0000" + normalize(username)),
                  prefix + "leases"),
              Integer.toString(globalLimit),
              Integer.toString(sourceLimit),
              Integer.toString(accountLimit),
              Integer.toString(parallelism),
              Long.toString(windowMillis),
              Long.toString(leaseMillis),
              id));
      return new Permit() {
        private boolean closed;

        public void identify(long accountId) {
          try {
            // 数据库等价字符串统一落到真实账号编号，且仍在BCrypt之前核验。
            check(
                redis.execute(
                    IDENTIFY,
                    List.of(prefix + "account:" + accountId),
                    Integer.toString(accountLimit),
                    Long.toString(windowMillis)));
          } catch (RequestThrottled error) {
            throw error;
          } catch (RuntimeException error) {
            throw new ServiceUnavailable("登录预算暂不可用");
          }
        }

        public void close() {
          if (closed) return;
          closed = true;
          try {
            redis.opsForZSet().remove(prefix + "leases", id);
          } catch (RuntimeException error) {
            LoggerFactory.getLogger(RedisLoginBudget.class).warn("登录预算释放失败，等待租约到期");
          } finally {
            local.release();
          }
        }
      };
    } catch (RuntimeException error) {
      local.release();
      if (error instanceof RequestThrottled throttled) throw throttled;
      throw new ServiceUnavailable("登录预算暂不可用");
    }
  }

  private static void check(Long delay) {
    if (delay == null || delay < 0) throw new ServiceUnavailable("登录预算暂不可用");
    if (delay > 0) throw new RequestThrottled((delay + 999) / 1000);
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("摘要算法不可用", error);
    }
  }
}
