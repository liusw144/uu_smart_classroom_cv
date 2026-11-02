from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class DetectionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    job_id: Annotated[UUID, Field(strict=False)]
    asset_id: Annotated[UUID, Field(strict=False)]
    input_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    model_version: Annotated[str, Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9._-]+$")]
    media_kind: Literal["IMAGE", "VIDEO", "CAMERA"] = "IMAGE"
