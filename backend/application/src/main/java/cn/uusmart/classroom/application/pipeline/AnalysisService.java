package cn.uusmart.classroom.application.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.time.Instant;
import java.util.UUID;

public final class AnalysisService {
  private final PipelineRepository repository;
  private final MediaService media;
  private final EdgeExecutionService execution;
  private final InferenceResultCodec codec;
  private final LocalAiGateway ai;
  private final LockedModels models;

  public AnalysisService(
      PipelineRepository repository,
      MediaService media,
      EdgeExecutionService execution,
      InferenceResultCodec codec,
      LocalAiGateway ai,
      LockedModels models) {
    this.repository = repository;
    this.media = media;
    this.execution = execution;
    this.codec = codec;
    this.ai = ai;
    this.models = models;
  }

  public TeachingReport analyze(Actor actor, String id) {
    String result = execution.resultJson(actor, id);
    var job = media.job(actor, id);
    media.analysisTask(actor, job.taskId());
    String facts = codec.summary(result);
    String content = ai.analyze(facts);
    var report =
        new TeachingReport(
            UUID.randomUUID().toString(),
            actor.tenantId(),
            job.ownerId(),
            job.taskId(),
            "本地AI视觉样例分析",
            "数据来源：YOLOX与RTMPose实际推理\n作业编号："
                + id
                + "\n结构化事实："
                + facts
                + "\n生成模型：Qwen3-0.6B-Q8_0\n权重SHA256："
                + models.aiHash()
                + "\n\nAI生成内容（需人工核对）：\n"
                + content
                + "\n\n固定说明：累计人次不是独立人数，姿态候选未经课堂标注验证，不用于学生专注度或教学质量评价。",
            "LOCAL_QWEN3",
            Instant.now());
    repository.createAiReport(report, id, "Qwen3-0.6B-Q8_0", models.aiHash());
    return media.report(actor, report.id());
  }
}
