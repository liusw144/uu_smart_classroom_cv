package cn.uusmart.classroom.application;

import cn.uusmart.classroom.application.pipeline.QueuedJobService;
import cn.uusmart.classroom.domain.*;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;

public final class MediaService {
  public Page<MediaAsset> assets(Actor actor, String q, int page, int size) {
    TeachingService.requireSchoolUser(actor);
    Page.validate(q, page, size);
    return repository.assets(actor, q, page, size);
  }

  public MediaAsset uploadForTask(
      Actor actor, Long taskId, String filename, String type, byte[] bytes) {
    if (taskId == null) return upload(actor, filename, type, bytes);
    var task = teaching.task(actor, taskId);
    return uploadOwned(actor, task.ownerId(), filename, type, bytes);
  }

  public void deleteAsset(Actor actor, String id) {
    asset(actor, id);
    lifecycle.delete(actor, id);
  }

  private final MediaRepository repository;
  private final AssetStorage storage;
  private final TeachingService teaching;
  private final SchoolContentService catalog;
  private final VisionGateway vision;
  private final QueuedJobService queue;
  private final AssetLifecycle lifecycle;

  public MediaService(
      MediaRepository repository,
      AssetStorage storage,
      TeachingService teaching,
      SchoolContentService catalog,
      VisionGateway vision,
      QueuedJobService queue,
      AssetLifecycle lifecycle) {
    this.repository = repository;
    this.storage = storage;
    this.teaching = teaching;
    this.catalog = catalog;
    this.vision = vision;
    this.queue = queue;
    this.lifecycle = lifecycle;
  }

  public MediaAsset upload(Actor actor, String filename, String type, byte[] bytes) {
    return uploadOwned(actor, actor.userId(), filename, type, bytes);
  }

