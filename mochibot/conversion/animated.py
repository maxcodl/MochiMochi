"""Animated WebP encoding and decimation ladder search."""

import logging
import struct
from io import BytesIO

from PIL import Image

from config import WA_MAX_ANIM_DURATION_MS, WA_MAX_BYTES

logger = logging.getLogger(__name__)


def _is_animated_webp_bytes(data: bytes) -> bool:
    if len(data) < 30:
        return False
    if data[0:4] != b'RIFF' or data[8:12] != b'WEBP':
        return False
    if data[12:16] == b'VP8X':
        flags = data[20] & 0xFF
        return bool(flags & 0x02)
    search_end = min(len(data), 512)
    return b'ANIM' in data[12:search_end]


def _count_webp_frames(data: bytes) -> int:
    count = 0
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos+4]
        csz = struct.unpack_from('<I', data, pos+4)[0]
        if csz > len(data):
            logger.debug(f"_count_webp_frames: oversized chunk at pos={pos} (csz={csz}), stopping")
            break
        if cid == b'ANMF':
            count += 1
        pos += 8 + csz + (csz & 1)
    return count if count > 0 else 1


def is_valid_webp_output(data: bytes, require_animated: bool = False) -> tuple[bool, str]:
    if not data or len(data) < 128:
        return False, "Converted output is empty or too small"
    if len(data) < 12 or data[0:4] != b'RIFF' or data[8:12] != b'WEBP':
        return False, "Converted output is not a valid WebP container"
    try:
        with Image.open(BytesIO(data)) as check:
            if check.width != 512 or check.height != 512:
                return False, f"Invalid dimensions {check.width}x{check.height}, expected 512x512"
            if require_animated:
                if not _is_animated_webp_bytes(data):
                    return False, "Expected animated WebP but got static output"
                frame_count = _count_webp_frames(data)
                if frame_count < 2:
                    return False, f"Animated WebP has only {frame_count} frame(s); WhatsApp requires >= 2"
    except Exception as e:
        return False, f"Cannot decode converted WebP: {e}"
    if len(data) > WA_MAX_BYTES:
        return False, f"Converted sticker is {len(data) // 1024}KB, exceeds WhatsApp's 500KB limit"
    return True, ""


