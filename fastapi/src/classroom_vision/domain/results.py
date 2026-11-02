from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class PersonResult(BaseModel):
    model_config = ConfigDict(extra="forbid")
    bbox: Annotated[list[float], Field(min_length=4, max_length=4)]
    keypoints: Annotated[
        list[Annotated[list[float], Field(min_length=3, max_length=3)]],
        Field(min_length=17, max_length=17),
    ]
    pose_candidates: list[Literal["HAND_UP_CANDIDATE"]]


class FrameResult(BaseModel):
    model_config = ConfigDict(extra="forbid")
    index: Annotated[int, Field(ge=0)]
    timestamp_ms: Annotated[int, Field(ge=0)]
    persons: Annotated[list[PersonResult], Field(max_length=16)]


class InferenceResult(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    schema_version: Literal[1]
    job_id: str
    asset_id: str
    input_sha256: str
    model_version: str
    model_hashes: dict[str, str]
    media_kind: Literal["IMAGE", "VIDEO", "CAMERA"]
    width: Annotated[int, Field(ge=2, le=1280)]
    height: Annotated[int, Field(ge=2, le=1280)]
    duration_ms: Annotated[int, Field(ge=0, le=30000)]
    sample_interval_ms: Annotated[int, Field(ge=100)]
    sampled_frames: Annotated[int, Field(ge=1, le=32)]
    person_instances: Annotated[int, Field(ge=0)]
    unique_people: None
    pose_counts: dict[str, int]
    frames: Annotated[list[FrameResult], Field(min_length=1, max_length=32)]
    artifact_sha256: str
    artifact_size: Annotated[int, Field(gt=0, le=32 * 1024 * 1024)]
    artifact_content_type: Literal["image/png", "video/mp4"]
    request_digest: str
    processed_at: str
    elapsed_ms: Annotated[int, Field(ge=0)]
    interpretation: str

    @model_validator(mode="after")
    def validate_counts(self):
        if self.sampled_frames != len(self.frames) or self.person_instances != sum(
            len(frame.persons) for frame in self.frames
        ):
            raise ValueError("帧数或累计人次与实际明细不一致")
        return self


class DetectionEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")
    data: InferenceResult
