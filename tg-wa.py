import os
import re
import time
import zipfile
import logging
from io import BytesIO
from PIL import Image
from pyrogram import Client, filters
from pyrogram.errors import FloodWait
from pyrogram.types import Message, InlineKeyboardMarkup, InlineKeyboardButton
from dotenv import load_dotenv
import aiohttp
import traceback
from pyrogram.enums import ChatType
import asyncio
import json
import gzip
import tempfile
import subprocess
import concurrent.futures
import ffmpeg
from pathlib import Path
import sys
import shutil
import emoji

load_dotenv()

logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO
)
logger = logging.getLogger(__name__)
logging.getLogger("pyrogram").setLevel(logging.WARNING)

API_ID = os.getenv("API_ID")
API_HASH = os.getenv("API_HASH")
BOT_TOKEN = os.getenv("BOT_TOKEN")
OWNER_ID = int(os.getenv("OWNER_ID", 0))

if not API_ID or not API_HASH or not BOT_TOKEN:
    logger.error("Missing environment variables in .env file!")
    logger.info("Please create a .env file with API_ID, API_HASH, and BOT_TOKEN")
    exit(1)
if not OWNER_ID:
    logger.warning("OWNER_ID not set in .env. Authorization commands will be disabled!")

app = Client(
    "sticker_pack_bot",
    api_id=int(API_ID),
    api_hash=API_HASH,
    bot_token=BOT_TOKEN
)

BASE_DIR = Path(__file__).resolve().parent
AUTHORIZED_CHATS_FILE = BASE_DIR / 'authorized_chats.json'
CONFIG_FILE = BASE_DIR / 'config.json'
APP_URL = "http://github.com/maxcodl/MochiMochi/releases/"

try:
    with open(AUTHORIZED_CHATS_FILE, 'r') as f:
        AUTHORIZED_CHATS = set(json.load(f))
except FileNotFoundError:
    AUTHORIZED_CHATS = set()
except Exception as e:
    logger.error(f"Error loading authorized chats: {e}")
    AUTHORIZED_CHATS = set()

# ── Resource / settings config ───────────────────────────────────────────────
# These values control how much CPU and RAM the bot uses during conversion.
# They are persisted in config.json and editable at runtime via /settings.

CONFIG_DEFAULTS = {
    "max_concurrent":      5,   # simultaneous sticker downloads/conversions
    "process_pool_workers": 2,  # TGS lottie render worker processes
    "ffmpeg_threads":      2,   # threads passed to ffmpeg for video decode
    "tgs_render_timeout":  60,  # seconds before an in-process TGS render is aborted
}

def _load_config() -> dict:
    try:
        with open(CONFIG_FILE, 'r') as f:
            data = json.load(f)
        merged = {**CONFIG_DEFAULTS, **data}
        return merged
    except FileNotFoundError:
        return dict(CONFIG_DEFAULTS)
    except Exception as e:
        logger.error(f"Error loading config: {e}")
        return dict(CONFIG_DEFAULTS)

def _save_config(cfg: dict):
    try:
        with open(CONFIG_FILE, 'w') as f:
            json.dump(cfg, f, indent=2)
        logger.info(f"Config saved: {cfg}")
    except Exception as e:
        logger.error(f"Error saving config: {e}")

CONFIG = _load_config()

def _cfg(key: str):
    """Read a config value (always up-to-date from the in-memory dict)."""
    return CONFIG.get(key, CONFIG_DEFAULTS[key])

# ── WhatsApp sticker hard limits ─────────────────────────────────────────────
# NOTE: WhatsApp's own limit is exactly 500,000 bytes (decimal), not 500KiB
# (500*1024=512000) or 499*1024=510976 as this used to say. That KiB/KB mix-up
# let the encoder produce files up to ~11KB over the real cap — they'd pass
# every check in this script but still get rejected by the app with
# "exceeds 500,000 bytes". Keep these as plain decimal byte counts.
WA_MAX_BYTES   = 499_000
WA_ANIM_TARGET = 494_000

# WhatsApp's official cap: "Animation duration should be less than or equal
# to 10 seconds total" (WhatsApp/stickers repo, Android/iOS README). This is
# a PACK-LEVEL check on WhatsApp's own side — if even one sticker in a pack
# (or in one auto-split ~30-sticker chunk) exceeds this, WhatsApp rejects the
# whole chunk with a generic "problem with the sticker pack" /
# "handleStickerPackPreviewResult/failed" error, not a per-sticker message.
# Kept at 9800ms (200ms safety margin below the hard 10000ms limit) since our
# own frame-count math can round up to just over 10000ms otherwise.
WA_MAX_ANIM_DURATION_MS = 9_800

# ── Sticker thumbnail settings ────────────────────────────────────────────────
# Generated once here, at pack-creation time, instead of on-device during
# import. MUST match StickerProcessor.THUMB_SIZE on the Android side, or
# packs built by this bot will show a different thumbnail resolution than
# packs whose thumbnails were generated on-device (e.g. via the "regenerate
# thumbnails" migration path, or packs merged from older imports).
STICKER_THUMB_SIZE = 100
STICKER_THUMB_QUALITY = 80

# ── Shared HTTP session ───────────────────────────────────────────────────────
_HTTP_SESSION: aiohttp.ClientSession | None = None

async def _get_http_session() -> aiohttp.ClientSession:
    global _HTTP_SESSION
    if _HTTP_SESSION is None or _HTTP_SESSION.closed:
        timeout = aiohttp.ClientTimeout(total=60)
        _HTTP_SESSION = aiohttp.ClientSession(timeout=timeout)
    return _HTTP_SESSION

