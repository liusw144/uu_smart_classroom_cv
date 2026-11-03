package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.uusmart.classroom.api.pipeline.NodeClientCertificate;
import cn.uusmart.classroom.application.AuthenticationFailed;
import cn.uusmart.classroom.application.pipeline.NodeCertificateRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeClientCertificateTest {
  @Test
  void unsafeStagingOverridesFailAtStartup() {
    assertThrows(
        IllegalStateException.class,
        () ->
            cn.uusmart.classroom.api.config.StagingSafetyConfiguration.validate(
                false, "want", true, false));
    assertThrows(
        IllegalStateException.class,
        () ->
            cn.uusmart.classroom.api.config.StagingSafetyConfiguration.validate(
                true, "none", true, false));
    assertThrows(
        IllegalStateException.class,
        () ->
            cn.uusmart.classroom.api.config.StagingSafetyConfiguration.validate(
                true, "want", false, false));
    assertThrows(
        IllegalStateException.class,
        () ->
            cn.uusmart.classroom.api.config.StagingSafetyConfiguration.validate(
                true, "want", true, true));
  }

  @Test
  void forwardedCertificateHeaderCannotReplaceNativeTls() {
    var request = mock(HttpServletRequest.class);
    when(request.isSecure()).thenReturn(false);
    when(request.getHeader("X-SSL-Client-Verify")).thenReturn("SUCCESS");
    var identity = new NodeClientCertificate(mock(NodeCertificateRegistry.class), true);
    assertThrows(AuthenticationFailed.class, () -> identity.verify(request, "node-a"));
  }

  @Test
  void onlyCurrentRegisteredCertificateCanAuthenticate() throws Exception {
    var request = mock(HttpServletRequest.class);
    var cert = mock(X509Certificate.class);
    var registry = mock(NodeCertificateRegistry.class);
    var identity = new NodeClientCertificate(registry, true);
    when(request.isSecure()).thenReturn(true);
    when(request.getAttribute("jakarta.servlet.request.X509Certificate"))
        .thenReturn(new X509Certificate[] {cert});
    when(cert.getEncoded()).thenReturn(new byte[] {1, 2, 3});
    when(cert.getBasicConstraints()).thenReturn(-1);
    when(cert.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
    when(registry.active(eq("node-a"), anyString())).thenReturn(true);
    assertDoesNotThrow(() -> identity.verify(request, "node-a"));
    when(registry.active(eq("node-a"), anyString())).thenReturn(false);
    assertThrows(AuthenticationFailed.class, () -> identity.verify(request, "node-a"));
    verify(cert, times(2)).checkValidity();
  }
}
