"""复用固定版本rtmlib的前后处理，由项目注入已校验设备的ONNX会话。"""

from rtmlib import YOLOX, RTMPose


class SessionYOLOX(YOLOX):
    def __init__(self, session):
        # 不调用上游构造器，避免其预先加载默认CPU会话；字段对应rtmlib 0.0.15。
        self.session = session
        self.backend = "onnxruntime"
        self.model_input_size = (416, 416)
        self.mode = "multiclass"
        self.nms_thr = 0.45
        self.score_thr = 0.3


class SessionRTMPose(RTMPose):
    def __init__(self, session):
        self.session = session
        self.backend = "onnxruntime"
        self.model_input_size = (192, 256)
        self.mean = (123.675, 116.28, 103.53)
        self.std = (58.395, 57.12, 57.375)
        self.to_openpose = False