def _estimate_starting_decimation(pil_frames: list, max_size: int) -> int:
    n = len(pil_frames)
    if n < 2:
        return 1

    sample_count = min(6, n)
    step = max(1, n // sample_count)
    sample_frames = pil_frames[::step][:sample_count]
    if len(sample_frames) < 2:
        sample_frames = pil_frames[:2]

    try:
        buf = BytesIO()
        sample_frames[0].save(
            buf, format="WEBP", save_all=True,
            append_images=sample_frames[1:],
            duration=50, loop=0, quality=12, method=6, alpha_quality=70,
            background=(0, 0, 0, 0),
        )
        sample_size = buf.tell()
        buf.close()
        estimated_full = int(sample_size * (n / len(sample_frames)) * 1.05)
        logger.debug(
            f"Floor-quality size estimate: sample={sample_size//1024}KB over {len(sample_frames)} frames → "
            f"estimated full={estimated_full//1024}KB over {n} frames"
        )

        if estimated_full <= max_size:
            return 1

        for decimation in [2, 3, 4]:
            estimated = estimated_full // decimation
            if estimated <= max_size:
                logger.info(
                    f"Even at floor quality, full frame rate estimated at "
                    f"{estimated_full//1024}KB > {max_size//1024}KB limit — "
                    f"starting at decimation={decimation}x"
                )
                return decimation
        return 4
    except Exception as e:
        logger.debug(f"Size estimation failed ({e}), starting at decimation=1x")
        return 1


def _encode_animated_webp_under_limit(
    pil_frames: list,
    frame_duration_ms: int,
    max_size: int = 490 * 1024,
) -> BytesIO:
    if len(pil_frames) < 2:
        raise Exception(f"Cannot create animated WebP with only {len(pil_frames)} frame(s). Need at least 2.")

    # Ensure second frame is not bit-for-bit identical to the first frame so libwebp does not collapse them to 1 frame / static WebP
    if len(pil_frames) >= 2:
        px = pil_frames[1].load()
        r, g, b, a = px[0, 0]
        px[0, 0] = (r, g, b, 1 if a == 0 else 0)

    def _try(frames, dur_ms, q, method, alpha_q=90):
        buf = BytesIO()
        frames[0].save(
            buf, format="WEBP", save_all=True,
            append_images=frames[1:],
            duration=dur_ms, loop=0,
            quality=q, method=method,
            kmax=1,
            allow_mixed=True,
            alpha_quality=alpha_q,
            background=(0, 0, 0, 0),
        )
        return buf

    def _quality_search(frames, dur_ms, qualities, method, alpha_q=90):
        for q in qualities:
            buf = _try(frames, dur_ms, q, method, alpha_q)
            sz = buf.tell()
            if sz <= max_size:
                buf.seek(0)
                return buf, q, sz, method
            buf.close()
        return None, None, 0, None

    def _predict_quality(frames, dur_ms, method=4, alpha_q=80):
        n = len(frames)
        sample_count = min(6, n)
        step = max(1, n // sample_count)
        samples = frames[::step][:sample_count]
        if len(samples) < 2:
            samples = frames[:2]
        try:
            hi_buf = _try(samples, dur_ms, 60, method, alpha_q)
            lo_buf = _try(samples, dur_ms, 20, method, alpha_q)
            full_hi = hi_buf.tell() * n / len(samples)
            full_lo = lo_buf.tell() * n / len(samples)
            hi_buf.close()
            lo_buf.close()
            slope = (full_hi - full_lo) / 40.0
            if slope <= 0:
                return 40
            target = max_size * 0.92
            q = 20 + (target - full_lo) / slope
            return int(max(12, min(72, q)))
        except Exception:
            return 40

    start_decimation = _estimate_starting_decimation(pil_frames, max_size)
    decimation_levels = [d for d in [1, 2, 3, 4] if d >= start_decimation]

    for decimation in decimation_levels:
        if decimation == 1:
            cur_frames = pil_frames
            cur_dur = frame_duration_ms
        else:
            cur_frames = pil_frames[::decimation]
            cur_dur = min(frame_duration_ms * decimation, 1000)
            if len(cur_frames) < 2:
                logger.debug(f"Decimation {decimation}x would yield {len(cur_frames)} frame(s) - skipping")
                continue
            logger.info(
                f"Animated WebP still too large — decimating to every {decimation}th frame "
                f"({len(cur_frames)} frames, {cur_dur}ms/frame)"
            )

        max_frames_for_duration = max(2, WA_MAX_ANIM_DURATION_MS // cur_dur)
        if len(cur_frames) > max_frames_for_duration:
            logger.info(
                f"Trimming {len(cur_frames)} → {max_frames_for_duration} frames to stay "
                f"under WhatsApp's {WA_MAX_ANIM_DURATION_MS}ms animation duration cap "
                f"({cur_dur}ms/frame would otherwise total {len(cur_frames) * cur_dur}ms)"
            )
            cur_frames = cur_frames[:max_frames_for_duration]

        predicted_q = _predict_quality(cur_frames, cur_dur)
        candidates = sorted(
            {q for q in (predicted_q, predicted_q - 10, predicted_q - 20, predicted_q - 32, 12) if 12 <= q <= 72},
            reverse=True,
        )
        best_buf, best_q, best_size, used_method = _quality_search(
            cur_frames, cur_dur, qualities=candidates, method=4, alpha_q=80,
        )

        if best_buf is None and 12 not in candidates:
            best_buf, best_q, best_size, used_method = _quality_search(
                cur_frames, cur_dur, qualities=[12], method=6, alpha_q=65,
            )

        if best_buf is not None:
            buf_data = best_buf.getvalue()
            if _count_webp_frames(buf_data) < 2 or not _is_animated_webp_bytes(buf_data):
                logger.warning("Encoded WebP has <2 frames; wrapping as 2-frame animation")
                best_buf.close()
                from .static import convert_to_whatsapp_one_frame_animation
                return convert_to_whatsapp_one_frame_animation(pil_frames[0])

            logger.info(
                f"✓ Animated WebP: {len(cur_frames)} frames "
                f"(decimation={decimation}x), quality={best_q}, method={used_method}, "
                f"{best_size // 1024}KB"
            )
            return best_buf

        logger.info(f"Still over limit at decimation={decimation}x — trying more aggressive decimation…")

    raise Exception(
        f"Could not encode animated WebP under {max_size // 1024}KB limit even with "
        f"maximum decimation (4x) and minimum fallback quality (12). "
        f"Sticker is too complex to convert."
    )
