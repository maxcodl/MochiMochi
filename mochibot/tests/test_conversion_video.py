"""Tests for PyAV video probing and frame decoding."""

import io
import os
import subprocess
import tempfile
import pytest
from PIL import Image

from conversion.tray import optimize_tray_icon
from conversion.video import (
    _sync_convert_video_to_animated_webp,
    decode_video_frames_to_pil,
    parse_frame_rate,
    probe_video_stream,
)


def test_parse_frame_rate():
    assert parse_frame_rate("30/1") == 30.0
    assert parse_frame_rate("15") == 15.0
    assert parse_frame_rate("invalid") == 15.0
    assert parse_frame_rate("30/0") == 15.0


def test_probe_video_stream_invalid_data():
    duration, fps, frames = probe_video_stream(b"invalid data")
    assert duration == 0.0
    assert fps == 15.0
    assert frames == 0


@pytest.fixture
def sample_vp9_alpha_webm():
    """Generates a small VP9 WebM with alpha channel transparency."""
    with tempfile.TemporaryDirectory() as tmpdir:
        png_path = os.path.join(tmpdir, "frame.png")
        webm_path = os.path.join(tmpdir, "sticker.webm")

        img = Image.new("RGBA", (200, 100), (0, 0, 0, 0))
        for x in range(50, 150):
            for y in range(25, 75):
                img.putpixel((x, y), (255, 0, 0, 255))
        img.save(png_path)

        subprocess.run(
            [
                "ffmpeg", "-y", "-loop", "1", "-i", png_path,
                "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p",
                "-t", "0.2", webm_path,
            ],
            check=True,
            capture_output=True,
        )

        with open(webm_path, "rb") as f:
            return f.read()


def test_decode_video_frames_preserves_alpha(sample_vp9_alpha_webm):
    frames = decode_video_frames_to_pil(sample_vp9_alpha_webm, target_fps=15.0, max_frames=10)
    assert len(frames) > 0

    first_frame = frames[0]
    assert first_frame.size == (512, 512)
    assert first_frame.mode == "RGBA"

    # Transparent background padding check: corner must have alpha == 0
    corner_px = first_frame.getpixel((0, 0))
    assert corner_px[3] == 0, f"Expected transparent corner, got {corner_px}"

    # Center red rectangle check: center must have alpha > 0
    center_px = first_frame.getpixel((256, 256))
    assert center_px[0] > 200 and center_px[3] > 200, f"Expected red center, got {center_px}"


def test_optimize_tray_icon_preserves_video_alpha(sample_vp9_alpha_webm):
    tray_buf = optimize_tray_icon(sample_vp9_alpha_webm, is_animated=True)
    assert tray_buf is not None
    with Image.open(tray_buf) as tray_img:
        assert tray_img.size == (96, 96)
        assert tray_img.mode == "RGBA"
        corner_px = tray_img.getpixel((0, 0))
        assert corner_px[3] == 0, f"Expected transparent corner in tray icon, got {corner_px}"


def test_sync_convert_video_to_animated_webp(sample_vp9_alpha_webm):
    output_buf = _sync_convert_video_to_animated_webp(sample_vp9_alpha_webm)
    assert output_buf is not None
    output_bytes = output_buf.getvalue()
    assert len(output_bytes) > 0

    with Image.open(io.BytesIO(output_bytes)) as webp_img:
        assert webp_img.size == (512, 512)
        corner_px = webp_img.getpixel((0, 0))
        assert corner_px[3] == 0, f"Expected transparent corner in animated WebP, got {corner_px}"

