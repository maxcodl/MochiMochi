"""PyAV-based stream metadata probing and in-memory frame decoding."""

import logging
from io import BytesIO

import av
from PIL import Image

from config import WA_ANIM_TARGET, WA_MAX_ANIM_DURATION_MS, WA_MAX_BYTES
from resources import resources
from .animated import _encode_animated_webp_under_limit

logger = logging.getLogger(__name__)


def parse_frame_rate(fps_val) -> float:
    try:
        if hasattr(fps_val, "numerator") and hasattr(fps_val, "denominator"):
            if fps_val.denominator == 0:
                return 15.0
            return float(fps_val.numerator) / float(fps_val.denominator)
        if isinstance(fps_val, str):
            if '/' in fps_val:
                num, denom = fps_val.split('/')
                n, d = float(num), float(denom)
                if d == 0:
                    return 15.0
                return n / d
            return float(fps_val)
        return float(fps_val)
    except (ValueError, TypeError, ZeroDivisionError):
        logger.warning(f"parse_frame_rate: cannot parse '{fps_val}', falling back to 15fps")
        return 15.0


def probe_video_stream(video_data: bytes) -> tuple[float, float, int]:
    """Probes video metadata (duration, fps, frame count) directly via PyAV."""
    try:
        with av.open(BytesIO(video_data)) as container:
            if not container.streams.video:
                return 0.0, 15.0, 0
            stream = container.streams.video[0]

            duration = 0.0
            if container.duration is not None and container.duration > 0:
                duration = float(container.duration) / float(av.time_base)
            elif stream.duration is not None and stream.time_base is not None:
                duration = float(stream.duration * stream.time_base)

            fps = parse_frame_rate(stream.average_rate or stream.guessed_rate or 15.0)
            nb_frames = stream.frames if stream.frames is not None else 0

            if duration == 0.0 and nb_frames > 0 and fps > 0:
                duration = nb_frames / fps

            logger.info(f"PyAV video probe: duration={duration:.2f}s, fps={fps:.2f}, frames={nb_frames}")
            return duration, fps, nb_frames
    except Exception as e:
        logger.warning(f"PyAV probe failed: {e}, using default fallback metadata")
        return 0.0, 15.0, 0


def _frame_to_rgba_pil(frame: av.VideoFrame) -> Image.Image:
    """Converts a PyAV VideoFrame to an RGBA PIL Image preserving any alpha channel."""
    try:
        rgba_arr = frame.to_ndarray(format="rgba")
        return Image.fromarray(rgba_arr, mode="RGBA")
    except Exception:
        return frame.to_image().convert("RGBA")


def decode_video_frames_to_pil(video_data: bytes, target_fps: float, max_frames: int) -> list[Image.Image]:
    """Decodes video frames directly into memory as 512x512 RGBA PIL Images without disk intermediate files."""
    pil_frames = []
    try:
        with av.open(BytesIO(video_data)) as container:
            if not container.streams.video:
                raise Exception("No video streams found in input container")
            stream = container.streams.video[0]
            stream.thread_type = "AUTO"
            try:
                from storage import storage
                thread_count = storage.get("ffmpeg_threads", 1)
                stream.codec_context.thread_count = thread_count
            except Exception:
                thread_count = 1

            input_fps = parse_frame_rate(stream.average_rate or stream.guessed_rate or 15.0)
            if input_fps <= 0:
                input_fps = 15.0

            sample_step = max(1.0, input_fps / target_fps)
            frame_idx = 0
            next_sample = 0.0

            decoder = None
            if stream.codec_context.name == "vp9" and "libvpx-vp9" in av.codecs_available:
                try:
                    decoder = av.CodecContext.create("libvpx-vp9", "r")
                    decoder.thread_count = thread_count
                    if stream.codec_context.extradata:
                        decoder.extradata = stream.codec_context.extradata
                except Exception as e:
                    logger.debug(f"Could not create libvpx-vp9 decoder context: {e}")
                    decoder = None

            def _process_frame(frame: av.VideoFrame) -> bool:
                nonlocal frame_idx, next_sample
                if frame_idx >= next_sample:
                    img = _frame_to_rgba_pil(frame)
                    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
                    img.thumbnail((512, 512), Image.LANCZOS)
                    x = (512 - img.width) // 2
                    y = (512 - img.height) // 2
                    canvas.paste(img, (x, y), img)
                    img.close()
                    pil_frames.append(canvas)

                    if len(pil_frames) >= max_frames:
                        return True
                    next_sample += sample_step
                frame_idx += 1
                return False

            if decoder:
                for packet in container.demux(stream):
                    try:
                        frames = decoder.decode(packet)
                    except (av.error.EOFError, av.error.FFmpegError):
                        break
                    for frame in frames:
                        if _process_frame(frame):
                            break
                    if len(pil_frames) >= max_frames:
                        break
                if len(pil_frames) < max_frames:
                    try:
                        for frame in decoder.decode():
                            if _process_frame(frame):
                                break
                    except (av.error.EOFError, av.error.FFmpegError, Exception):
                        pass
            else:
                for frame in container.decode(video=0):
                    if _process_frame(frame):
                        break

    except Exception as e:
        logger.warning(f"Error during PyAV frame decoding: {e}")
        # Clean up any loaded frames on error
        for f in pil_frames:
            try:
                f.close()
            except Exception:
                pass
        raise

    return pil_frames


def _sync_convert_video_to_animated_webp(video_data: bytes) -> BytesIO:
    _duration, input_fps, _nb_frames = probe_video_stream(video_data)

    target_fps = min(input_fps, 20.0)
    target_fps = max(target_fps, 8.0)
    frame_duration_ms = max(8, int(1000.0 / target_fps))
    max_frames = min(240, int(WA_MAX_ANIM_DURATION_MS / frame_duration_ms))

    pil_frames = decode_video_frames_to_pil(video_data, target_fps, max_frames)

    try:
        if len(pil_frames) == 0:
            raise Exception("No valid frames could be decoded from video")
        if len(pil_frames) == 1:
            logger.warning("Video sticker has only 1 frame; duplicating to satisfy WhatsApp animated requirement")
            duplicate_canvas = pil_frames[0].copy()
            px = duplicate_canvas.load()
            r, g, b, a = px[0, 0]
            px[0, 0] = (r, g, b, 1 if a == 0 else 0)
            pil_frames.append(duplicate_canvas)

        output = _encode_animated_webp_under_limit(pil_frames, frame_duration_ms, WA_ANIM_TARGET)
        final_size = output.seek(0, 2)
        output.seek(0)

        if final_size > WA_MAX_BYTES:
            output.close()
            raise Exception(f"Encoded output is {final_size // 1024}KB, exceeds 500KB hard limit")
        if final_size > WA_ANIM_TARGET:
            output.close()
            raise Exception(f"Encoder bug: output is {final_size // 1024}KB, exceeds target {WA_ANIM_TARGET // 1024}KB")

        logger.info(
            f"✓ Video → animated WebP (PyAV): "
            f"{len(pil_frames)} source frames, {final_size / 1024:.1f}KB, "
            f"fps={target_fps:.0f}"
        )
        return output
    finally:
        for f in pil_frames:
            try:
                f.close()
            except Exception:
                pass


async def convert_video_to_animated_webp(video_data: bytes) -> BytesIO:
    return await resources.run_cpu_bound(_sync_convert_video_to_animated_webp, video_data)
