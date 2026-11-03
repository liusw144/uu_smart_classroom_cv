package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "server.ssl.enabled=true",
      "server.ssl.client-auth=want",
      "server.ssl.bundle=test-node",
      "classroom.security.node-mtls=true",
      "spring.ssl.bundle.pem.test-node.keystore.certificate=file:../../.harness/pki-production-tests/server.crt",
      "spring.ssl.bundle.pem.test-node.keystore.private-key=file:../../.harness/pki-production-tests/server.key",
      "spring.ssl.bundle.pem.test-node.truststore.certificate=file:../../.harness/pki-production-tests/ca.crt"
    })
class NodeMtlsIT extends SchoolHttpITSupport {
  static final Path ROOT = Path.of("../../.harness/pki-production-tests");

  String scheme() {
    return "https";
  }

  HttpClient client() throws Exception {
    return secured(null);
  }

  X509Certificate certificate(String name) throws Exception {
    try (var input = Files.newInputStream(ROOT.resolve(name + ".crt"))) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  HttpClient secured(String name) throws Exception {
    var store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setCertificateEntry("ca", certificate("ca"));
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    KeyManager[] managers = null;
    if (name != null) {
      String pem =
          Files.readString(ROOT.resolve(name + ".key")).replaceAll("-----[^-]+-----|\\s", "");
      var key =
          KeyFactory.getInstance("EC")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      store.setKeyEntry(
          "identity",
          key,
          new char[0],
          new java.security.cert.Certificate[] {certificate(name), certificate("ca")});
      var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(store, new char[0]);
      managers = keys.getKeyManagers();
    }
    var context = SSLContext.getInstance("TLS");
    context.init(managers, trust.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(context).build();
  }

  int claim(HttpClient client, String node) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/internal/v1/edge/claim"))
            .header("Authorization", "Bearer edge-fixture-only-token-000000000000000000")
            .header("X-Node-ID", node)
            .header("X-SSL-Client-Verify", "SUCCESS")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  void enroll(String name, String node) throws Exception {
    var cert = certificate(name);
    String hash =
        java.util.HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    jdbc.update(
        "INSERT INTO edge_node_certificate(fingerprint,node_id,not_before,not_after) VALUES (?,?,?,?)",
        hash,
        node,
        java.sql.Timestamp.from(cert.getNotBefore().toInstant()),
        java.sql.Timestamp.from(cert.getNotAfter().toInstant()));
  }

  @Test
  void nativeTlsBindingRotationAndRevocation() throws Exception {
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "edge-fixture-only-token-000000000000000000"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    jdbc.update(
        "INSERT INTO edge_node(id,tenant_id,name,token_hash) VALUES ('mtls-a',101,'TLS甲',?),('mtls-b',102,'TLS乙',?)",
        hash,
        hash);
    enroll("node", "mtls-a");
    assertEquals(401, claim(secured(null), "mtls-a"));
    assertEquals(401, claim(secured("other"), "mtls-a"));
    assertEquals(401, claim(secured("node"), "mtls-b"));
    assertEquals(200, claim(secured("node"), "mtls-a"));
    enroll("other", "mtls-a");
    assertEquals(200, claim(secured("other"), "mtls-a"));
    jdbc.update(
        "UPDATE edge_node_certificate SET revoked_at=CURRENT_TIMESTAMP(6) WHERE node_id='mtls-a' AND fingerprint<>?",
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate("other").getEncoded())));
    assertEquals(401, claim(secured("node"), "mtls-a"));
    assertEquals(200, claim(secured("other"), "mtls-a"));
    assertThrows(javax.net.ssl.SSLException.class, () -> claim(secured("expired"), "mtls-a"));
  }
}
