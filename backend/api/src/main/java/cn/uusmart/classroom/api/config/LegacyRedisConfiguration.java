package cn.uusmart.classroom.api.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
@ConditionalOnProperty(name = "classroom.redis.compatibility-mode", havingValue = "true")
public class LegacyRedisConfiguration {
  @Bean
  @Primary
  SaTokenDao compatibleSessionDao(RedisConnectionFactory factory) {
    var dao =
        new SaTokenDaoForRedisTemplate() {
          @Override
          public void setStringAndKeepTTL(String key, String value) {
            // Redis 5不支持KEEPTTL，用原子脚本保留剩余毫秒且不复活已过期会话。
            var script =
                new DefaultRedisScript<Long>(
                    """
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl == -2 then return 0 end
            if ttl == -1 then
              redis.call('SET', KEYS[1], ARGV[1], 'XX')
            else
              redis.call('SET', KEYS[1], ARGV[1], 'PX', math.max(ttl, 1), 'XX')
            end
            return 1
            """,
                    Long.class);
            stringRedisTemplate.execute(script, List.of(key), value);
          }
        };
    dao.init(factory);
    return dao;
  }
}
