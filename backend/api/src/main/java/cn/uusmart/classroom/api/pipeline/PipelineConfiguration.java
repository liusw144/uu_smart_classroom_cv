package cn.uusmart.classroom.api.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class PipelineConfiguration {
  @Bean
  NodeStatusService nodeStatusService(PipelineRepository repository) {
    return new NodeStatusService(repository);
  }

  @Bean
  AnalysisService analysisService(
      PipelineRepository repository,
      MediaService media,
      EdgeExecutionService execution,
      InferenceResultCodec codec,
      LocalAiGateway ai,
      LockedModels models) {
    return new AnalysisService(repository, media, execution, codec, ai, models);
  }

  @Bean
  QueuedJobService queuedJobService(
      @Value("${classroom.pipeline.enabled:false}") boolean enabled,
      PipelineRepository repository,
      MediaRepository media,
      LockedModels models,
      JobArtifacts artifacts) {
    return new QueuedJobService(enabled, repository, media, models, artifacts);
  }

  @Bean
  EdgeExecutionService edgeExecutionService(
      PipelineRepository repository,
      AssetStorage assets,
      JobArtifacts artifacts,
      InferenceResultCodec codec) {
    return new EdgeExecutionService(repository, assets, artifacts, codec);
  }
}
