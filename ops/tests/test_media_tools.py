import pytest


def test_segmentation_covers_tail_without_gaps():
    from classroom_ops.segments import windows

    assert windows(65, 30) == [(0, 30), (30, 30), (60, 5)]
    with pytest.raises(ValueError):
        windows(float("inf"), 30)


def test_capacity_estimate_is_not_reported_as_measured_concurrency():
    from classroom_ops.benchmark import capacity

    result = capacity([10, 11, 12], 300, 300)
    assert result["measured_parallelism"] == 1
    assert result["estimated_workers"] >= 12
    assert result["production_capacity_passed"] is False


def test_capacity_respects_separate_school_execution_slots():
    from classroom_ops.benchmark import capacity

    result = capacity([11, 12, 12.6], 300, 600, schools=10)
    assert result["estimated_workers"] == 10
    assert result["estimated_workers_per_school"] == 1
    assert result["assumed_even_distribution"] is True
