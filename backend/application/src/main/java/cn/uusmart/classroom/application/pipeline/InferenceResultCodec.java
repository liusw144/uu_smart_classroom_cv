package cn.uusmart.classroom.application.pipeline;

import cn.uusmart.classroom.domain.pipeline.JobLease;

public interface InferenceResultCodec {
  record Proof(String contentType, String artifactHash) {}

  Proof validate(String json, JobLease lease, byte[] artifact);

  String summary(String json);
}