async def _run_cpu_bound(func, *args):
    """Run CPU-heavy sync work in a worker thread to avoid blocking the event loop.

    Uses _get_cpu_pool() (sized from config["max_concurrent"]) instead of the
    default executor (loop.run_in_executor(None, ...)). The default executor
    is a hidden, fixed-size ThreadPoolExecutor (min(32, os.cpu_count()+4))
    that's created once and never re-reads CONFIG — so bumping max_concurrent
    in /settings previously had *no effect* on the actual WebP-encode /
    ffmpeg.probe work done here, only on the asyncio.Semaphore gating how many
    stickers get dispatched in the first place. Encodes just piled up on the
    same unconfigurable pool regardless of the settings.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(_get_cpu_pool(), lambda: func(*args))

def _convert_static_bytes_to_webp(sticker_data: bytes) -> BytesIO:
    img = Image.open(BytesIO(sticker_data))
    return convert_to_whatsapp_static(img)

def generate_thumbnail_from_webp_bytes(webp_bytes: bytes, thumb_size: int = STICKER_THUMB_SIZE) -> bytes:
    """
    Builds a small static WebP thumbnail from an already-converted sticker's
    final WebP bytes. Works for both static and animated input — for animated
    WebP, only frame 0 is used (PIL defaults to frame 0 on open/seek(0)),
    matching how a sticker list preview is meant to look.

    This runs on bytes we already have in memory post-conversion, so it's a
    cheap extra resize rather than a fresh decode from disk. Generating it
    here means the Android app can just copy this file into the pack folder
    on import instead of decoding + resizing + re-encoding every sticker
    on-device (the slow part on SAF/SD-card-backed folders).
    """
    img = Image.open(BytesIO(webp_bytes))
    try:
        img.seek(0)
    except Exception:
        pass
    img = img.convert("RGBA")
    frame = img.copy()
    frame.thumbnail((thumb_size, thumb_size), Image.LANCZOS)
    canvas = Image.new("RGBA", (thumb_size, thumb_size), (0, 0, 0, 0))
    position = ((thumb_size - frame.width) // 2, (thumb_size - frame.height) // 2)
    canvas.paste(frame, position, frame)
    out = BytesIO()
    canvas.save(out, format="WEBP", quality=STICKER_THUMB_QUALITY, method=4)
    return out.getvalue()

# ── ProcessPoolExecutor (lazy, size driven by config) ────────────────────────
_PROCESS_POOL: concurrent.futures.ProcessPoolExecutor | None = None

def _get_process_pool() -> concurrent.futures.ProcessPoolExecutor:
    global _PROCESS_POOL
    workers = _cfg("process_pool_workers")
    if _PROCESS_POOL is None:
        _PROCESS_POOL = concurrent.futures.ProcessPoolExecutor(max_workers=workers)
        logger.info(f"ProcessPoolExecutor created with max_workers={workers}")
    return _PROCESS_POOL

def _rebuild_process_pool(workers: int):
    """Shut down the existing pool and create a new one with updated worker count."""
    global _PROCESS_POOL
    if _PROCESS_POOL is not None:
        _PROCESS_POOL.shutdown(wait=False)
        _PROCESS_POOL = None
    _PROCESS_POOL = concurrent.futures.ProcessPoolExecutor(max_workers=workers)
    logger.info(f"ProcessPoolExecutor rebuilt with max_workers={workers}")

# ── ThreadPoolExecutor for general CPU-bound work (lazy, size driven by config) ──
# Used by _run_cpu_bound() for WebP encoding and ffmpeg.probe() — the actual
# per-sticker heavy lifting for video/webm-based animated stickers (most
# Telegram animated packs), which never touches _PROCESS_POOL at all since
# that one's reserved for TGS/Lottie rendering only.
#
# A thread pool (not a process pool) is used deliberately here: Pillow's
# libwebp encoder is a C extension that releases the GIL during save(), so
# threads DO get real parallelism for the encode step without the cost of
# pickling full lists of 512x512 RGBA PIL.Image frames across a process
# boundary on every call (which a ProcessPoolExecutor would require).
_CPU_POOL: concurrent.futures.ThreadPoolExecutor | None = None

def _get_cpu_pool() -> concurrent.futures.ThreadPoolExecutor:
    global _CPU_POOL
    workers = _cfg("max_concurrent")
    if _CPU_POOL is None:
        _CPU_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
        logger.info(f"CPU ThreadPoolExecutor created with max_workers={workers}")
    return _CPU_POOL

def _rebuild_cpu_pool(workers: int):
    """Shut down the existing CPU pool and create a new one with updated worker count."""
    global _CPU_POOL
    if _CPU_POOL is not None:
        _CPU_POOL.shutdown(wait=False)
        _CPU_POOL = None
    _CPU_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
    logger.info(f"CPU ThreadPoolExecutor rebuilt with max_workers={workers}")

# ── Authorised chats ──────────────────────────────────────────────────────────
def save_authorized_chats():
    try:
        with open(AUTHORIZED_CHATS_FILE, 'w') as f:
            json.dump(list(AUTHORIZED_CHATS), f)
        logger.info("Authorized chats saved.")
    except Exception as e:
        logger.error(f"Error saving authorized chats: {e}")

# ── Filename sanitisation ─────────────────────────────────────────────────────
def sanitize_filename(name: str) -> str:
    name = name.lstrip("@")
    name = name.replace(".", "")
    name = name.encode("ascii", "ignore").decode("ascii")
    name = re.sub(r'[<>:"/\\|?*]', '', name)
    name = re.sub(r'[\s-]+', '_', name).strip("_")
    return name[:50] if name else "sticker_pack"

def _format_elapsed(seconds: float) -> str:
    """Human-readable elapsed time for pack captions, e.g. '8m 12s' or '43s'."""
    total = int(round(seconds))
    m, s = divmod(total, 60)
    return f"{m}m {s}s" if m else f"{s}s"

# ── WebP helpers ──────────────────────────────────────────────────────────────
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

async def verify_sticker(sticker_data: bytes, is_animated: bool, is_video: bool, file_id: str) -> dict:
    result = {'valid': True, 'reason': '', 'warnings': []}
    try:
        min_size = 3 * 1024
        if len(sticker_data) < min_size:
            result['valid'] = False
            result['reason'] = f"File too small ({len(sticker_data)} bytes, minimum: {min_size} bytes) - likely corrupt or invalid"
            return result

        max_size = 500 * 1024
        if len(sticker_data) > max_size:
            result['warnings'].append(f"Large file ({len(sticker_data)} bytes), will be compressed")

        if is_animated:
            try:
                with gzip.open(BytesIO(sticker_data), 'rb') as f:
                    json_data = f.read()
                    if len(json_data) < 50:
                        result['valid'] = False
                        result['reason'] = "TGS file appears empty or corrupt"
                        return result
            except Exception as e:
                result['valid'] = False
                result['reason'] = f"Invalid TGS format: {str(e)}"
                return result

        elif is_video:
            if not (len(sticker_data) >= 4 and sticker_data[:4] == b'\x1a\x45\xdf\xa3'):
                result['valid'] = False
                result['reason'] = "Not a valid WebM file (bad magic bytes)"
                return result

        else:
            try:
                img = Image.open(BytesIO(sticker_data))
                img.verify()
                img = Image.open(BytesIO(sticker_data))

                if img.width < 10 or img.height < 10:
                    result['valid'] = False
                    result['reason'] = f"Image too small ({img.width}x{img.height})"
                    return result

                if img.width > 5000 or img.height > 5000:
                    result['warnings'].append(f"Large dimensions ({img.width}x{img.height}), will be resized")

                if img.mode in ('RGBA', 'LA'):
                    import numpy as np
                    alpha = np.array(img.convert('RGBA'))[:, :, 3]
                    max_alpha = int(alpha.max())
                    if max_alpha == 0:
                        result['valid'] = False
                        result['reason'] = "Image is completely transparent (empty)"
                        return result
                    transparency_ratio = float((alpha < 10).sum()) / alpha.size
                    if transparency_ratio > 0.95:
                        result['warnings'].append(f"Image is {transparency_ratio*100:.1f}% transparent")

                extrema = img.convert('RGB').getextrema()
                if all(min_val == max_val for min_val, max_val in extrema):
                    result['warnings'].append("Image appears to be solid color")

            except Exception as e:
                result['valid'] = False
                # FIX: catch PIL's UnidentifiedImageError regardless of Pillow version
                result['reason'] = f"Invalid image format: {str(e)}"
                return result

        logger.info(f"Sticker {file_id[-8:]} verified: valid={result['valid']}, warnings={len(result['warnings'])}")
        return result

    except Exception as e:
        result['valid'] = False
        result['reason'] = f"Verification error: {str(e)}"
        return result

def optimize_tray_icon(tray_data: bytes, is_animated: bool = False) -> BytesIO:
    try:
        if is_animated:
            is_tgs = len(tray_data) >= 2 and tray_data[:2] == b'\x1f\x8b'
            if is_tgs:
                import gzip as _gzip
                json_bytes = _gzip.decompress(tray_data)
                try:
                    from lottie.parsers.tgs import parse_tgs_json
                    from lottie.exporters.cairo import export_png as _export_png
                    anim = parse_tgs_json(BytesIO(json_bytes))
                    frame_buf = BytesIO()
                    _export_png(anim, frame_buf, frame=0)
                    frame_buf.seek(0)
                    img = Image.open(frame_buf).copy()
                    img = img.resize((96, 96), Image.LANCZOS)
                    logger.info("Rendered TGS first frame via lottie/cairo for tray icon")
                except Exception as e:
                    # FIX: log which import/render step failed so debugging isn't blind
                    logger.debug(f"lottie cairo render failed for tray icon: {e}")
                    raise Exception(f"lottie cairo render failed: {e}")
            else:
                logger.info("Extracting first frame from animated sticker for tray icon")
                with tempfile.TemporaryDirectory() as tmpdir:
                    tmppath = Path(tmpdir)
                    input_path = tmppath / "input.webm"
                    input_path.write_bytes(tray_data)
                    output_path = tmppath / "frame.png"

                    img = None
                    try:
                        tmp_img = Image.open(BytesIO(tray_data))
                        tmp_img.seek(0)
                        img = tmp_img.copy()
                        logger.info("Opened animated sticker first frame via Pillow for tray icon")
                    except Exception:
                        img = None

                    if img is None:
                        for decoder_args in (['-c:v', 'libvpx-vp9'], []):
                            cmd = [
                                'ffmpeg', '-y',
                                *decoder_args,
                                '-i', str(input_path),
                                '-vf', r'select=eq(n\,0),format=rgba',
                                '-frames:v', '1',
                                '-vsync', 'vfr',
                                str(output_path)
                            ]
                            result = subprocess.run(cmd, capture_output=True, text=True, shell=False)
                            if result.returncode == 0 and output_path.exists():
                                with open(output_path, 'rb') as f:
                                    frame_data = f.read()
                                img = Image.open(BytesIO(frame_data)).copy()
                                logger.info("Successfully extracted first frame for tray icon")
                                break
                        else:
                            logger.warning(f"Failed to extract frame: {result.stderr}")
                            raise Exception("Frame extraction failed")
        else:
            img = Image.open(BytesIO(tray_data)).copy()

    except Exception as e:
        logger.warning(f"Tray icon conversion failed, using transparent placeholder: {e}")
        img = Image.new("RGBA", (96, 96), (0, 0, 0, 0))

    if img.mode != "RGBA":
        img = img.convert("RGBA")

    canvas = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    img.thumbnail((96, 96), Image.LANCZOS)
    position = ((96 - img.width) // 2, (96 - img.height) // 2)
    canvas.paste(img, position, img)

    output = BytesIO()
    for compress_level in range(3, 10):
        output.seek(0)
        output.truncate(0)
        canvas.save(output, format="PNG", optimize=True, compress_level=compress_level)
        if output.tell() < 50000:
            break

    output.seek(0)
    return output

def parse_frame_rate(fps_string: str) -> float:
    try:
        if '/' in fps_string:
            num, denom = fps_string.split('/')
            n, d = float(num), float(denom)
            if d == 0:
                # FIX: log bad value instead of silently swallowing ZeroDivisionError
                logger.warning(f"parse_frame_rate: zero denominator in '{fps_string}', falling back to 15fps")
                return 15.0
            return n / d
        else:
            return float(fps_string)
    except ValueError:
        logger.warning(f"parse_frame_rate: cannot parse '{fps_string}', falling back to 15fps")
        return 15.0

def convert_to_whatsapp_static(img: Image.Image) -> BytesIO:
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    img.thumbnail((512, 512), Image.LANCZOS)
    position = ((512 - img.width) // 2, (512 - img.height) // 2)
    canvas.paste(img, position, img)

    output = BytesIO()
    quality = 95
    max_attempts = 10
    for attempt in range(max_attempts):
        output.seek(0)
        output.truncate(0)
        canvas.save(output, format="WEBP", quality=quality)
        size = output.tell()
        if size <= 100 * 1024:
            break
        if quality > 75:
            quality -= 5
        elif quality > 50:
            quality -= 10
        else:
            quality -= 15
        if quality < 5:
            logger.warning(f"Static sticker is {size/1024:.1f}KB, exceeds 100KB limit")
            break

    output.seek(0)
    return output

def convert_to_whatsapp_one_frame_animation(img: Image.Image) -> BytesIO:
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    img.thumbnail((512, 512), Image.LANCZOS)
    position = ((512 - img.width) // 2, (512 - img.height) // 2)
    canvas.paste(img, position, img)

    output = BytesIO()
    frame2 = canvas.copy()
    px = frame2.load()
    r, g, b, a = px[0, 0]
    px[0, 0] = (r, g, b, 1 if a == 0 else 0)

    attempts = [
        (85, 0, 90), (75, 0, 85), (65, 4, 80),
        (55, 4, 75), (45, 6, 70), (35, 6, 65),
    ]
    for quality, method, alpha_quality in attempts:
        output.seek(0)
        output.truncate(0)
        canvas.save(
            output, format="WEBP", save_all=True,
            append_images=[frame2],
            duration=[100, 100], loop=0,
            quality=quality, method=method,
            alpha_quality=alpha_quality,
            background=(0, 0, 0, 0), lossless=False
        )
        if output.tell() <= WA_MAX_BYTES:
            output.seek(0)
            if not _is_animated_webp_bytes(output.getvalue()):
                raise Exception("Failed to force animated WebP output")
            return output

    raise Exception("Could not encode one-frame animation under 500KB")

def _estimate_starting_decimation(pil_frames: list, max_size: int) -> int:
    """
    Estimate whether full frame rate (decimation=1x) can possibly fit before
    committing to it, and only recommend dropping frames if it genuinely
    cannot — even at the encoder's floor quality. Dropping frames is far more
    noticeable to a viewer than dropping quality ("choppy" vs "a bit soft"),
    so this samples at the LOWEST quality the encoder will ever try (12)
    rather than a mid-range quality — that way full frame rate only gets
    skipped when there's truly no path to fitting under the size limit
    without losing frames, not just because a higher-quality guess looked big.
    """
    n = len(pil_frames)
    if n < 2:
        return 1

    # Sample up to 6 evenly spaced frames for a fast size estimate
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
        # Extrapolate at floor quality — small margin since we're already at
        # the bottom of the quality range and can't shrink much further.
        estimated_full = int(sample_size * (n / len(sample_frames)) * 1.05)
        logger.debug(f"Floor-quality size estimate: sample={sample_size//1024}KB over {len(sample_frames)} frames → "
                     f"estimated full={estimated_full//1024}KB over {n} frames")

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
        return None, None, 0, None

    def _predict_quality(frames, dur_ms, method=4, alpha_q=80):
        """
        Cheaply estimate roughly which quality value will land closest to
        max_size for THIS frame count, using two small samples (quality 20
        and 60) to build a rough linear size-vs-quality model, instead of
        blindly scanning the whole ladder from the top every time. This is
        what actually cuts encode time — the old code always started probing
        at quality=72 regardless of how likely that was to succeed.
        """
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
            slope = (full_hi - full_lo) / 40.0  # bytes per quality point
            if slope <= 0:
                return 40
            target = max_size * 0.92  # small safety margin below the hard cap
            q = 20 + (target - full_lo) / slope
            return int(max(12, min(72, q)))
        except Exception:
            return 40

    # Pick a smart starting decimation level based on a fast size estimate,
    # avoiding wasted encode attempts that are statistically certain to fail.
    start_decimation = _estimate_starting_decimation(pil_frames, max_size)

    decimation_levels = [d for d in [1, 2, 3, 4] if d >= start_decimation]
    for decimation in decimation_levels:
        if decimation == 1:
            cur_frames = pil_frames
            cur_dur    = frame_duration_ms
        else:
            cur_frames = pil_frames[::decimation]
            cur_dur    = min(frame_duration_ms * decimation, 1000)
            if len(cur_frames) < 2:
                logger.debug(f"Decimation {decimation}x would yield {len(cur_frames)} frame(s) - skipping")
                continue
            logger.info(
                f"Animated WebP still too large — decimating to every {decimation}th frame "
                f"({len(cur_frames)} frames, {cur_dur}ms/frame)"
            )

        # FIX: WhatsApp hard-rejects a whole pack chunk if ANY sticker's total
        # animation duration exceeds ~10s (see WA_MAX_ANIM_DURATION_MS above).
        # This is independent of frame count/quality/size — a sticker can pass
        # every other check and still trigger a pack-level "problem with the
        # sticker pack" error on WhatsApp's side purely from running too long
        # in real time. Truncate trailing frames (not resample) so we keep
        # full smoothness on the frames we do show, and just cut the tail —
        # cheaper than dropping frames throughout and still under 2 frames
        # only in pathological single-frame-duration-over-cap cases.
        max_frames_for_duration = max(2, WA_MAX_ANIM_DURATION_MS // cur_dur)
        if len(cur_frames) > max_frames_for_duration:
            logger.info(
                f"Trimming {len(cur_frames)} → {max_frames_for_duration} frames to stay "
                f"under WhatsApp's {WA_MAX_ANIM_DURATION_MS}ms animation duration cap "
                f"({cur_dur}ms/frame would otherwise total {len(cur_frames) * cur_dur}ms)"
            )
            cur_frames = cur_frames[:max_frames_for_duration]

        # Jump straight to roughly the right quality instead of scanning the
        # ladder from the top — only a handful of step-downs from there.
        predicted_q = _predict_quality(cur_frames, cur_dur)
        candidates = sorted(
            {q for q in (predicted_q, predicted_q - 10, predicted_q - 20, predicted_q - 32, 12) if 12 <= q <= 72},
            reverse=True,
        )
        best_buf, best_q, best_size, used_method = _quality_search(
            cur_frames, cur_dur, qualities=candidates, method=4, alpha_q=80,
        )
        # Last-resort single attempt at the true floor with the slowest but
        # most efficient compression method, only if the fast path missed.
        if best_buf is None and 12 not in candidates:
            best_buf, best_q, best_size, used_method = _quality_search(
                cur_frames, cur_dur, qualities=[12], method=6, alpha_q=65,
            )

        if best_buf is not None:
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

# ── TGS in-process renderer ───────────────────────────────────────────────────
def _tgs_render_frames_sync(json_bytes: bytes, ip: int, n_frames: int) -> list:
    from io import BytesIO
    from lottie.parsers.tgs import parse_tgs_json
    from lottie.exporters.cairo import export_png

    anim = parse_tgs_json(BytesIO(json_bytes))
    raw_frames = []
    for frame_i in range(ip, ip + n_frames):
        buf = BytesIO()
        export_png(anim, buf, frame=frame_i)
        raw_frames.append(buf.getvalue())
    return raw_frames

async def convert_tgs_to_animated_webp(tgs_data: bytes) -> BytesIO:
    import json as _json

    try:
        with gzip.open(BytesIO(tgs_data), 'rb') as gz:
            json_data = gz.read()
    except Exception as e:
        raise Exception(f"Invalid TGS file: {e}")

    try:
        anim_meta = _json.loads(json_data)
        fps = max(1.0, float(anim_meta.get('fr', 30)))
        in_point  = int(anim_meta.get('ip', 0))
        out_point = int(anim_meta.get('op', 90))
    except Exception:
        fps, in_point, out_point = 30.0, 0, 90

    render_frames     = min(max(1, out_point - in_point), int(WA_MAX_ANIM_DURATION_MS / 1000.0 * fps), 120)
    frame_duration_ms = max(8, int(1000.0 / fps))

    pil_frames = None
    try:
        loop = asyncio.get_running_loop()
        timeout_secs = _cfg("tgs_render_timeout")
        # FIX: wrap in asyncio.wait_for to prevent runaway TGS renders from blocking forever
        raw_frames = await asyncio.wait_for(
            loop.run_in_executor(
                _get_process_pool(),
                _tgs_render_frames_sync, json_data, in_point, render_frames
            ),
            timeout=timeout_secs,
        )
        pil_frames = []
        for raw in raw_frames:
            img = Image.open(BytesIO(raw)).convert("RGBA")
            canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
            img.thumbnail((512, 512), Image.LANCZOS)
            canvas.paste(img, ((512 - img.width) // 2, (512 - img.height) // 2), img)
            pil_frames.append(canvas)
        logger.info(f"Rendered {len(pil_frames)} TGS frames in-process")
    except asyncio.TimeoutError:
        logger.warning(f"TGS in-process render timed out after {timeout_secs}s, falling back to GIF")
        pil_frames = None
    except Exception as png_err:
        logger.warning(f"In-process lottie render failed: {png_err}, falling back to GIF subprocess")

    if pil_frames is None or len(pil_frames) < 2:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmppath = Path(tmpdir)
            json_path = tmppath / "sticker.json"
            json_path.write_bytes(json_data)
            gif_path = tmppath / "sticker.gif"
            cmd = [sys.executable, "-m", "lottie.exporters.gif", str(json_path), str(gif_path)]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.returncode != 0 or not gif_path.exists() or gif_path.stat().st_size == 0:
                raise Exception(
                    "TGS conversion failed: in-process render unavailable "
                    "and GIF fallback also failed"
                )
            with open(gif_path, 'rb') as f:
                gif_data = f.read()
        logger.warning(f"TGS → GIF fallback ({len(gif_data) / 1024:.1f}KB) — smooth transparency NOT preserved")
        return await convert_video_to_animated_webp(gif_data)

    output = await _run_cpu_bound(
        _encode_animated_webp_under_limit, pil_frames, frame_duration_ms, WA_ANIM_TARGET
    )
    output_size = output.seek(0, 2)
    output.seek(0)
    if output_size > WA_MAX_BYTES:
        raise Exception(f"Encoded output {output_size // 1024}KB exceeds 500KB limit")
    logger.info(f"✓ TGS → animated WebP: {len(pil_frames)} frames, {output_size // 1024}KB")
    return output

async def convert_video_to_animated_webp(video_data: bytes) -> BytesIO:
    with tempfile.TemporaryDirectory() as tmpdir:
        tmppath = Path(tmpdir)
        input_path = tmppath / "input.tmp"
        input_path.write_bytes(video_data)
        frames_dir = tmppath / "frames"
        frames_dir.mkdir()

        try:
            probe = await _run_cpu_bound(ffmpeg.probe, str(input_path))
            video_info = next(s for s in probe['streams'] if s['codec_type'] == 'video')
            format_duration = probe.get('format', {}).get('duration')
            stream_duration = video_info.get('duration')
            nb_frames = video_info.get('nb_frames')

            if format_duration and float(format_duration) > 0.1:
                duration = float(format_duration)
            elif stream_duration and float(stream_duration) > 0.1:
                duration = float(stream_duration)
            elif nb_frames and int(nb_frames) > 0:
                input_fps = parse_frame_rate(video_info.get('r_frame_rate', '30/1'))
                duration = int(nb_frames) / input_fps
            else:
                duration = 0.0

            input_fps = parse_frame_rate(video_info.get('r_frame_rate', '15/1'))
            logger.info(f"Video probe: format_duration={format_duration}s, stream_duration={stream_duration}s, fps={input_fps:.2f}, frames={nb_frames}")
        except Exception as e:
            logger.warning(f"Could not probe input: {e}, using defaults")
            duration = 0.0
            input_fps = 15.0

        target_fps = min(input_fps, 20.0)
        target_fps = max(target_fps, 8.0)
        frame_duration_ms = max(8, int(1000.0 / target_fps))
        max_frames = min(240, int(WA_MAX_ANIM_DURATION_MS / frame_duration_ms))

        # NOTE: we deliberately do NOT pre-halve fps at extraction time based
        # on a frame-count guess. The encoder (_encode_animated_webp_under_limit)
        # now exhausts its full quality ladder at full frame rate before ever
        # dropping frames, so pre-halving here would just bake in choppiness
        # for stickers that would otherwise have fit fine at full smoothness —
        # it's no longer a safe assumption that decimation will be needed.
        estimated_frames = int(duration * target_fps) if duration > 0 else max_frames
        logger.debug(f"Estimated ~{estimated_frames} frames at {target_fps:.1f}fps")

        # Pass ffmpeg_threads from config so users can tune CPU load
        ffmpeg_threads = _cfg("ffmpeg_threads")
        extract_cmd = [
            'ffmpeg', '-y',
            '-c:v', 'libvpx-vp9',
            '-threads', str(ffmpeg_threads),
            '-i', str(input_path),
            '-vf', f'fps={target_fps},format=rgba',
            '-vframes', str(max_frames),
            '-vsync', 'cfr',
            str(frames_dir / 'frame_%04d.png')
        ]
        proc = await asyncio.create_subprocess_exec(
            *extract_cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr_bytes = await proc.communicate()
        if proc.returncode != 0:
            raise Exception(f"ffmpeg frame extraction failed: {stderr_bytes.decode()[:300]}")

        frame_files = sorted(frames_dir.glob("frame_*.png"))
        if not frame_files:
            raise Exception("ffmpeg produced no frames from video")
        logger.info(f"Extracted {len(frame_files)} RGBA PNG frames from video")

        pil_frames = []
        for ff in frame_files:
            try:
                img = Image.open(ff).convert("RGBA")
                canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
                img.thumbnail((512, 512), Image.LANCZOS)
                x = (512 - img.width) // 2
                y = (512 - img.height) // 2
                canvas.paste(img, (x, y), img)
                pil_frames.append(canvas)
            except Exception as fe:
                logger.warning(f"Skipping frame {ff.name}: {fe}")

        if len(pil_frames) == 0:
            raise Exception("No valid frames could be decoded from video")
        if len(pil_frames) == 1:
            logger.warning("Video sticker has only 1 frame; duplicating to satisfy WhatsApp animated requirement")
            pil_frames = pil_frames * 2

        output = await _run_cpu_bound(
            _encode_animated_webp_under_limit, pil_frames, frame_duration_ms, WA_ANIM_TARGET,
        )
        final_size = output.seek(0, 2)
        output.seek(0)

        if final_size > WA_MAX_BYTES:
            raise Exception(f"Encoded output is {final_size // 1024}KB, exceeds 500KB hard limit")
        if final_size > WA_ANIM_TARGET:
            raise Exception(f"Encoder bug: output is {final_size // 1024}KB, exceeds target {WA_ANIM_TARGET // 1024}KB")

        logger.info(
            f"✓ Video → animated WebP: "
            f"{len(pil_frames)} source frames, {final_size / 1024:.1f}KB, "
            f"fps={target_fps:.0f}"
        )
        return output

async def convert_to_whatsapp_animated(file_data: bytes, is_tgs: bool) -> BytesIO:
    if is_tgs:
        logger.info("Converting TGS sticker to animated WebP...")
        return await convert_tgs_to_animated_webp(file_data)
    else:
        logger.info("Converting video sticker to animated WebP...")
        return await convert_video_to_animated_webp(file_data)

def _is_animated_webp_bytes(data: bytes) -> bool:
    if len(data) < 30:
        return False
    if data[0:4] != b'RIFF' or data[8:12] != b'WEBP':
        return False
    if data[12:16] == b'VP8X':
        flags = data[20] & 0xFF
        return bool(flags & 0x02)
    search_end = min(len(data), 512)
    if b'ANIM' in data[12:search_end]:
        return True
    return False

def _count_webp_frames(data: bytes) -> int:
    import struct as _struct
    count = 0
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos+4]
        csz = _struct.unpack_from('<I', data, pos+4)[0]
        # FIX: guard against malformed/corrupt chunk sizes that would loop forever
        if csz > len(data):
            logger.debug(f"_count_webp_frames: oversized chunk at pos={pos} (csz={csz}), stopping")
            break
        if cid == b'ANMF':
            count += 1
        pos += 8 + csz + (csz & 1)
    return count if count > 0 else 1

def split_stickers_by_type(stickers: list):
    static = [s for s in stickers if not (s.is_animated or s.is_video)]
    animated = [s for s in stickers if s.is_animated or s.is_video]
    return static, animated

def classify_sticker_files(files: list) -> tuple[list, list]:
    static_files = []
    animated_files = []
    for f in files:
        ext = f.suffix.lower()
        if ext in ['.webm', '.tgs']:
            animated_files.append(f)
        elif ext == '.webp':
            try:
                with Image.open(f) as img:
                    if getattr(img, "is_animated", False):
                        animated_files.append(f)
                    else:
                        static_files.append(f)
            except Exception:
                static_files.append(f)
        else:
            static_files.append(f)
    return static_files, animated_files

def split_into_chunks(items: list, max_per_chunk: int = 30) -> list:
    return [items[i:i + max_per_chunk] for i in range(0, len(items), max_per_chunk)]

def _build_contents_json(identifier, name, publisher, emoji_map, animated) -> dict:
    stickers_array = [
        {"image_file": fname, "emojis": emojis if emojis else ["😊"]}
        for fname, emojis in emoji_map.items()
    ]
    return {
        "android_play_store_link": "",
        "ios_app_store_link": "",
        "sticker_packs": [{
            "identifier": identifier,
            "name": name,
            "publisher": publisher,
            "tray_image_file": "tray.png",
            "publisher_email": "",
            "publisher_website": "",
            "privacy_policy_website": "",
            "license_agreement_website": "",
            "image_data_version": "1",
            "avoid_cache": False,
            "animated_sticker_pack": animated,
            "stickers": stickers_array,
        }],
    }

class SimpleSticker:
    def __init__(self, file_id, is_animated, is_video, emojis):
        self.file_id = file_id
        self.is_animated = is_animated
        self.is_video = is_video
        if isinstance(emojis, str):
            self.emojis: list[str] = [emojis] if emojis else ["\U0001F600"]
        else:
            self.emojis: list[str] = list(emojis)[:3] if emojis else ["\U0001F600"]

    @property
    def emoji(self) -> str:
        return self.emojis[0] if self.emojis else "\U0001F600"

async def fetch_pack_emoji_map(set_name: str) -> dict:
    try:
        from pyrogram import raw as _raw
        result = await app.invoke(
            _raw.functions.messages.GetStickerSet(
                stickerset=_raw.types.InputStickerSetShortName(short_name=set_name),
                hash=0,
            )
        )
        doc_emoji: dict[int, list[str]] = {}
        for pack in result.packs:
            for doc_id in pack.documents:
                lst = doc_emoji.setdefault(doc_id, [])
                if pack.emoticon not in lst and len(lst) < 3:
                    lst.append(pack.emoticon)

        index_map: dict[int, list[str]] = {}
        for idx, doc in enumerate(result.documents):
            emojis = doc_emoji.get(doc.id)
            if emojis:
                index_map[idx] = emojis

        logger.info(
            f"fetch_pack_emoji_map '{set_name}': "
            f"{sum(len(v) for v in index_map.values())} total emojis across "
            f"{len(index_map)} stickers"
        )
        return index_map
    except Exception as e:
        logger.warning(f"fetch_pack_emoji_map failed for '{set_name}': {e}")
        return {}

# ── File download — FIX: chunked streaming, no full-file RAM buffer ───────────
async def download_file_by_id(bot_token: str, file_id: str) -> bytes:
    """Downloads a Telegram file by file_id using chunked streaming to cap RAM usage."""
    session = await _get_http_session()

    # FIX: use a local var for the URL so bot_token never appears in log output.
    # We pass only the file_path (not the assembled URL) to logger.
    get_url = f"https://api.telegram.org/bot{bot_token}/getFile"
    async with session.get(get_url, params={"file_id": file_id}) as resp:
        data = await resp.json()
        if not data["ok"]:
            raise Exception(data["description"])
        file_path = data["result"]["file_path"]

    _MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024
    download_url = f"https://api.telegram.org/file/bot{bot_token}/{file_path}"

    # FIX: stream in 64 KB chunks instead of resp.read() so we never hold the
    # full file in RAM when content-length is unknown or huge.
    CHUNK = 64 * 1024
    async with session.get(download_url) as resp:
        if resp.status != 200:
            raise Exception(f"Failed to download file: HTTP {resp.status}")
        content_length = resp.headers.get("Content-Length")
        if content_length and int(content_length) > _MAX_DOWNLOAD_BYTES:
            raise Exception(
                f"File too large ({int(content_length) // 1024}KB), refusing download"
            )
        buf = BytesIO()
        async for chunk in resp.content.iter_chunked(CHUNK):
            buf.write(chunk)
            if buf.tell() > _MAX_DOWNLOAD_BYTES:
                raise Exception("File exceeded 20MB limit mid-download, aborting")
        return buf.getvalue()

async def get_sticker_set_via_bot_api(bot_token: str, name: str):
    session = await _get_http_session()
    url = f"https://api.telegram.org/bot{bot_token}/getStickerSet"
    async with session.get(url, params={"name": name}) as resp:
        data = await resp.json()
        if not data["ok"]:
            raise Exception(data["description"])
        return data["result"]

# ── Per-user rate limiting — FIX: prune stale entries to prevent memory leak ─
_user_last_command: dict[int, float] = {}
_RATE_LIMIT_SECONDS = 10
_RATE_LIMIT_PRUNE_INTERVAL = 3600  # prune entries older than 1h

def _prune_rate_limit_dict():
    cutoff = time.monotonic() - _RATE_LIMIT_PRUNE_INTERVAL
    stale = [uid for uid, ts in _user_last_command.items() if ts < cutoff]
    for uid in stale:
        del _user_last_command[uid]
    if stale:
        logger.debug(f"Pruned {len(stale)} stale rate-limit entries")

def _is_rate_limited(user_id: int) -> bool:
    _prune_rate_limit_dict()
    now = time.monotonic()
    last = _user_last_command.get(user_id, 0)
    if now - last < _RATE_LIMIT_SECONDS:
        return True
    _user_last_command[user_id] = now
    return False

# ── Active sticker sessions — FIX: background TTL task ───────────────────────
active_sticker_sessions = {}
_SESSION_TTL = 3600

async def _session_cleanup_loop():
    """Background task: evict sticker sessions that are older than SESSION_TTL."""
    while True:
        await asyncio.sleep(300)
        cutoff = time.monotonic() - _SESSION_TTL
        stale = [k for k, v in active_sticker_sessions.items() if v.get("created_at", 0) < cutoff]
        for k in stale:
            active_sticker_sessions.pop(k, None)
        if stale:
            logger.info(f"Session cleanup: evicted {len(stale)} expired session(s)")

# ── ZIP creation helpers ──────────────────────────────────────────────────────
async def create_simple_zip(
    set_name: str,
    stickers: list,
    convert: bool = True,
    progress_callback=None
) -> tuple[Path, int]:
    logger.info(f"Creating simple ZIP for: {set_name} (convert={convert})")
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)

    import uuid
    work_dir = packs_dir / f"simple_{set_name}_{uuid.uuid4().hex[:8]}"
    work_dir.mkdir(exist_ok=True)

    valid_count = 0
    total = len(stickers)
    stats = {'skipped': 0, 'skipped_reasons': []}

    # FIX: semaphore size now reads from config
    sem = asyncio.Semaphore(_cfg("max_concurrent"))

    async def _process_one(i: int, sticker):
        async with sem:
            try:
                sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)
                verification = await verify_sticker(
                    sticker_data, sticker.is_animated, sticker.is_video, sticker.file_id
                )
                if not verification['valid']:
                    logger.warning(f"❌ Skipping sticker {i}/{total}: {verification['reason']}")
                    return i, None, None, verification['reason']
                for w in verification['warnings']:
                    logger.info(f"⚠️ Sticker {i}: {w}")
                if convert:
                    if sticker.is_animated or sticker.is_video:
                        converted = await convert_to_whatsapp_animated(sticker_data, sticker.is_animated)
                    else:
                        converted = await _run_cpu_bound(_convert_static_bytes_to_webp, sticker_data)
                    converted_bytes = converted.getvalue()
                    if len(converted_bytes) > WA_MAX_BYTES:
                        return i, None, None, f"converted output is {len(converted_bytes) // 1024}KB (>500KB)"
                    return i, converted_bytes, "webp", None
                else:
                    ext = "tgs" if sticker.is_animated else ("webm" if sticker.is_video else "webp")
                    return i, sticker_data, ext, None
            except Exception as e:
                logger.error(f"Error processing sticker {i}: {e}")
                return i, None, None, str(e)

    tasks = [asyncio.create_task(_process_one(i, s)) for i, s in enumerate(stickers, 1)]
    raw_results = []
    completed = 0
    for fut in asyncio.as_completed(tasks):
        idx, data, ext, reason = await fut
        completed += 1
        if progress_callback:
            await progress_callback(completed, total)
        if data is None:
            stats['skipped'] += 1
            stats['skipped_reasons'].append(f"Sticker {idx}: {reason}")
        else:
            raw_results.append((idx, data, ext))

    try:
        if not raw_results:
            raise ValueError("No valid stickers found after verification.")

        for idx, data, ext in sorted(raw_results, key=lambda x: x[0]):
            sticker_filename = f"sticker_{idx:03d}.{ext}"
            sticker_path = work_dir / sticker_filename
            with open(sticker_path, 'wb') as f:
                f.write(data)
            valid_count += 1

        zip_path = packs_dir / f"{set_name}.zip"
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file_path in sorted(work_dir.iterdir()):
                zipf.write(file_path, file_path.name)

        logger.info(f"Simple ZIP created: {zip_path} with {valid_count} stickers (skipped {stats['skipped']} invalid)")
        return zip_path, valid_count
    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)

async def create_wastickers_zip(
    set_name: str,
    tray_bytes: BytesIO,
    stickers: list,
    title: str,
    author: str,
    progress_callback=None,
    return_valid_results: bool = False,
) -> tuple[Path, int, dict] | tuple[list, dict]:
    logger.info("Starting ZIP creation for pack: %s", set_name)
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)

    import uuid
    work_dir = packs_dir / f"pack_{set_name}_{uuid.uuid4().hex[:8]}"
    work_dir.mkdir(exist_ok=True)

    valid_count = 0
    valid_entries = []
    total = len(stickers)
    emoji_map = {}
    stats = {
        'skipped': 0, 'corrupt': 0, 'empty': 0,
        'invalid': 0, 'warnings': 0, 'skipped_reasons': []
    }

    try:
        tray_path = work_dir / "tray.png"
        with open(tray_path, 'wb') as f:
            f.write(tray_bytes.getvalue())
        (work_dir / "author.txt").write_text(author, encoding='utf-8')
        (work_dir / "title.txt").write_text(title, encoding='utf-8')

        should_be_animated = any(s.is_animated or s.is_video for s in stickers)
        pack_type = "Animated" if should_be_animated else "Static"
        logger.info(f"Pack type determined: {pack_type}")

        # FIX: semaphore size reads from config
        sem = asyncio.Semaphore(_cfg("max_concurrent"))

        async def process_one_sticker(i, sticker):
            async with sem:
                try:
                    sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)
                    verification = await verify_sticker(
                        sticker_data, sticker.is_animated, sticker.is_video, sticker.file_id,
                    )

                    if not verification['valid']:
                        reason = verification['reason']
                        reason_lower = reason.lower()
                        kind = 'corrupt' if ('corrupt' in reason_lower or 'invalid' in reason_lower) else \
                               'empty'  if ('empty'  in reason_lower or 'transparent' in reason_lower) else 'invalid'
                        return {'index': i, 'ok': False, 'reason': reason, 'kind': kind, 'warnings': verification['warnings']}

                    if sticker.is_animated or sticker.is_video:
                        logger.info(f"Sticker {i}/{total}: Converting {'TGS' if sticker.is_animated else 'video'} to animated WebP")
                        try:
                            animated_out = await convert_to_whatsapp_animated(sticker_data, sticker.is_animated)
                            converted_bytes = animated_out.getvalue()
                            if not _is_animated_webp_bytes(converted_bytes):
                                logger.warning(f"Sticker {i}: animated conversion produced static output — wrapping as 1-frame animation")
                                try:
                                    img = Image.open(BytesIO(converted_bytes))
                                    fallback_out = await _run_cpu_bound(convert_to_whatsapp_one_frame_animation, img)
                                    converted_bytes = fallback_out.getvalue()
                                except Exception as fe:
                                    return {'index': i, 'ok': False, 'reason': f'conversion produced static and 1-frame fallback failed: {fe}', 'kind': 'invalid', 'warnings': verification['warnings']}
                        except Exception:
                            return {'index': i, 'ok': False, 'reason': f"{'TGS' if sticker.is_animated else 'video'} conversion failed", 'kind': 'invalid', 'warnings': verification['warnings']}

                    elif should_be_animated:
                        logger.info(f"Sticker {i}/{total}: static sticker in animated pack — converting to 1-frame animation")
                        try:
                            img = Image.open(BytesIO(sticker_data))
                            fallback_out = await _run_cpu_bound(convert_to_whatsapp_one_frame_animation, img)
                            converted_bytes = fallback_out.getvalue()
                        except Exception as fe:
                            return {'index': i, 'ok': False, 'reason': f'static-to-animated fallback failed: {fe}', 'kind': 'invalid', 'warnings': verification['warnings']}

                    else:
                        try:
                            converted = await _run_cpu_bound(_convert_static_bytes_to_webp, sticker_data)
                            converted_bytes = converted.getvalue()
                        except Exception:
                            return {'index': i, 'ok': False, 'reason': 'static conversion failed', 'kind': 'invalid', 'warnings': verification['warnings']}

                    if should_be_animated and not _is_animated_webp_bytes(converted_bytes):
                        return {'index': i, 'ok': False, 'reason': 'produced static WebP instead of animated', 'kind': 'invalid', 'warnings': verification['warnings']}

                    if len(converted_bytes) > WA_MAX_BYTES:
                        return {'index': i, 'ok': False, 'reason': f"oversized after conversion ({len(converted_bytes) // 1024}KB)", 'kind': 'invalid', 'warnings': verification['warnings']}

                    is_valid, validation_reason = is_valid_webp_output(converted_bytes, require_animated=should_be_animated)
                    if not is_valid:
                        return {'index': i, 'ok': False, 'reason': validation_reason, 'kind': 'invalid', 'warnings': verification['warnings']}

                    emoji_list = sticker.emojis[:3] if sticker.emojis else ["\U0001F600"]
                    return {'index': i, 'ok': True, 'bytes': converted_bytes, 'file_id': sticker.file_id, 'emoji_list': emoji_list, 'warnings': verification['warnings']}

                except Exception as e:
                    logger.error(f"Error processing sticker {i} in pack {set_name}: {e}")
                    logger.debug(traceback.format_exc())
                    return {'index': i, 'ok': False, 'reason': 'unexpected processing error', 'kind': 'invalid', 'warnings': []}

        tasks = [asyncio.create_task(process_one_sticker(i, sticker)) for i, sticker in enumerate(stickers, 1)]
        results = []
        completed = 0
        for fut in asyncio.as_completed(tasks):
            result = await fut
            results.append(result)
            completed += 1
            if progress_callback:
                await progress_callback(completed, total)

        for result in sorted(results, key=lambda r: r['index']):
            if result['warnings']:
                stats['warnings'] += len(result['warnings'])
                for warning in result['warnings']:
                    logger.info(f"⚠️ Sticker {result['index']}: {warning}")

            if not result['ok']:
                stats['skipped'] += 1
                stats['skipped_reasons'].append(f"Sticker {result['index']}: {result['reason']}")
                kind = result.get('kind', 'invalid')
                if kind == 'corrupt':   stats['corrupt'] += 1
                elif kind == 'empty':   stats['empty'] += 1
                else:                   stats['invalid'] += 1
                logger.warning(f"❌ Skipping sticker {result['index']}/{total}: {result['reason']}")
                continue

            sticker_filename = f"{set_name}_{result['file_id'][-12:]}.webp"
            sticker_path = work_dir / sticker_filename
            with open(sticker_path, 'wb') as f:
                f.write(result['bytes'])

            # Pre-generate the thumbnail alongside the sticker itself, so packs
            # built via this (non-return_valid_results) path also carry one.
            try:
                thumb_bytes = generate_thumbnail_from_webp_bytes(result['bytes'])
                thumbs_dir = work_dir / "thumbnails"
                thumbs_dir.mkdir(exist_ok=True)
                with open(thumbs_dir / f"thumb_{sticker_filename}", 'wb') as f:
                    f.write(thumb_bytes)
            except Exception as e:
                logger.warning(f"Failed to generate thumbnail for {sticker_filename}: {e}")

            emoji_map[sticker_filename] = result['emoji_list']
            valid_entries.append({
                'file_id': result['file_id'],
                'bytes': result['bytes'],
                'emoji_list': result['emoji_list'],
            })
            valid_count += 1

        if return_valid_results:
            return valid_entries, stats

        if valid_count == 0:
            raise ValueError("No valid stickers found in the pack.")
        if valid_count < 3:
            raise ValueError(f"Pack has only {valid_count} stickers. WhatsApp requires minimum 3 stickers per pack.")
        if valid_count > 30:
            logger.info(f"Pack has {valid_count} stickers — the Android app will split into 30-sticker chunks.")

        emojis_json_path = work_dir / "emojis.json"
        with open(emojis_json_path, 'w', encoding='utf-8') as f:
            json.dump(emoji_map, f, ensure_ascii=False, indent=2)

        import uuid
        pack_identifier = uuid.uuid4().hex[:16]
        contents_json = _build_contents_json(pack_identifier, title, author, emoji_map, should_be_animated)
        contents_json_path = work_dir / "contents.json"
        with open(contents_json_path, 'w', encoding='utf-8') as f:
            json.dump(contents_json, f, ensure_ascii=False, indent=2)

        zip_path = packs_dir / f"{set_name}.wasticker"
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file_path in work_dir.iterdir():
                zipf.write(file_path, file_path.name)

        logger.info("ZIP creation finished for pack: %s with %d valid stickers.", set_name, valid_count)
        return zip_path, valid_count, stats

    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)

def _build_wasticker_zip_from_valid_entries(
    set_name, tray_bytes, valid_entries, title, author, animated_sticker_pack
) -> Path:
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)

    filtered_entries = [e for e in valid_entries if len(e['bytes']) <= WA_MAX_BYTES]
    if len(filtered_entries) < 3:
        raise ValueError("Pack has fewer than 3 valid stickers after size enforcement.")

    import uuid
    work_dir = packs_dir / f"pack_{set_name}_{uuid.uuid4().hex[:8]}"
    work_dir.mkdir(exist_ok=True)

    try:
        tray_path = work_dir / "tray.png"
        with open(tray_path, 'wb') as f:
            f.write(tray_bytes.getvalue())
        (work_dir / "author.txt").write_text(author, encoding='utf-8')
        (work_dir / "title.txt").write_text(title, encoding='utf-8')

        emoji_map = {}
        for idx, entry in enumerate(filtered_entries, start=1):
            sticker_filename = f"sticker_{idx}.webp"
            sticker_path = work_dir / sticker_filename
            with open(sticker_path, 'wb') as f:
                f.write(entry['bytes'])
            emoji_map[sticker_filename] = entry['emoji_list']

            # Pre-generate the thumbnail here, at pack-build time on the bot,
            # instead of leaving it for the Android app to decode/resize/
            # re-encode on-device during import. The app just needs to see
            # "thumbnails/thumb_<filename>" inside the zip and copy it into
            # its thumbnails/ subfolder — see WastickerParser.importStickerPack().
            try:
                thumb_bytes = generate_thumbnail_from_webp_bytes(entry['bytes'])
                thumbs_dir = work_dir / "thumbnails"
                thumbs_dir.mkdir(exist_ok=True)
                thumb_path = thumbs_dir / f"thumb_{sticker_filename}"
                with open(thumb_path, 'wb') as f:
                    f.write(thumb_bytes)
            except Exception as e:
                logger.warning(f"Failed to generate thumbnail for {sticker_filename}: {e}")

        emojis_json_path = work_dir / "emojis.json"
        with open(emojis_json_path, 'w', encoding='utf-8') as f:
            json.dump(emoji_map, f, ensure_ascii=False, indent=2)

        pack_identifier = uuid.uuid4().hex[:16]
        contents_json = _build_contents_json(pack_identifier, title, author, emoji_map, animated_sticker_pack)
        contents_json_path = work_dir / "contents.json"
        with open(contents_json_path, 'w', encoding='utf-8') as f:
            json.dump(contents_json, f, ensure_ascii=False, indent=2)

        zip_path = packs_dir / f"{set_name}.wasticker"
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file_path in work_dir.iterdir():
                if file_path.is_dir():
                    # Write directory contents with relative paths (e.g. thumbnails/thumb_x.webp)
                    for sub_file in file_path.iterdir():
                        zipf.write(sub_file, f"{file_path.name}/{sub_file.name}")
                else:
                    zipf.write(file_path, file_path.name)
        return zip_path
    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)

# ── Command handlers ──────────────────────────────────────────────────────────

@app.on_message(filters.command("auth") & filters.private)
async def authorize_chat(client: Client, message: Message):
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can use this command.")
        return
    if len(message.command) < 2:
        await message.reply_text("Usage: `/auth <chat_id>`")
        return
    try:
        chat_id = int(message.command[1])
        AUTHORIZED_CHATS.add(chat_id)
        save_authorized_chats()
        await message.reply_text(f"✅ Chat `{chat_id}` authorized.")
    except ValueError:
        await message.reply_text("❌ Invalid chat ID.")

@app.on_message(filters.command("deauth") & filters.private)
async def deauthorize_chat(client: Client, message: Message):
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can use this command.")
        return
    if len(message.command) < 2:
        await message.reply_text("Usage: `/deauth <chat_id>`")
        return
    try:
        chat_id = int(message.command[1])
        AUTHORIZED_CHATS.discard(chat_id)
        save_authorized_chats()
        await message.reply_text(f"✅ Chat `{chat_id}` deauthorized.")
    except ValueError:
        await message.reply_text("❌ Invalid chat ID.")

@app.on_message(filters.command("listauth") & filters.private)
async def list_authorized_chats(client: Client, message: Message):
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can use this command.")
        return
    if not AUTHORIZED_CHATS:
        await message.reply_text("No authorized chats.")
    else:
        chats_list = "\n".join(str(c) for c in sorted(AUTHORIZED_CHATS))
        await message.reply_text(f"Authorized chats:\n`{chats_list}`")

# ── /settings command ─────────────────────────────────────────────────────────
_SETTINGS_META = {
    "max_concurrent": {
        "label": "Parallel",
        "desc": "Downloads/Conversions",
        "min": 1, "max": 20, "step": 1, "unit": ""
    },
    "process_pool_workers": {
        "label": "TGS Workers",
        "desc": "Render Processes",
        "min": 1, "max": 8, "step": 1, "unit": ""
    },
    "ffmpeg_threads": {
        "label": "FFmpeg",
        "desc": "Decode Threads",
        "min": 1, "max": 16, "step": 1, "unit": ""
    },
    "tgs_render_timeout": {
        "label": "Timeout",
        "desc": "TGS Render",
        "min": 10, "max": 300, "step": 10, "unit": "s"
    },
}

def _settings_keyboard() -> InlineKeyboardMarkup:
    rows = []
    for key, meta in _SETTINGS_META.items():
        val = _cfg(key)
        unit = meta["unit"]
        rows.append([
            InlineKeyboardButton("➖", callback_data=f"cfg_dec_{key}"),
            InlineKeyboardButton(f"{meta['label']}: {val}{unit}",
                                callback_data=f"cfg_noop_{key}"),
            InlineKeyboardButton("➕", callback_data=f"cfg_inc_{key}")
        ])
    rows.append([InlineKeyboardButton("Reset to defaults", callback_data="cfg_reset")])
    return InlineKeyboardMarkup(rows)

def _settings_text() -> str:
    lines = ["**Resource settings**\n"]
    for key, meta in _SETTINGS_META.items():
        val = _cfg(key)
        default = CONFIG_DEFAULTS[key]
        unit = meta["unit"]
        marker = "" if val == default else " ✏️"
        lines.append(f"• {meta['label']}: `{val}{unit}`{marker}")
    lines.append("\nUse the buttons below to tune up or down.")
    lines.append("Higher values = faster conversion but more CPU/RAM.")
    return "\n".join(lines)

@app.on_message(filters.command("settings") & filters.private)
async def settings_command(client: Client, message: Message):
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can adjust settings.")
        return
    await message.reply_text(_settings_text(), reply_markup=_settings_keyboard())

@app.on_callback_query(filters.regex(r"^cfg_(inc|dec|reset|noop)_?(.*)$"))
async def settings_callback(client: Client, callback_query):
    if callback_query.from_user.id != OWNER_ID:
        await callback_query.answer("Not authorised.", show_alert=True)
        return

    action = callback_query.matches[0].group(1)
    key    = callback_query.matches[0].group(2)

    if action == "noop":
        await callback_query.answer()
        return

    if action == "reset":
        CONFIG.update(CONFIG_DEFAULTS)
        _save_config(CONFIG)
        _rebuild_process_pool(_cfg("process_pool_workers"))
        _rebuild_cpu_pool(_cfg("max_concurrent"))
        await callback_query.answer("Reset to defaults.")
        try:
            await callback_query.message.edit_text(_settings_text(), reply_markup=_settings_keyboard())
        except Exception:
            pass
        return

    if key not in _SETTINGS_META:
        await callback_query.answer("Unknown setting.", show_alert=True)
        return

    meta = _SETTINGS_META[key]
    current = _cfg(key)
    step = meta["step"]

    if action == "inc":
        new_val = min(current + step, meta["max"])
    else:
        new_val = max(current - step, meta["min"])

    if new_val == current:
        await callback_query.answer(f"Already at {'maximum' if action == 'inc' else 'minimum'}.")
        return

    CONFIG[key] = new_val
    _save_config(CONFIG)

    if key == "process_pool_workers":
        _rebuild_process_pool(new_val)
    if key == "max_concurrent":
        _rebuild_cpu_pool(new_val)

    await callback_query.answer(f"{meta['label']} → {new_val}{meta['unit']}")
    try:
        await callback_query.message.edit_text(_settings_text(), reply_markup=_settings_keyboard())
    except Exception:
        pass

@app.on_message(filters.command("start") & (filters.group | filters.private))
async def start_command(client: Client, message: Message):
    instructions = """
