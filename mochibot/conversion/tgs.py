"""TGS (Lottie) sticker rendering and WebP conversion."""

import asyncio
import gzip
import json
import logging
import subprocess
import sys
import tempfile
from io import BytesIO
from pathlib import Path

from PIL import Image

from config import WA_ANIM_TARGET, WA_MAX_ANIM_DURATION_MS, WA_MAX_BYTES
from resources import resources
from storage import storage
from .animated import _encode_animated_webp_under_limit

logger = logging.getLogger(__name__)


def _tgs_render_frames_sync(json_bytes: bytes, ip: int, n_frames: int) -> list:
    from lottie.exporters.cairo import export_png
    from lottie.parsers.tgs import parse_tgs_json

    anim = parse_tgs_json(BytesIO(json_bytes))
    raw_frames = []
    for frame_i in range(ip, ip + n_frames):
        buf = BytesIO()
        export_png(anim, buf, frame=frame_i)
        raw_frames.append(buf.getvalue())
        buf.close()
    return raw_frames


async def convert_tgs_to_animated_webp(tgs_data: bytes) -> BytesIO:
    try:
        with gzip.open(BytesIO(tgs_data), 'rb') as gz:
            json_data = gz.read()
    except Exception as e:
        raise Exception(f"Invalid TGS file: {e}")

    try:
        anim_meta = json.loads(json_data)
        fps = max(1.0, float(anim_meta.get('fr', 30)))
        in_point = int(anim_meta.get('ip', 0))
        out_point = int(anim_meta.get('op', 90))
    except Exception:
        fps, in_point, out_point = 30.0, 0, 90

    render_frames = min(max(1, out_point - in_point), int(WA_MAX_ANIM_DURATION_MS / 1000.0 * fps), 120)
    frame_duration_ms = max(8, int(1000.0 / fps))

    pil_frames = None
    try:
        loop = asyncio.get_running_loop()
        timeout_secs = storage.get("tgs_render_timeout", 60)
        raw_frames = await asyncio.wait_for(
            loop.run_in_executor(
                resources.get_process_pool(),
                _tgs_render_frames_sync, json_data, in_point, render_frames
            ),
            timeout=timeout_secs,
        )
        pil_frames = []
        for raw in raw_frames:
            with Image.open(BytesIO(raw)) as img:
                img_rgba = img.convert("RGBA")
                canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
                img_rgba.thumbnail((512, 512), Image.LANCZOS)
                canvas.paste(img_rgba, ((512 - img_rgba.width) // 2, (512 - img_rgba.height) // 2), img_rgba)
                img_rgba.close()
        if len(pil_frames) == 1:
            logger.info("TGS render produced 1 frame; duplicating with toggled bit for WhatsApp animation requirement")
            duplicate_canvas = pil_frames[0].copy()
            px = duplicate_canvas.load()
            r, g, b, a = px[0, 0]
            px[0, 0] = (r, g, b, 1 if a == 0 else 0)
            pil_frames.append(duplicate_canvas)
        logger.info(f"Rendered {len(pil_frames)} TGS frames in-process")
    except asyncio.TimeoutError:
        logger.warning(f"TGS in-process render timed out after {timeout_secs}s, falling back to GIF")
        pil_frames = None
    except Exception as png_err:
        logger.warning(f"In-process lottie render failed: {png_err}, falling back to GIF subprocess")
        pil_frames = None

    if pil_frames is None or len(pil_frames) == 0:
        if pil_frames:
            for f in pil_frames:
                f.close()
        with tempfile.TemporaryDirectory() as tmpdir:
            tmppath = Path(tmpdir)
            json_path = tmppath / "sticker.json"
            json_path.write_bytes(json_data)
            gif_path = tmppath / "sticker.gif"
            cmd = [sys.executable, "-m", "lottie.exporters.gif", str(json_path), str(gif_path)]
            try:
                proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
                if proc.returncode != 0 or not gif_path.exists() or gif_path.stat().st_size == 0:
                    raise Exception(
                        f"TGS GIF fallback failed: {proc.stderr[:200] if proc.stderr else 'empty GIF output'}"
                    )
            except subprocess.TimeoutExpired:
                raise Exception("TGS GIF fallback timed out after 30s")

            with open(gif_path, 'rb') as f:
                gif_data = f.read()

        logger.warning(f"TGS → GIF fallback ({len(gif_data) / 1024:.1f}KB) — smooth transparency NOT preserved")
        from .video import convert_video_to_animated_webp
        return await convert_video_to_animated_webp(gif_data)

    try:
        output = await resources.run_cpu_bound(
            _encode_animated_webp_under_limit, pil_frames, frame_duration_ms, WA_ANIM_TARGET
        )
        output_size = output.seek(0, 2)
        output.seek(0)
        if output_size > WA_MAX_BYTES:
            output.close()
            raise Exception(f"Encoded output {output_size // 1024}KB exceeds 500KB limit")
        logger.info(f"✓ TGS → animated WebP: {len(pil_frames)} frames, {output_size // 1024}KB")
        return output
    finally:
        for f in pil_frames:
            try:
                f.close()
            except Exception:
                pass


async def convert_to_whatsapp_animated(file_data: bytes, is_tgs: bool) -> BytesIO:
    if is_tgs:
        logger.info("Converting TGS sticker to animated WebP...")
        return await convert_tgs_to_animated_webp(file_data)
    else:
        logger.info("Converting video sticker to animated WebP...")
        from .video import convert_video_to_animated_webp
        return await convert_video_to_animated_webp(file_data)
