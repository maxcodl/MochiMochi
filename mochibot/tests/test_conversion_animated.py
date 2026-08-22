"""Tests for animated WebP decimation ladder and WebP output checks."""

from PIL import Image

from conversion.animated import (
    _count_webp_frames,
    _encode_animated_webp_under_limit,
    is_valid_webp_output,
)


def test_encode_animated_webp_under_limit():
    frames = [Image.new("RGBA", (512, 512), (i * 20, 100, 100, 255)) for i in range(5)]
    output = _encode_animated_webp_under_limit(frames, frame_duration_ms=100)
    data = output.getvalue()
    output.close()
    for f in frames:
        f.close()

    assert len(data) > 0
    valid, reason = is_valid_webp_output(data, require_animated=True)
    assert valid is True, f"Failed validation: {reason}"
    assert _count_webp_frames(data) >= 2


def test_encode_animated_webp_with_identical_frames():
    # When all frames are identical, ensure libwebp output is not collapsed to <2 frames
    frames = [Image.new("RGBA", (512, 512), (100, 150, 200, 255)) for _ in range(4)]
    output = _encode_animated_webp_under_limit(frames, frame_duration_ms=100)
    data = output.getvalue()
    output.close()
    for f in frames:
        f.close()

    assert len(data) > 0
    valid, reason = is_valid_webp_output(data, require_animated=True)
    assert valid is True, f"Failed validation for identical frames: {reason}"
    assert _count_webp_frames(data) >= 2


def test_encode_animated_webp_two_duplicated_frames():
    # Exactly 2 identical frames
    f1 = Image.new("RGBA", (512, 512), (255, 0, 0, 255))
    f2 = f1.copy()
    output = _encode_animated_webp_under_limit([f1, f2], frame_duration_ms=100)
    data = output.getvalue()
    output.close()
    f1.close()
    f2.close()

    assert len(data) > 0
    valid, reason = is_valid_webp_output(data, require_animated=True)
    assert valid is True, f"Failed validation for 2 duplicated frames: {reason}"
    assert _count_webp_frames(data) >= 2

