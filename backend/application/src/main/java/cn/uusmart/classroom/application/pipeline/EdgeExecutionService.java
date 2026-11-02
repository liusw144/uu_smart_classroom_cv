package cn.uusmart.classroom.application.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import cn.uusmart.classroom.domain.pipeline.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

public final class EdgeExecutionService {
  private final PipelineRepository repository;
  private final AssetStorage assets;
  private final JobArtifacts artifacts;
  private final InferenceResultCodec codec;

  public EdgeExecutionService(
      PipelineRepository repository,
      AssetStorage assets,
      JobArtifacts artifacts,
      InferenceResultCodec codec) {
    this.repository = repository;
    this.assets = assets;
    this.artifacts = artifacts;
    this.codec = codec;
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public NodePrincipal authenticate(String nodeId, String authorization) {
    if (nodeId == null
        || !nodeId.matches("[A-Za-z0-9_-]{3,64}")
        || authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 520) throw new AuthenticationFailed();
    String token = authorization.substring(7);
    if (token.length() < 32) throw new AuthenticationFailed();
    var credential = repository.node(nodeId).orElseThrow(AuthenticationFailed::new);
    if (!MessageDigest.isEqual(
        credential.tokenHash().getBytes(StandardCharsets.US_ASCII),
        hash(token.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII)))
      throw new AuthenticationFailed();
    return credential.principal();
  }

  public JobLease claim(NodePrincipal node) {
    return repository.claim(node).orElse(null);
  }

  private PipelineRepository.StoredJob active(NodePrincipal node, String id, String lease) {
    var job = repository.nodeJob(node, id).orElseThrow(ResourceProblem::missing);
    if (!Objects.equals(job.lease().leaseId(), lease)
        || !"RUNNING".equals(job.status())
        || job.lease().leaseExpiresAt() == null
        || job.lease().leaseExpiresAt().isBefore(Instant.now()))
      throw ResourceProblem.conflict("作业租约已失效或已取消");
    return job;
  }

  public byte[] input(NodePrincipal node, String id, String lease) {
    var job = active(node, id, lease);
    return assets.read(node.tenantId(), job.lease().assetId());
  }

  public String inputType(NodePrincipal node, String id, String lease) {
    return active(node, id, lease).lease().contentType();
  }

  public cn.uusmart.classroom.domain.pipeline.JobLease heartbeat(
      NodePrincipal node, String id, String lease) {
    active(node, id, lease);
    repository.heartbeat(node, id, lease);
    return repository.nodeJob(node, id).orElseThrow(ResourceProblem::missing).lease();
  }

  public void fail(NodePrincipal node, String id, String lease, String code) {
    active(node, id, lease);
    if (!Set.of("INPUT_INVALID", "MODEL_ERROR", "INFERENCE_FAILED").contains(code))
      throw ResourceProblem.invalid("失败原因无效");
    if (!repository.fail(node, id, lease, code)) throw ResourceProblem.conflict("作业状态已变更");
  }

  public boolean complete(
      NodePrincipal node, String id, String lease, String result, byte[] artifact) {
    if (result.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024
        || artifact.length == 0
        || artifact.length > 32 * 1024 * 1024) throw ResourceProblem.invalid("结果体积超出限制");
    String digest = hash(result.getBytes(StandardCharsets.UTF_8));
    var previous = repository.nodeJob(node, id).orElseThrow(ResourceProblem::missing);
    if ("SUCCEEDED".equals(previous.status())
        && Objects.equals(previous.lease().leaseId(), lease)
        && Objects.equals(previous.resultHash(), digest)) {
      codec.validate(result, previous.lease(), artifact);
      return true;
    }
    var job = active(node, id, lease);
    var proof = codec.validate(result, job.lease(), artifact);
    artifacts.write(node.tenantId(), id, lease, artifact, proof.artifactHash());
    if (!repository.complete(node, id, lease, digest, result, proof.contentType())) {
      var current = repository.nodeJob(node, id).orElseThrow(ResourceProblem::missing);
      if (!"SUCCEEDED".equals(current.status())
          || !digest.equals(current.resultHash())
          || !Objects.equals(lease, current.lease().leaseId()))
        throw ResourceProblem.conflict("作业状态或租约已变更");
    }
    return true;
  }

  private PipelineRepository.StoredJob result(Actor actor, String id) {
    TeachingService.requireSchoolUser(actor);
    var job = repository.userJob(actor, id).orElseThrow(ResourceProblem::missing);
    if (!"SUCCEEDED".equals(job.status())) throw ResourceProblem.conflict("作业尚无成功结果");
    return job;
  }

  public String resultJson(Actor actor, String id) {
    return result(actor, id).resultJson();
  }

  public String artifactType(Actor actor, String id) {
    return result(actor, id).artifactType();
  }

  public byte[] artifact(Actor actor, String id) {
    var job = result(actor, id);
    return artifacts.read(actor.tenantId(), id, job.lease().leaseId());
  }

  public void cancel(Actor actor, String id) {
    TeachingService.requireSchoolUser(actor);
    repository.userJob(actor, id).orElseThrow(ResourceProblem::missing);
    repository.cancel(actor, id);
  }
}
