package cn.uusmart.classroom.application.pipeline;

public interface NodeCertificateRegistry {
  boolean active(String node, String fingerprint);
}
