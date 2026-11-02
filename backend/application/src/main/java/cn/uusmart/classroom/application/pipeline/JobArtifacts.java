package cn.uusmart.classroom.application.pipeline;

public interface JobArtifacts {
  void write(long tenantId, String jobId, String leaseId, byte[] bytes, String sha256);

  byte[] read(long tenantId, String jobId, String leaseId);

  void removeJob(long tenantId, String jobId);
}