  private MediaAsset uploadOwned(
      Actor actor, long ownerId, String filename, String type, byte[] bytes) {
    TeachingService.requireSchoolUser(actor);
    boolean video = "video/mp4".equals(type);
    if (bytes.length == 0
        || bytes.length > (video ? 32 : 10) * 1024 * 1024
        || !(video || "image/png".equals(type) || "image/jpeg".equals(type)))
      throw ResourceProblem.invalid("支持10MB以内图片或32MB以内MP4视频");
    if (video) {
      if (bytes.length < 12
          || bytes[4] != 'f'
          || bytes[5] != 't'
          || bytes[6] != 'y'
          || bytes[7] != 'p') throw ResourceProblem.invalid("视频封装无效");
    } else validateImage(bytes, type);
    String leaf = filename == null ? "image" : filename.replace('\\', '/');
    leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (leaf.isBlank()) leaf = "image";
    if (leaf.length() > 128) leaf = leaf.substring(0, 128);
    String id = UUID.randomUUID().toString();
    String hash;
    try {
      hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
    var asset = new MediaAsset(id, actor.tenantId(), ownerId, leaf, type, bytes.length, hash);
    lifecycle.create(asset, bytes);
    return asset;
  }

  private void validateImage(byte[] bytes, String type) {
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw ResourceProblem.invalid("图片内容无效");
      var reader = readers.next();
      try {
        reader.setInput(input);
        String format = reader.getFormatName();
        if (!(("PNG".equalsIgnoreCase(format) && type.equals("image/png"))
            || ("JPEG".equalsIgnoreCase(format) && type.equals("image/jpeg"))))
          throw ResourceProblem.invalid("图片格式与内容不一致");
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1
            || height < 1
            || width > 8000
            || height > 8000
            || (long) width * height > 16000000) throw ResourceProblem.invalid("图片尺寸过大，最多1600万像素");
        if (reader.read(0) == null) throw ResourceProblem.invalid("图片无法解码");
      } finally {
        reader.dispose();
      }
    } catch (IOException error) {
      throw ResourceProblem.invalid("图片内容无效");
    }
  }

  public MediaAsset asset(Actor actor, String id) {
    TeachingService.requireSchoolUser(actor);
    return repository.asset(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public byte[] content(Actor actor, String id) {
    var asset = asset(actor, id);
    return storage.read(asset.tenantId(), asset.id());
  }

  public DetectionJob submit(Actor actor, long taskId, String assetId, long modelId) {
    return submit(actor, taskId, assetId, modelId, null);
  }

  public DetectionJob submit(
      Actor actor, long taskId, String assetId, long modelId, String requestedKind) {
    var task = teaching.task(actor, taskId);
    var asset = asset(actor, assetId);
    var model = catalog.entry(actor, SchoolContentRepository.Catalog.MODEL, modelId);
    if (asset.ownerId() != task.ownerId()) throw ResourceProblem.invalid("素材与课堂任务必须由同一负责人持有");
    if (!model.enabled()) throw ResourceProblem.conflict("所选模型档案已停用");
    String kind =
        requestedKind == null
            ? (asset.contentType().equals("video/mp4") ? "VIDEO" : "IMAGE")
            : requestedKind;
    if (!Set.of("IMAGE", "VIDEO", "CAMERA").contains(kind)
        || asset.contentType().equals("video/mp4") != kind.equals("VIDEO"))
      throw ResourceProblem.invalid("素材类型与作业类型不一致");
    if (queue.enabled()) return queue.submit(actor, task, asset, model, kind);
    String id = UUID.randomUUID().toString();
    repository.createJob(
        new DetectionJob(
            id,
            actor.tenantId(),
            task.ownerId(),
            taskId,
            task.name(),
            asset.id(),
            model.id(),
            model.code(),
            "RUNNING",
            null,
            Instant.now()));
    String reason;
    try {
      reason = vision.unavailableReason(id, asset, model.code());
    } catch (RuntimeException error) {
      reason = "VISION_UNAVAILABLE";
    }
    repository.failJob(actor, id, reason);
    return job(actor, id);
  }

  public Page<DetectionJob> jobs(Actor actor, String q, String status, int page, int size) {
    TeachingService.requireSchoolUser(actor);
    Page.validate(q, page, size);
    if (!Set.of("", "QUEUED", "RUNNING", "FAILED", "SUCCEEDED", "CANCELLED").contains(status))
      throw ResourceProblem.invalid("检测状态无效");
    return repository.jobs(actor, q, status, page, size);
  }

  public DetectionJob job(Actor actor, String id) {
    TeachingService.requireSchoolUser(actor);
    return repository.job(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public void deleteJob(Actor actor, String id) {
    var job = job(actor, id);
    repository.deleteJob(actor, id);
    queue.removeArtifacts(job.tenantId(), id);
  }

  public TeachingReport createReport(Actor actor, long taskId, String title) {
    var task = teaching.task(actor, taskId);
    var course = teaching.course(actor, task.courseId());
    var schoolClass = teaching.schoolClass(actor, task.classId());
    long failed = repository.taskJobCount(actor, taskId, "FAILED");
    String content =
        "课堂任务："
            + task.name()
            + "\n班级："
            + schoolClass.name()
            + "\n课程："
            + course.name()
            + "\n教务录入班级人数："
            + schoolClass.studentCount()
            + "\n该课堂任务失败作业数："
            + failed
            + "\n本报告来自教务数据与作业状态，不包含课堂行为判断。\n本模板不调用视觉模型或AI，不能据此评价学生专注度、行为或教师教学质量。";
    var report =
        new TeachingReport(
            UUID.randomUUID().toString(),
            actor.tenantId(),
            task.ownerId(),
            taskId,
            title,
            content,
            "LOCAL_TEACHING_TEMPLATE",
            Instant.now());
    repository.createReport(report);
    return report(actor, report.id());
  }

  public Page<TeachingReport> reports(Actor actor, String q, int page, int size) {
    TeachingService.requireSchoolUser(actor);
    Page.validate(q, page, size);
    return repository.reports(actor, q, page, size);
  }

  public TeachingReport report(Actor actor, String id) {
    TeachingService.requireSchoolUser(actor);
    return repository.report(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public void deleteReport(Actor actor, String id) {
    report(actor, id);
    repository.deleteReport(actor, id);
  }
}
