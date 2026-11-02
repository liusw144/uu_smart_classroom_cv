package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.MediaAsset;

public interface VisionGateway {
  String unavailableReason(String jobId, MediaAsset asset, String modelVersion);
}
