package cn.uusmart.classroom.api.pipeline;

import cn.uusmart.classroom.application.AuthenticationFailed;
import cn.uusmart.classroom.application.pipeline.NodeCertificateRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class NodeClientCertificate {
  private final NodeCertificateRegistry registry;
  private final boolean required;

  public NodeClientCertificate(
      NodeCertificateRegistry registry,
      @Value("${classroom.security.node-mtls:false}") boolean required) {
    this.registry = registry;
    this.required = required;
  }

  public void verify(HttpServletRequest request, String node) {
    if (!required) return;
    // 只读取TLS容器已经验证的证书链，绝不从HTTP转发头重建证书身份。
    Object value = request.getAttribute("jakarta.servlet.request.X509Certificate");
    if (!request.isSecure() || !(value instanceof X509Certificate[] chain) || chain.length == 0)
      throw new AuthenticationFailed();
    try {
      X509Certificate certificate = chain[0];
      certificate.checkValidity();
      var usages = certificate.getExtendedKeyUsage();
      if (certificate.getBasicConstraints() >= 0
          || usages == null
          || !usages.contains("1.3.6.1.5.5.7.3.2")) throw new AuthenticationFailed();
      String hash =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
      if (!registry.active(node, hash)) throw new AuthenticationFailed();
    } catch (java.security.GeneralSecurityException error) {
      throw new AuthenticationFailed();
    }
  }
}