🤖 **Telegram to WhatsApp Sticker Converter**

**Commands:**
• `/wast` - Reply to any sticker to convert its entire pack to WhatsApp format
• `/wast CustomName` - Convert with a custom pack name
• `/wast -z` - Download and ZIP all stickers (converted to WebP)
• `/wast -z -c` - Download and ZIP raw stickers (no conversion)
• `/loadsticker` - Reply to a sticker to import it to WhatsApp
• `/loadsticker PackName` - Import to a named pack
• `/converts` - Reply to a sticker to get the raw converted .webp file
• `/local` - Process sticker files from a local 'stickers' folder
• `/upload` - Upload all .wasticker files from current directory
• `/help` - How to import stickers to WhatsApp
• `/settings` - Tune CPU/RAM usage (owner only)
• `/start` - Show this help message
"""
    await message.reply_text(instructions)

@app.on_message(filters.command("help") & (filters.group | filters.private))
async def help_command(client: Client, message: Message):
    help_text = """
**How to import stickers to WhatsApp**

Download the app using the button below, tap the .wasticker file, then tap "Import to WhatsApp".

If you're on iOS, tap the .wasticker file and it should prompt you directly.
"""
    keyboard = InlineKeyboardMarkup([[
        InlineKeyboardButton(
            "Download WA Sticker Maker (Android)",
            url="https://play.google.com/store/apps/details?id=com.marsvard.stickermakerforwhatsapp"
        )
    ]])
    await message.reply_text(help_text, reply_markup=keyboard)

@app.on_message(filters.command("loadsticker") & (filters.group | filters.private))
async def loadsticker_command(client: Client, message: Message):
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return
    if _is_rate_limited(message.from_user.id):
        await message.reply_text(f"⏳ Please wait {_RATE_LIMIT_SECONDS}s between commands.")
        return

    replied = message.reply_to_message
    if not replied or not replied.sticker:
        await message.reply_text(
            "❌ Please reply to a sticker with `/loadsticker` to import it to WhatsApp.\n\n"
            "**Usage:**\n"
            "• `/loadsticker` — import to default pack (My Stickers)\n"
            "• `/loadsticker MyPack` — import to a named pack"
        )
        return

    user_id = message.from_user.id
    sticker = replied.sticker
    is_animated = sticker.is_animated or sticker.is_video
    author_name = message.from_user.first_name or "Telegram User"
    args = message.command[1:] if len(message.command) > 1 else []
    pack_title = " ".join(args) if args else "My Stickers"
    msg = await message.reply_text("📥 Downloading sticker...")

    try:
        sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)
        if not sticker_data or len(sticker_data) == 0:
            await msg.edit_text("❌ Failed to download sticker.")
            return

        await msg.edit_text("🔄 Converting to WhatsApp format...")
        if is_animated:
            converted = await convert_to_whatsapp_animated(sticker_data, sticker.is_animated)
        else:
            converted = await _run_cpu_bound(_convert_static_bytes_to_webp, sticker_data)
        converted_bytes = converted.getvalue()

        tray_bytes = await _run_cpu_bound(optimize_tray_icon, converted_bytes, False)
        await msg.edit_text("📦 Packaging .wasticker file...")

        thumb_bytes = await _run_cpu_bound(generate_thumbnail_from_webp_bytes, converted_bytes)

        wasticker_bio = BytesIO()
        with zipfile.ZipFile(wasticker_bio, 'w', zipfile.ZIP_DEFLATED) as zipf:
            zipf.writestr('title.txt', pack_title)
            zipf.writestr('author.txt', author_name)
            zipf.writestr('tray.png', tray_bytes.getvalue())
            zipf.writestr('sticker_001.webp', converted_bytes)
            zipf.writestr('thumb_sticker_001.webp', thumb_bytes)
        wasticker_bio.seek(0)

        wasticker_name = f"{sanitize_filename(pack_title)}.idwasticker"
        await msg.edit_text("Sending file...")
        caption = f"**{pack_title}**\nsticker ready to import"
        await client.send_document(
            chat_id=message.chat.id,
            document=wasticker_bio,
            file_name=wasticker_name,
            caption=caption,
            disable_notification=True
        )
        await msg.delete()

    except Exception as e:
        logger.error(f"Loadsticker failed for user {user_id}: {e}")
        logger.error(traceback.format_exc())
        await msg.edit_text(f"Failed to load sticker: `{str(e)}`")

@app.on_message(filters.command("converts") & (filters.group | filters.private))
async def converts_command(client: Client, message: Message):
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return
    if _is_rate_limited(message.from_user.id):
        await message.reply_text(f"⏳ Please wait {_RATE_LIMIT_SECONDS}s between commands.")
        return

    replied = message.reply_to_message
    if not replied or not replied.sticker:
        await message.reply_text("❌ Please reply to a sticker with `/converts`.")
        return

    user_id = message.from_user.id
    sticker = replied.sticker
    is_animated = sticker.is_animated or sticker.is_video
    msg = await message.reply_text("📥 Downloading sticker for conversion...")

    try:
        sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)
        if not sticker_data or len(sticker_data) == 0:
            await msg.edit_text("❌ Failed to download sticker.")
            return

        await msg.edit_text("🔄 Converting sticker to WebP format...")
        if is_animated:
            converted = await convert_to_whatsapp_animated(sticker_data, sticker.is_animated)
        else:
            converted = await _run_cpu_bound(_convert_static_bytes_to_webp, sticker_data)
        converted_bytes = converted.getvalue()

        await msg.edit_text("Sending file...")
        file_bio = BytesIO(converted_bytes)
        file_bio.name = "converted_sticker.webp"
        await client.send_document(
            chat_id=message.chat.id,
            document=file_bio,
            caption="Here is your converted sticker file.",
            disable_notification=True
        )
        await msg.delete()

    except Exception as e:
        logger.error(f"Converts failed for user {user_id}: {e}")
        logger.error(traceback.format_exc())
        await msg.edit_text(f"Failed to convert sticker: `{str(e)}`")

async def process_stickers(
    client: Client,
    msg: Message,
    message_text: str,
    target_chat: int,
    set_title: str,
    stickers: list,
    use_simple_zip: bool,
    skip_conversion: bool,
    author_name: str,
    pack_name: str,
    send_to_private: bool,
    from_user_id: int
):
    try:
        _pack_start_time = time.monotonic()
        set_name_sanitized = sanitize_filename(set_title)
        if not stickers:
            await msg.edit_text("No stickers provided.")
            return

        first_sticker = stickers[0]
        tray_data = await download_file_by_id(BOT_TOKEN, first_sticker.file_id)
        optimized_tray_bytes = await _run_cpu_bound(
            optimize_tray_icon, tray_data, first_sticker.is_animated or first_sticker.is_video
        )

        if use_simple_zip:
            await msg.edit_text(f"Creating {'raw' if skip_conversion else 'converted'} ZIP with {len(stickers)} stickers...")

            _last_edit_simple = [0.0]
            async def update_progress_simple(current, total):
                now = time.monotonic()
                if current != total and now - _last_edit_simple[0] < 4.0:
                    return
                _last_edit_simple[0] = now
                bar = "█" * int(current / total * 20) + "░" * (20 - int(current / total * 20))
                action = "Downloading" if skip_conversion else "Converting"
                try:
                    await msg.edit_text(f"{action} stickers\n\n{bar} {int(current/total*100)}%\nSticker {current}/{total}")
                except Exception:
                    pass

            zip_path, valid_count = await create_simple_zip(
                set_name_sanitized, stickers, convert=not skip_conversion,
                progress_callback=update_progress_simple
            )
            await msg.edit_text("Uploading ZIP file...")
            mode_desc = "Raw stickers" if skip_conversion else "Converted stickers"
            elapsed = _format_elapsed(time.monotonic() - _pack_start_time)
            await client.send_document(
                chat_id=target_chat,
                document=str(zip_path),
                file_name=zip_path.name,
                caption=f"{mode_desc}: {valid_count} files\n{set_title}\nConverted in {elapsed}",
                disable_notification=True
            )
            try:
                zip_path.unlink(missing_ok=True)
            except Exception as ce:
                logger.warning(f"Cleanup error: {ce}")
            try:
                await msg.reply_text(f"Successfully zipped {valid_count} stickers!")
            except Exception:
                pass
            return

        # FIX: removed the vestigial types_to_process loop — process pack directly
        has_animated = any(s.is_animated or s.is_video for s in stickers)
        type_name = "Animated" if has_animated else "Static"
        await msg.edit_text(f"Processing {len(stickers)} stickers as {type_name} pack...")

        _last_edit_pack = [0.0]
        async def update_progress_pack(current, total):
            now = time.monotonic()
            if current != total and now - _last_edit_pack[0] < 4.0:
                return
            _last_edit_pack[0] = now
            bar = "█" * int(current / total * 20) + "░" * (20 - int(current / total * 20))
            try:
                await msg.edit_text(
                    f"Processing **{type_name}** stickers\n\n{bar} {int(current/total*100)}%\nSticker {current}/{total}"
                )
            except Exception:
                pass

        type_letter = type_name[0].lower()
        prep_name = f"{set_name_sanitized}_{type_letter}_prep"
        valid_entries, stats = await create_wastickers_zip(
            prep_name, optimized_tray_bytes, stickers, set_title,
            author=author_name, progress_callback=update_progress_pack,
            return_valid_results=True,
        )

        valid_count_total = len(valid_entries)
        if valid_count_total == 0:
            raise ValueError("No valid stickers found in the pack.")
        if valid_count_total < 3:
            logger.warning(f"Pack has only {valid_count_total} valid sticker(s) — WhatsApp requires ≥3. Skipping.")
            await msg.edit_text(f"❌ Only {valid_count_total} valid sticker(s) — WhatsApp requires at least 3.")
            return

        internal_name = f"{set_name_sanitized}_{type_letter}"
        zip_path = await _run_cpu_bound(
            _build_wasticker_zip_from_valid_entries,
            internal_name, optimized_tray_bytes, valid_entries, set_title, author_name, has_animated,
        )

        caption = f"{type_name} Stickers: {valid_count_total} stickers"
        if stats['skipped'] > 0:
            caption += f"\nSkipped {stats['skipped']} invalid"
            if stats.get('skipped_reasons'):
                reason_counts = {}
                for entry in stats['skipped_reasons']:
                    reason = entry.split(': ', 1)[1] if ': ' in entry else entry
                    reason_counts[reason] = reason_counts.get(reason, 0) + 1
                top_reasons = sorted(reason_counts.items(), key=lambda x: x[1], reverse=True)[:3]
                if top_reasons:
                    caption += "\nTop skip reasons:"
                    for reason, count in top_reasons:
                        caption += f"\n- {count}x {reason}"
        if valid_count_total > 30:
            caption += f"\n\n{valid_count_total} stickers"

        elapsed = _format_elapsed(time.monotonic() - _pack_start_time)
        caption += f"\nConverted in {elapsed}"

        try:
            await msg.edit_text(f"Uploading {type_name} pack ({valid_count_total} stickers)...")
        except Exception:
            pass

        while True:
            try:
                await client.send_document(
                    chat_id=target_chat,
                    document=str(zip_path),
                    file_name=zip_path.name,
                    caption=caption,
                    disable_notification=True
                )
                break
            except FloodWait as fw:
                logger.warning(f"FloodWait on send_document: waiting {fw.value}s")
                await asyncio.sleep(fw.value)

        try:
            zip_path.unlink(missing_ok=True)
        except Exception as ce:
            logger.warning(f"Cleanup error: {ce}")

        try:
            sent_success_msg = await msg.reply_text(f"Successfully sent {valid_count_total} stickers!")

            async def _delete_after_delay(target_msg, delay_secs: float = 10.0):
                try:
                    await asyncio.sleep(delay_secs)
                    await target_msg.delete()
                except Exception as de:
                    logger.debug(f"Could not auto-delete success message: {de}")

            # Fire-and-forget: don't block the handler (or the semaphore slot
            # it's running under) for 10s just to clean up a status message.
            asyncio.create_task(_delete_after_delay(sent_success_msg))
        except Exception:
            pass

    except FloodWait as e:
        logger.warning(f"FloodWait for pack {pack_name}: waiting {e.value}s")
        await asyncio.sleep(e.value)
        try:
            await msg.edit_text("⏳ Rate limit hit. Please try again in a moment.")
        except Exception:
            pass
    except Exception as e:
        logger.error(f"Pack conversion failed for pack {pack_name}: {e}")
        logger.error(traceback.format_exc())
        error_message = str(e).lower()
        if "bot was kicked" in error_message:
            await msg.edit_text("I've been kicked from the group. Please re-add me!")
        elif "not enough rights" in error_message or "chat write forbidden" in error_message:
            await msg.edit_text("I need permissions to send messages and files in this group!")
        elif "chat not found" in error_message:
            await msg.edit_text("I cannot access this group. Please ensure I'm added and have permissions!")
        else:
            await msg.edit_text(f"An unexpected error occurred: `{str(e)}`")

@app.on_message(filters.command("wast") & (filters.group | filters.private))
async def convert_pack(client: Client, message: Message):
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("This chat is not authorized. Contact the bot owner to authorize it with `/auth <chat_id>` in private.")
        return
    if _is_rate_limited(message.from_user.id):
        await message.reply_text(f"⏳ Please wait {_RATE_LIMIT_SECONDS}s between commands.")
        return

    args = message.command[1:] if len(message.command) > 1 else []
    flags = [a for a in args if a.startswith('-')]
    name_parts = [a for a in args if not a.startswith('-')]

    use_simple_zip  = '-z' in flags
    skip_conversion = '-c' in flags
    is_session_mode = '-s' in flags
    custom_name = " ".join(name_parts) if name_parts else None

    if skip_conversion and not use_simple_zip:
        await message.reply_text("Flag `-c` (no conversion) requires `-z` flag.\nUsage: `/wast -z -c`")
        return

    if is_session_mode:
        session_key = (message.chat.id, message.from_user.id)
        keyboard = InlineKeyboardMarkup([[InlineKeyboardButton("Make Sticker Pack", callback_data="make_pack")]])
        mode_text = " (Raw ZIP)" if (use_simple_zip and skip_conversion) else " (Converted ZIP)" if use_simple_zip else ""
        name_text = f" as **{custom_name}**" if custom_name else ""
        msg = await message.reply_text(
            f"Send stickers one by one to create a pack{name_text}{mode_text}.\n\nStickers added: 0",
            reply_markup=keyboard
        )
        active_sticker_sessions[session_key] = {
            "message_id": msg.id,
            "chat_id": message.chat.id,
            "stickers": [],
            "use_simple_zip": use_simple_zip,
            "skip_conversion": skip_conversion,
            "custom_name": custom_name,
            "source_message_text": message.text,
            "from_user": message.from_user,
            "created_at": time.monotonic(),
        }
        return

    replied = message.reply_to_message
    if not replied or not replied.sticker:
        await message.reply_text("❌ Please reply to a sticker with `/wast` or use `/wast -s` to select multiple stickers.")
        return
    if not replied.sticker.set_name:
        await message.reply_text("❌ This sticker doesn't belong to a pack.")
        return

    pack_name = replied.sticker.set_name
    mode_text = " (Raw ZIP)" if (use_simple_zip and skip_conversion) else " (Converted ZIP)" if use_simple_zip else ""
    msg = await message.reply_text(f"📦 Processing pack: **{pack_name}**{mode_text}...")

    try:
        sticker_set = await get_sticker_set_via_bot_api(BOT_TOKEN, pack_name)
        set_title = custom_name if custom_name else sticker_set["title"]
        emoji_index_map = await fetch_pack_emoji_map(pack_name)

        seen_ids = set()
        stickers = []
        for idx, s in enumerate(sticker_set["stickers"]):
            fid = s["file_id"]
            if fid not in seen_ids:
                seen_ids.add(fid)
                emojis = emoji_index_map.get(idx) or [s.get("emoji", "\U0001F600")]
                stickers.append(SimpleSticker(fid, s.get("is_animated", False), s.get("is_video", False), emojis))

        if len(seen_ids) < len(sticker_set["stickers"]):
            logger.warning(f"Removed {len(sticker_set['stickers']) - len(seen_ids)} duplicate stickers")

        send_to_private = "private" in message.text.lower() if message.text else False
        target_chat = message.from_user.id if send_to_private and message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] else message.chat.id

        await process_stickers(
            client=client, msg=msg, message_text=message.text,
            target_chat=target_chat, set_title=set_title, stickers=stickers,
            use_simple_zip=use_simple_zip, skip_conversion=skip_conversion,
            author_name=message.from_user.first_name or "Telegram User",
            pack_name=pack_name, send_to_private=send_to_private,
            from_user_id=message.from_user.id
        )
    finally:
        try:
            await msg.delete()
        except Exception:
            pass

@app.on_message(filters.sticker & (filters.group | filters.private))
async def handle_individual_stickers(client: Client, message: Message):
    session_key = (message.chat.id, message.from_user.id)
    if session_key not in active_sticker_sessions:
        return
    if time.monotonic() - active_sticker_sessions[session_key].get("created_at", 0) > _SESSION_TTL:
        active_sticker_sessions.pop(session_key, None)
        return

    session = active_sticker_sessions[session_key]
    session["stickers"].append(SimpleSticker(
        message.sticker.file_id,
        message.sticker.is_animated,
        message.sticker.is_video,
        [message.sticker.emoji or "\U0001F600"]
    ))
    keyboard = InlineKeyboardMarkup([[InlineKeyboardButton("Make Sticker Pack", callback_data="make_pack")]])
    try:
        await client.edit_message_text(
            chat_id=session["chat_id"],
            message_id=session["message_id"],
            text=f"⏳ Send stickers one by one.\n\nStickers added: {len(session['stickers'])}",
            reply_markup=keyboard
        )
    except Exception:
        pass

@app.on_callback_query(filters.regex("^make_pack$"))
async def make_pack_callback(client: Client, callback_query):
    session_key = (callback_query.message.chat.id, callback_query.from_user.id)
    if session_key not in active_sticker_sessions:
        await callback_query.answer("No active session or you didn't start it.", show_alert=True)
        return

    session = active_sticker_sessions.pop(session_key)
    stickers_list = session["stickers"]
    if not stickers_list:
        await callback_query.answer("No stickers added!", show_alert=True)
        return

    await callback_query.answer("Processing stickers...")
    msg = callback_query.message
    await msg.edit_text("📦 Processing your custom sticker pack...", reply_markup=None)

    message_text = session["source_message_text"]
    from_user = session["from_user"]
    send_to_private = message_text and "private" in message_text.lower()
    target_chat = from_user.id if send_to_private and msg.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] else msg.chat.id
    set_title = session["custom_name"] or "Random Stickers"

    try:
        await process_stickers(
            client=client, msg=msg, message_text=message_text,
            target_chat=target_chat, set_title=set_title,
            stickers=stickers_list,
            use_simple_zip=session["use_simple_zip"],
            skip_conversion=session["skip_conversion"],
            author_name=from_user.first_name or "Telegram User",
            pack_name=set_title, send_to_private=send_to_private,
            from_user_id=from_user.id
        )
    finally:
        try:
            await msg.delete()
        except Exception:
            pass

@app.on_message(filters.command("upload") & (filters.group | filters.private))
async def upload_wasticker_files(client: Client, message: Message):
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return

    packs_dir = Path("wasticker_packs")
    if not packs_dir.exists():
        await message.reply_text("❌ No wasticker_packs folder found. Process some stickers first with `/wast`.")
        return

    wasticker_files = list(packs_dir.glob("*.wasticker"))
    if not wasticker_files:
        await message.reply_text("❌ No .wasticker files found in the wasticker_packs folder.")
        return

    msg = await message.reply_text(f"📤 Found {len(wasticker_files)} .wasticker file(s). Uploading...")
    send_to_private = "private" in message.text.lower()
    target_chat = message.from_user.id if send_to_private and message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] else message.chat.id

    uploaded_count = 0
    for i, wasticker_file in enumerate(wasticker_files, 1):
        try:
            await msg.edit_text(f"📤 Uploading {i}/{len(wasticker_files)}: {wasticker_file.name}")
            await client.send_document(
                chat_id=target_chat,
                document=str(wasticker_file),
                file_name=wasticker_file.name,
                caption=f"Sticker Pack: {wasticker_file.stem}",
                disable_notification=True
            )
            uploaded_count += 1
            await asyncio.sleep(1)
        except Exception as e:
            logger.error(f"Failed to upload {wasticker_file.name}: {e}")
            continue

    cleanup_success = False
    try:
        if packs_dir.exists():
            shutil.rmtree(packs_dir, ignore_errors=True)
            cleanup_success = True
    except Exception as ce:
        logger.warning(f"Cleanup error: {ce}")

    success_msg = f"✅ Uploaded {uploaded_count}/{len(wasticker_files)} sticker packs!"
    if cleanup_success:
        success_msg += "\n🗑️ Cleaned up temporary files."
    await message.reply_text(success_msg)
    try:
        await msg.delete()
    except Exception:
        pass

@app.on_message(filters.command("local") & (filters.group | filters.private))
async def process_local_folder(client: Client, message: Message):
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return

    stickers_dir = Path("stickers")
    if not stickers_dir.exists():
        await message.reply_text("❌ No 'stickers' folder found. Create one and put .webm/.tgs/.png/.jpg/.webp files there.")
        return

    sticker_files = []
    for ext in ['*.webm', '*.tgs', '*.png', '*.jpg', '*.jpeg', '*.webp']:
        sticker_files.extend(stickers_dir.glob(ext))

    if not sticker_files:
        await message.reply_text("❌ No sticker files found. Supported: .webm .tgs .png .jpg .jpeg .webp")
        return

    msg = await message.reply_text(f"📦 Processing {len(sticker_files)} local sticker files...")

    try:
        static_files, animated_files = classify_sticker_files(sticker_files)
        types_to_process = []
        if static_files:
            types_to_process.append(("Static", static_files))
        if animated_files:
            types_to_process.append(("Animated", animated_files))

        total_processed = 0
        zip_paths = []
        _local_sem = asyncio.Semaphore(_cfg("max_concurrent"))

        for type_name, files in types_to_process:
            chunks = split_into_chunks(files, 30)
            num_parts = len(chunks)
            for part_num, chunk in enumerate(chunks, 1):
                type_letter = type_name[0].lower()
                processing_dir = Path(f"processed_{type_letter}_{part_num}")
                processing_dir.mkdir(exist_ok=True)
                processed_count = 0

                async def _convert_local_file(sf, _sem=_local_sem):
                    async with _sem:
                        try:
                            with open(sf, 'rb') as fh:
                                fdata = fh.read()
                            is_tgs = sf.suffix.lower() == '.tgs'
                            if sf in animated_files:
                                conv = await convert_to_whatsapp_animated(fdata, is_tgs)
                            else:
                                conv = await _run_cpu_bound(_convert_static_bytes_to_webp, fdata)
                            return sf, conv.getvalue(), None
                        except Exception as exc:
                            logger.error(f"Error processing {sf.name}: {exc}")
                            return sf, None, str(exc)

                _local_tasks = [asyncio.create_task(_convert_local_file(sf)) for sf in chunk]
                _local_done = 0
                for _fut in asyncio.as_completed(_local_tasks):
                    sf, data, _err = await _fut
                    _local_done += 1
                    try:
                        part_suffix = f" (Part {part_num}/{num_parts})" if num_parts > 1 else ""
                        await msg.edit_text(f"📦 Processing {type_name}{part_suffix}: {_local_done}/{len(chunk)}")
                    except Exception:
                        pass
                    if data is not None:
                        output_name = f"{sf.stem}_whatsapp.webp"
                        with open(processing_dir / output_name, 'wb') as fh:
                            fh.write(data)
                        processed_count += 1

                if processed_count == 0:
                    shutil.rmtree(processing_dir, ignore_errors=True)
                    continue

                tray_path = processing_dir / "tray.png"
                try:
                    first_file = chunk[0]
                    with open(first_file, 'rb') as f:
                        tray_data = f.read()
                    is_animated = first_file in animated_files
                    optimized_tray = await _run_cpu_bound(optimize_tray_icon, tray_data, is_animated)
                    with open(tray_path, 'wb') as f:
                        f.write(optimized_tray.getvalue())
                except Exception as e:
                    logger.warning(f"Could not create tray icon: {e}")

                part_title = f"Converted {type_name}" + (f" Part {part_num}" if num_parts > 1 else "")
                zip_name = f"whatsapp_{type_name.lower()}_{part_num}.wastickers"
                zip_path = Path(zip_name)
                with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
                    if tray_path.exists():
                        zipf.write(tray_path, 'tray.png')
                    author_name = message.from_user.first_name or "Telegram User"
                    zipf.writestr('author.txt', author_name.encode('utf-8'))
                    zipf.writestr('title.txt', part_title.encode('utf-8'))
                    for webp_file in processing_dir.glob("*.webp"):
                        zipf.write(webp_file, f"sticker_{webp_file.stem[-8:]}.webp")

                zip_paths.append(zip_name)
                total_processed += processed_count
                shutil.rmtree(processing_dir, ignore_errors=True)

        if total_processed > 0:
            await msg.edit_text(f"✅ Processed {total_processed} stickers! ZIP files: {', '.join(zip_paths)}")
        else:
            await msg.edit_text("❌ Failed to process any stickers.")

    except Exception as e:
        logger.error(f"Local processing failed: {e}")
        await msg.edit_text(f"❌ Processing failed: {str(e)}")
    finally:
        try:
            await msg.delete()
        except Exception:
            pass

@app.on_message(filters.document & (filters.group | filters.private))
async def process_zip_upload(client: Client, message: Message):
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return
    if not message.document.file_name or not message.document.file_name.lower().endswith('.zip'):
        return

    msg = await message.reply_text("📥 Downloading ZIP file...")

    try:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            zip_path = await message.download(file_name=str(tmp_path / message.document.file_name))

            await msg.edit_text("📦 Extracting ZIP file...")
            extract_dir = tmp_path / "extracted"
            extract_dir.mkdir()

            extract_dir_resolved = extract_dir.resolve()
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                for member in zip_ref.infolist():
                    member_path = (extract_dir_resolved / member.filename).resolve()
                    if not str(member_path).startswith(str(extract_dir_resolved) + os.sep):
                        logger.warning(f"Zip Slip blocked: '{member.filename}'")
                        continue
                    zip_ref.extract(member, extract_dir)

            sticker_files = []
            for ext in ['*.webm', '*.tgs', '*.png', '*.jpg', '*.jpeg', '*.webp']:
                sticker_files.extend(extract_dir.rglob(ext))

            if not sticker_files:
                await msg.edit_text("❌ No supported sticker files found in the ZIP.")
                return

            static_files, animated_files = classify_sticker_files(sticker_files)
            types_to_process = []
            if static_files:
                types_to_process.append(("Static", static_files))
            if animated_files:
                types_to_process.append(("Animated", animated_files))

            pack_name_base = message.document.file_name[:-4]
            author_name = message.from_user.first_name or "Telegram User"
            total_packs_sent = 0
            send_to_private = bool(message.caption and "private" in message.caption.lower())
            target_chat = (
                message.from_user.id if send_to_private and message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP]
                else message.chat.id
            )
            _zip_sem = asyncio.Semaphore(_cfg("max_concurrent"))

            for type_name, files in types_to_process:
                chunks = split_into_chunks(files, 30)
                num_parts = len(chunks)

                for part_num, chunk in enumerate(chunks, 1):
                    type_letter = type_name[0].lower()
                    part_title = pack_name_base + (f" - Part {part_num}" if num_parts > 1 else "")
                    processing_dir = tmp_path / f"processed_{type_letter}_{part_num}"
                    processing_dir.mkdir()
                    processed_count = 0

                    async def _convert_zip_file(sf, _sem=_zip_sem):
                        async with _sem:
                            try:
                                with open(sf, 'rb') as fh:
                                    fdata = fh.read()
                                is_tgs = sf.suffix.lower() == '.tgs'
                                if sf in animated_files:
                                    conv = await convert_to_whatsapp_animated(fdata, is_tgs)
                                else:
                                    conv = await _run_cpu_bound(_convert_static_bytes_to_webp, fdata)
                                return sf, conv.getvalue(), None
                            except Exception as exc:
                                logger.error(f"Error processing {sf.name}: {exc}")
                                return sf, None, str(exc)

                    _zip_tasks = [asyncio.create_task(_convert_zip_file(sf)) for sf in chunk]
                    _zip_done = 0
                    for _fut in asyncio.as_completed(_zip_tasks):
                        sf, data, _err = await _fut
                        _zip_done += 1
                        try:
                            part_suffix = f" (Part {part_num}/{num_parts})" if num_parts > 1 else ""
                            await msg.edit_text(f"📦 Processing {type_name}{part_suffix}: {_zip_done}/{len(chunk)}")
                        except Exception:
                            pass
                        if data is not None:
                            output_name = f"{sf.stem}_whatsapp.webp"
                            with open(processing_dir / output_name, 'wb') as fh:
                                fh.write(data)
                            processed_count += 1

                    if processed_count == 0:
                        continue

                    tray_path = processing_dir / "tray.png"
                    try:
                        first_file = chunk[0]
                        with open(first_file, 'rb') as f:
                            tray_data = f.read()
                        is_animated = first_file in animated_files
                        optimized_tray = await _run_cpu_bound(optimize_tray_icon, tray_data, is_animated)
                        with open(tray_path, 'wb') as f:
                            f.write(optimized_tray.getvalue())
                    except Exception as e:
                        logger.warning(f"Could not create tray icon: {e}")

                    wasticker_name = f"{sanitize_filename(part_title)}.wasticker"
                    wasticker_path = tmp_path / wasticker_name
                    with zipfile.ZipFile(wasticker_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
                        if tray_path.exists():
                            zipf.write(tray_path, 'tray.png')
                        zipf.writestr('author.txt', author_name.encode('utf-8'))
                        zipf.writestr('title.txt', part_title.encode('utf-8'))
                        for webp_file in processing_dir.glob("*.webp"):
                            zipf.write(webp_file, f"sticker_{webp_file.stem[-8:]}.webp")

                    await msg.edit_text(f"📤 Uploading {type_name} sticker pack...")
                    part_suffix = f" (Part {part_num}/{num_parts})" if num_parts > 1 else ""
                    caption = f"✅ {type_name} Stickers{part_suffix}\nProcessed {processed_count} stickers from {message.document.file_name}"

                    await client.send_document(
                        chat_id=target_chat,
                        document=str(wasticker_path),
                        file_name=wasticker_name,
                        caption=caption,
                        disable_notification=True
                    )
                    total_packs_sent += 1

            if total_packs_sent == 0:
                await msg.edit_text("❌ Failed to process any sticker packs from the ZIP.")
            else:
                try:
                    await msg.delete()
                except Exception:
                    pass

    except Exception as e:
        logger.error(f"ZIP processing failed: {e}")
        try:
            await msg.edit_text(f"Processing failed: {str(e)}")
        except Exception:
            pass

# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import atexit

    def _shutdown():
        global _HTTP_SESSION, _PROCESS_POOL, _CPU_POOL
        if _PROCESS_POOL is not None:
            _PROCESS_POOL.shutdown(wait=False)
            _PROCESS_POOL = None
        if _CPU_POOL is not None:
            _CPU_POOL.shutdown(wait=False)
            _CPU_POOL = None
        if _HTTP_SESSION is not None and not _HTTP_SESSION.closed:
            import asyncio as _asyncio
            try:
                loop = _asyncio.get_event_loop()
                if loop.is_running():
                    loop.create_task(_HTTP_SESSION.close())
                else:
                    loop.run_until_complete(_HTTP_SESSION.close())
            except Exception:
                pass
            _HTTP_SESSION = None

    atexit.register(_shutdown)

    async def _main():
        # Start background cleanup tasks before running the bot
        asyncio.create_task(_session_cleanup_loop())
        await app.start()
        logger.info("Starting Sticker Pack Bot...")
        logger.info(f"Loaded {len(AUTHORIZED_CHATS)} authorized chats.")
        logger.info(f"Config: {CONFIG}")
        await asyncio.Event().wait()

    app.run(_main())