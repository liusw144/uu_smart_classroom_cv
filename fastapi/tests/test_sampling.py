from classroom_vision.inference.onnx_pose import video_samples


def test_long_video_samples_include_first_and_final_decodable_frames():
    points, strategy, interval = video_samples(360, 30, 250, 32)
    assert len(points) == 32
    assert points[0] == (0, 0)
    assert points[-1] == (359, 11967)
    assert strategy == "UNIFORM_FULL_SPAN"
    assert interval >= 250
    assert len({index for index, _ in points}) == len(points)


def test_short_video_keeps_existing_four_sample_count():
    points, strategy, interval = video_samples(5, 5, 250, 32)
    assert len(points) == 4
    assert points[-1][0] == 4
    assert strategy == "FIXED_INTERVAL_WITH_TAIL"


def test_low_frame_rate_has_no_duplicate_frame_counts():
    points, _, _ = video_samples(3, 1, 250, 32)
    assert [index for index, _ in points] == [0, 1, 2]
