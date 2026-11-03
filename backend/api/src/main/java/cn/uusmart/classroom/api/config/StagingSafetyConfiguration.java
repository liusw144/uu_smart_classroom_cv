package cn.uusmart.classroom.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile({"staging", "production"})
public class StagingSafetyConfiguration {
  public StagingSafetyConfiguration(Environment environment) {
    validate(
        environment.getProperty("server.ssl.enabled", Boolean.class, false),
        environment.getProperty("server.ssl.client-auth", "none"),
        environment.getProperty("classroom.security.node-mtls", Boolean.class, false),
        environment.getProperty("classroom.bootstrap.enabled", Boolean.class, true));
  }

  public static void validate(boolean tls, String clientAuth, boolean mtls, boolean bootstrap) {
    if (!tls || !(clientAuth.equals("want") || clientAuth.equals("need")) || !mtls || bootstrap)
      throw new IllegalStateException("受保护环境要求原生TLS、客户端证书、节点绑定，且禁止开发初始化");
  }
}
