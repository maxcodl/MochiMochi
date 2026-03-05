import os
import re
import zipfile
import logging
from io import BytesIO
from PIL import Image
from pyrogram import Client, filters
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
import ffmpeg
from pathlib import Path
import sys
import shutil
import emoji
# Load environment variables from .env file
load_dotenv()

# Configure logging to only show INFO and higher for cleaner output by default
logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO
)
logger = logging.getLogger(__name__)

# Suppress verbose pyrogram logs
logging.getLogger("pyrogram").setLevel(logging.WARNING)

# Get environment variables
API_ID = os.getenv("API_ID")
API_HASH = os.getenv("API_HASH")
BOT_TOKEN = os.getenv("BOT_TOKEN")
OWNER_ID = int(os.getenv("OWNER_ID", 0))

# Validate environment variables
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

# Authorized chats mechanism (whitelist for groups)
BASE_DIR = Path(__file__).resolve().parent
AUTHORIZED_CHATS_FILE = BASE_DIR / 'authorized_chats.json'
APP_PLAYSTORE_URL = "https://play.google.com/store/apps/details?id=com.kawai.mochii"
try:
    with open(AUTHORIZED_CHATS_FILE, 'r') as f:
        AUTHORIZED_CHATS = set(json.load(f))
except FileNotFoundError:
    AUTHORIZED_CHATS = set()
except Exception as e:
    logger.error(f"Error loading authorized chats: {e}")
    AUTHORIZED_CHATS = set()

def save_authorized_chats():
    """Saves authorized chat IDs to a JSON file."""
    try:
        with open(AUTHORIZED_CHATS_FILE, 'w') as f:
            json.dump(list(AUTHORIZED_CHATS), f)
        logger.info("Authorized chats saved.")
    except Exception as e:
        logger.error(f"Error saving authorized chats: {e}")

def sanitize_filename(name: str) -> str:
    """Stricter sanitization for filesystem and Telegram compatibility."""
    # 1. Remove leading @ symbol (causes WhatsApp URI parsing failures)
    name = name.lstrip("@")
    # 2. Remove dots specifically (they are the main cause of extension mangling)
    name = name.replace(".", "")
    # 3. Remove non-ASCII characters/emojis for the filename only
    name = name.encode("ascii", "ignore").decode("ascii")
    # 4. Remove invalid OS characters
    name = re.sub(r'[<>:"/\\|?*]', '', name)
    # 5. Replace spaces and hyphens with underscores
    name = re.sub(r'[\s-]+', '_', name).strip("_")
    
    # Return a fallback if the name became empty (e.g. it was all emojis)
    return name[:50] if name else "sticker_pack"


# WhatsApp sticker hard limits
WA_MAX_BYTES   = 500 * 1024  # 500 KB per sticker (hard limit)
WA_ANIM_TARGET = 500 * 1024  # encode target (use full limit with fast encoding)

def build_open_app_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("Install / Update App", url=APP_PLAYSTORE_URL)]
    ])


def is_valid_webp_output(data: bytes, require_animated: bool = False) -> tuple[bool, str]:
    if not data or len(data) < 128:
        return False, "Converted output is empty or too small"
    if len(data) < 12 or data[0:4] != b'RIFF' or data[8:12] != b'WEBP':
        return False, "Converted output is not a valid WebP container"

    try:
        with Image.open(BytesIO(data)) as check:
            if check.width != 512 or check.height != 512:
                return False, f"Invalid dimensions {check.width}x{check.height}, expected 512x512"
            if require_animated and not _is_animated_webp_bytes(data):
                return False, "Expected animated WebP but got static output"
    except Exception as e:
        return False, f"Cannot decode converted WebP: {e}"

    # Size gate — WhatsApp rejects stickers >500 KB
    if len(data) > WA_MAX_BYTES:
        return False, f"Converted sticker is {len(data) // 1024}KB, exceeds WhatsApp's 500KB limit"

    return True, ""

async def verify_sticker(sticker_data: bytes, is_animated: bool, is_video: bool, file_id: str) -> dict:
    """
    Verifies sticker data for corruption, emptiness, and validity.
    Returns dict with 'valid': bool, 'reason': str, 'warnings': list
    """
    result = {
        'valid': True,
        'reason': '',
        'warnings': []
    }
    
    try:
        # Check 1: Basic file size validation (strict 3KB minimum)
        min_size = 3 * 1024  # 3KB minimum
        if len(sticker_data) < min_size:
            result['valid'] = False
            result['reason'] = f"File too small ({len(sticker_data)} bytes, minimum: {min_size} bytes) - likely corrupt or invalid"
            return result
        
        # Check 2: Maximum size check
        max_size = 500 * 1024  # 500KB max for WhatsApp
        if len(sticker_data) > max_size:
            result['warnings'].append(f"Large file ({len(sticker_data)} bytes), will be compressed")
        
        # Check 3: Verify file format based on type
        if is_animated:
            # TGS files should be gzipped JSON
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
            # WebM/video files - check with ffmpeg
            try:
                with tempfile.NamedTemporaryFile(suffix='.webm', delete=False) as tmp:
                    tmp.write(sticker_data)
                    tmp_path = tmp.name
                
                try:
                    probe = ffmpeg.probe(tmp_path)
                    video_streams = [s for s in probe.get('streams', []) if s['codec_type'] == 'video']
                    
                    if not video_streams:
                        result['valid'] = False
                        result['reason'] = "Video file has no video stream"
                        return result
                    
                    duration = float(probe.get('format', {}).get('duration', 0))
                    if duration <= 0:
                        result['valid'] = False
                        result['reason'] = "Video has zero duration"
                        return result
                    
                    if duration > 3.1:
                        result['warnings'].append(f"Video too long ({duration:.1f}s), will be trimmed to 3s")
                        
                finally:
                    os.unlink(tmp_path)
                    
            except Exception as e:
                result['valid'] = False
                result['reason'] = f"Invalid video format: {str(e)}"
                return result
        
        else:
            # Static image - check with PIL
            try:
                img = Image.open(BytesIO(sticker_data))
                
                # Verify image can be loaded
                img.verify()
                
                # Re-open for further checks (verify() closes the image)
                img = Image.open(BytesIO(sticker_data))
                
                # Check dimensions
                if img.width < 10 or img.height < 10:
                    result['valid'] = False
                    result['reason'] = f"Image too small ({img.width}x{img.height})"
                    return result
                
                if img.width > 5000 or img.height > 5000:
                    result['warnings'].append(f"Large dimensions ({img.width}x{img.height}), will be resized")
                
                # Check if image is completely transparent/empty
                if img.mode in ('RGBA', 'LA'):
                    # Convert to RGBA to check alpha
                    img_rgba = img.convert('RGBA')
                    pixels = list(img_rgba.getdata())
                    
                    # Check if all pixels are fully transparent
                    if all(pixel[3] == 0 for pixel in pixels):
                        result['valid'] = False
                        result['reason'] = "Image is completely transparent (empty)"
                        return result
                    
                    # Check if image has very little content (>95% transparent)
                    transparent_count = sum(1 for pixel in pixels if pixel[3] < 10)
                    transparency_ratio = transparent_count / len(pixels)
                    if transparency_ratio > 0.95:
                        result['warnings'].append(f"Image is {transparency_ratio*100:.1f}% transparent")
                
                # Check if image is all white/single color
                extrema = img.convert('RGB').getextrema()
                if all(min_val == max_val for min_val, max_val in extrema):
                    result['warnings'].append("Image appears to be solid color")
                    
            except Exception as e:
                result['valid'] = False
                result['reason'] = f"Invalid image format: {str(e)}"
                return result
        
        logger.info(f"Sticker {file_id[-8:]} verified: valid={result['valid']}, warnings={len(result['warnings'])}")
        return result
        
    except Exception as e:
        result['valid'] = False
        result['reason'] = f"Verification error: {str(e)}"
        return result

def optimize_tray_icon(tray_data: bytes, is_animated: bool = False) -> BytesIO:
    """
    Optimizes tray icon to be under 50KB and 96x96 pixels, returning BytesIO.
    Correctly preserves alpha/transparency in all code paths.
    """
    try:
        if is_animated:
            # Detect TGS (gzip-compressed Lottie JSON) by magic bytes \x1f\x8b
            is_tgs = len(tray_data) >= 2 and tray_data[:2] == b'\x1f\x8b'

            if is_tgs:
                import gzip as _gzip
                json_bytes = _gzip.decompress(tray_data)
                try:
                    import lottie  # type: ignore
                    from lottie.exporters.png import export_png as _export_png  # type: ignore
                    anim = lottie.Animation.load(BytesIO(json_bytes))
                    frame_buf = BytesIO()
                    _export_png(anim, frame_buf, frame=0, width=96, height=96)
                    frame_buf.seek(0)
                    img = Image.open(frame_buf).copy()
                    logger.info("Rendered TGS first frame via lottie library for tray icon")
                except ImportError:
                    raise Exception("lottie library not available for TGS rendering")
            else:
                # WebM / video sticker — extract first frame via ffmpeg, keeping alpha plane.
                logger.info("Extracting first frame from animated sticker for tray icon")
                with tempfile.TemporaryDirectory() as tmpdir:
                    tmppath = Path(tmpdir)
                    input_path = tmppath / "input.webm"
                    input_path.write_bytes(tray_data)
                    output_path = tmppath / "frame.png"
                    cmd = [
                        'ffmpeg',
                        '-i', str(input_path),
                        '-vf', r'select=eq(n\,0)',
                        '-frames:v', '1',
                        '-pix_fmt', 'rgba',
                        '-y',
                        str(output_path)
                    ]
                    result = subprocess.run(cmd, capture_output=True, text=True, shell=False)
                    if result.returncode == 0 and output_path.exists():
                        with open(output_path, 'rb') as f:
                            frame_data = f.read()
                        img = Image.open(BytesIO(frame_data)).copy()
                        logger.info("Successfully extracted first frame for tray icon")
                    else:
                        logger.warning(f"Failed to extract frame, creating placeholder: {result.stderr}")
                        raise Exception("Frame extraction failed")
        else:
            img = Image.open(BytesIO(tray_data)).copy()

    except Exception as e:
        logger.warning(f"Tray icon conversion failed, using transparent placeholder: {e}")
        img = Image.new("RGBA", (96, 96), (0, 0, 0, 0))

    # Simple transparency-preserving resize
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    
    # Create transparent 96x96 canvas
    canvas = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    
    # Resize image to fit within 96x96 while maintaining aspect ratio
    img.thumbnail((96, 96), Image.LANCZOS)
    
    # Center the image on transparent canvas
    position = (
        (96 - img.width) // 2,
        (96 - img.height) // 2
    )
    canvas.paste(img, position, img)

    # PNG uses compress_level, NOT quality
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
    """
    Safely parse ffmpeg frame rate string like "25/1" or "30000/1001".
    Returns float fps value (e.g., 25.0 or 29.97).
    """
    try:
        if '/' in fps_string:
            num, denom = fps_string.split('/')
            return float(num) / float(denom)
        else:
            return float(fps_string)
    except (ValueError, ZeroDivisionError):
        return 15.0  # Default fallback


def convert_to_whatsapp_static(img: Image.Image) -> BytesIO:
    """
    Converts a PIL Image to WhatsApp-compatible static WebP.
    
    WhatsApp Official Specs:
    - Dimensions: Exactly 512x512 pixels
    - Format: WebP
    - File size: ≤100KB
    - Transparent background
    """
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    
    # Create 512x512 canvas with transparent background (per WhatsApp specs)
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    img.thumbnail((512, 512), Image.LANCZOS)
    
    position = (
        (512 - img.width) // 2,
        (512 - img.height) // 2
    )
    canvas.paste(img, position, img)
    
    # Optimize to meet 100KB requirement
    output = BytesIO()
    quality = 95
    max_attempts = 10
    
    for attempt in range(max_attempts):
        output.seek(0)
        output.truncate(0)
        canvas.save(output, format="WEBP", quality=quality)
        size = output.tell()
        
        if size <= 100 * 1024:  # 100KB per WhatsApp spec
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
    """
    Converts a static PIL Image into a guaranteed animated WebP.
    This is used to allow mixing static stickers into an animated pack.
    """
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    
    # Create 512x512 canvas with transparent background
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    img.thumbnail((512, 512), Image.LANCZOS)
    
    position = (
        (512 - img.width) // 2,
        (512 - img.height) // 2
    )
    canvas.paste(img, position, img)
    
    output = BytesIO()

    # Create a second near-identical frame to guarantee ANIM chunk presence.
    frame2 = canvas.copy()
    px = frame2.load()
    r, g, b, a = px[0, 0]
    px[0, 0] = (r, g, b, 1 if a == 0 else 0)

    canvas.save(
        output, format="WEBP", save_all=True,
        append_images=[frame2],
        duration=[100, 100], loop=0, quality=85, method=6,
        background=(0, 0, 0, 0), lossless=False
    )
    output.seek(0)

    if not _is_animated_webp_bytes(output.getvalue()):
        raise Exception("Failed to force animated WebP output")

    return output




def _encode_animated_webp_under_limit(
    pil_frames: list,
    frame_duration_ms: int,
    max_size: int = 490 * 1024,
) -> BytesIO:
    """
    Encode an animated WebP that fits within *max_size* bytes.
    
    FAST encoding strategy: Try a few key quality points instead of binary search.
    Preserves transparency via kmax=1 + allow_mixed + alpha_quality.
    """
    # CRITICAL: Ensure we have at least 2 frames for animation
    if len(pil_frames) < 2:
        raise Exception(f"Cannot create animated WebP with only {len(pil_frames)} frame(s). Need at least 2.")
    
    def _try(frames, dur_ms, q):
        buf = BytesIO()
        frames[0].save(
            buf, format="WEBP", save_all=True,
            append_images=frames[1:],
            duration=dur_ms, loop=0,
            quality=q, method=0,       # fastest encoder
            kmax=1,                    # every frame is a keyframe → no transparent bleed
            allow_mixed=True,          # lossless alpha + lossy colour per frame
            alpha_quality=90,          # high-quality alpha plane
            background=(0, 0, 0, 0),
        )
        return buf

    def _fast_quality_search(frames, dur_ms):
        """Ultra-fast quality search: try only 3 key points for speed"""
        for q in [50, 30, 15]:
            buf = _try(frames, dur_ms, q)
            sz = buf.tell()
            if sz <= max_size:
                buf.seek(0)
                return buf, q, sz
        return None, None, 0

    # Try only essential decimation levels: 1, 2, 4 (skip 6 for speed)
    decimation_levels = [1, 2, 4]
    
    for decimation in decimation_levels:
        if decimation == 1:
            cur_frames = pil_frames
            cur_dur    = frame_duration_ms
        else:
            cur_frames = pil_frames[::decimation]
            cur_dur    = min(frame_duration_ms * decimation, 1000)
            
            # Skip decimation levels that would produce inadequate frame counts
            if len(cur_frames) < 2:
                logger.debug(
                    f"Decimation {decimation}x would yield {len(cur_frames)} frame(s) - skipping"
                )
                continue
            
            logger.info(
                f"Animated WebP still too large — decimating to every {decimation}th frame "
                f"({len(cur_frames)} frames, {cur_dur}ms/frame)"
            )

        best_buf, best_q, best_size = _fast_quality_search(cur_frames, cur_dur)
        if best_buf is not None:
            logger.info(
                f"✓ Animated WebP: {len(cur_frames)} frames "
                f"(decimation={decimation}x), quality={best_q}, "
                f"{best_size // 1024}KB"
            )
            return best_buf

        logger.info(
            f"Still over limit at decimation={decimation}x, quality=15 — "
            f"trying more aggressive decimation…"
        )

    # No decimation level produced acceptable result - fail
    raise Exception(
        f"Could not encode animated WebP under {max_size // 1024}KB limit even with "
        f"maximum decimation (4x) and minimum quality (15). "
        f"Sticker is too complex to convert."
    )


async def convert_tgs_to_animated_webp(tgs_data: bytes) -> BytesIO:
    """
    Convert Telegram's TGS format (Lottie JSON in gzip) to animated WebP.

    PRIMARY path:  TGS -> JSON -> PNG frames (full RGBA) via lottie API -> animated WebP via PIL
    FALLBACK path: TGS -> JSON -> GIF via lottie CLI -> animated WebP via ffmpeg
                   (fallback loses smooth alpha — GIF only has 1-bit transparency)
    """
    import json as _json

    with tempfile.TemporaryDirectory() as tmpdir:
        tmppath = Path(tmpdir)
        json_path = tmppath / "sticker.json"

        # 1. Decompress TGS (gzipped Lottie JSON)
        try:
            with gzip.open(BytesIO(tgs_data), 'rb') as gz:
                json_data = gz.read()
            json_path.write_bytes(json_data)
            logger.info(f"Decompressed TGS to JSON ({len(json_data)} bytes)")
        except Exception as e:
            logger.error(f"Failed to decompress TGS: {e}")
            raise Exception(f"Invalid TGS file: {e}")

        # 2. Parse animation metadata
        try:
            anim_meta = _json.loads(json_data)
            fps = max(1.0, float(anim_meta.get('fr', 30)))
            in_point  = int(anim_meta.get('ip', 0))
            out_point = int(anim_meta.get('op', 90))
            total_frames = max(1, out_point - in_point)
        except Exception:
            fps, in_point, total_frames = 30.0, 0, 90

        # WhatsApp: max 10 s, min 8 ms/frame
        max_frames        = int(10.0 * fps)
        render_frames     = min(total_frames, max_frames)
        frame_duration_ms = max(8, int(1000.0 / fps))

        frames_dir = tmppath / "frames"
        frames_dir.mkdir()

        # === PRIMARY PATH: render each Lottie frame as RGBA PNG ===
        try:
            render_script = (
                "import sys; "
                "from lottie.parsers.lottie import parse_file; "
                "from lottie.exporters.png import export; "
                "import os; "
                "anim = parse_file(sys.argv[1]); "
                "ip = int(anim.in_point); "
                "op = min(int(anim.out_point), ip + int(sys.argv[3])); "
                "[export(anim, os.path.join(sys.argv[2], 'frame_{:04d}.png'.format(i - ip)), frame=i) "
                " for i in range(ip, op)]; "
                "print(f'DONE:{op - ip}')"
            )
            result = subprocess.run(
                [sys.executable, "-c", render_script,
                 str(json_path), str(frames_dir), str(render_frames)],
                capture_output=True, text=True, timeout=120
            )

            if result.returncode != 0:
                logger.error(f"Lottie render script failed (rc={result.returncode})")
                logger.error(f"STDOUT: {result.stdout}")
                logger.error(f"STDERR: {result.stderr}")
                raise Exception(f"Lottie render failed: {result.stderr[:200]}")

            frame_files = sorted(frames_dir.glob("frame_*.png"))

            if not frame_files:
                logger.error(f"No PNG frames found in {frames_dir}")
                raise Exception(f"No PNG frames produced (rc={result.returncode}): {result.stderr[:200]}")

            logger.info(f"✓ Rendered {len(frame_files)} RGBA PNG frames from TGS")

            # Build 512×512 RGBA frames
            pil_frames = []
            for ff in frame_files:
                try:
                    img = Image.open(ff).convert("RGBA")
                    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
                    img.thumbnail((512, 512), Image.LANCZOS)
                    canvas.paste(img, ((512 - img.width) // 2, (512 - img.height) // 2), img)
                    pil_frames.append(canvas)
                except Exception as fe:
                    logger.warning(f"Skipping frame {ff.name}: {fe}")

            if len(pil_frames) < 2:
                raise Exception(f"Too few valid frames ({len(pil_frames)}) to make animation")

            # Encode using shared helper — finer quality steps + frame decimation
            # if quality alone can't get under the limit.  Transparency settings
            # (kmax=1 / allow_mixed / alpha_quality) are never changed.
            output = _encode_animated_webp_under_limit(
                pil_frames, frame_duration_ms, max_size=WA_ANIM_TARGET
            )
            
            # CRITICAL: Verify output is under 500KB hard limit
            output_size = output.seek(0, 2)
            output.seek(0)
            if output_size > WA_MAX_BYTES:
                raise Exception(f"Encoded output is {output_size // 1024}KB, exceeds 500KB hard limit")
            if output_size > WA_ANIM_TARGET:
                raise Exception(f"Encoder bug: output is {output_size // 1024}KB, exceeds target {WA_ANIM_TARGET // 1024}KB")
            
            logger.info(
                f"✓ TGS → animated WebP: "
                f"{len(pil_frames)} frames, {output_size // 1024}KB"
            )
            return output

        except Exception as png_err:
            logger.warning(f"PNG frame path failed: {png_err}. Falling back to GIF intermediate.")

        # === FALLBACK PATH: GIF intermediate (binary alpha only) ===
        gif_path = tmppath / "sticker.gif"
        try:
            # Use lottie cli to export GIF
            cmd = [sys.executable, "-m", "lottie.exporters.gif", str(json_path), str(gif_path)]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.returncode != 0:
                raise Exception(f"GIF export failed: {result.stderr[:200]}")
            if not gif_path.exists() or gif_path.stat().st_size == 0:
                raise Exception("GIF output empty or missing")
            
            # Since we are falling back to GIF, we must use convert_video_to_animated_webp
            # which uses ffmpeg to convert GIF -> WebP while trying to keep transparency.
            with open(gif_path, 'rb') as f:
                gif_data = f.read()
            
            logger.warning(
                f"TGS → GIF fallback ({len(gif_data) / 1024:.1f}KB) "
                f"— smooth transparency NOT preserved (GIF has 1-bit alpha)"
            )
            return await convert_video_to_animated_webp(gif_data)
        except subprocess.TimeoutExpired:
            raise Exception("TGS → GIF conversion timed out (animation too complex)")
        except Exception as gif_err:
            raise Exception(f"TGS conversion failed — PNG: {png_err}; GIF: {gif_err}")



async def convert_video_to_animated_webp(video_data: bytes) -> BytesIO:
    """
    Converts a WebM/video file to an animated WebP.

    Strategy: use ffmpeg ONLY to extract RGBA PNG frames (it handles VP9+alpha correctly),
    then use PIL to assemble the animated WebP — the same approach used for TGS stickers.
    This avoids all libwebp encoder alpha issues (black background bugs) that occur when
    ffmpeg encodes WebP directly.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        tmppath = Path(tmpdir)
        input_path = tmppath / "input.tmp"
        input_path.write_bytes(video_data)
        frames_dir = tmppath / "frames"
        frames_dir.mkdir()

        # --- 1. Probe input to get fps and duration ---
        try:
            probe = ffmpeg.probe(str(input_path))
            video_info = next(s for s in probe['streams'] if s['codec_type'] == 'video')
            
            # For WebM, format-level duration is more reliable than stream-level
            # Try format duration first, then stream duration, then calculate from frame count
            format_duration = probe.get('format', {}).get('duration')
            stream_duration = video_info.get('duration')
            nb_frames = video_info.get('nb_frames')
            
            if format_duration and float(format_duration) > 0.1:
                duration = float(format_duration)
            elif stream_duration and float(stream_duration) > 0.1:
                duration = float(stream_duration)
            elif nb_frames and int(nb_frames) > 0:
                # Calculate from frame count and fps
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

        # For WebM stickers, duration metadata is often broken (reports 0.001-0.002s for 1-2s videos)
        # Solution: Extract ALL available frames (don't limit by duration), then check what we got
        # WhatsApp limit: 10s max, 8fps min (8ms/frame min)
        target_fps = min(input_fps, 20.0)  # Cap at 20fps for file size
        target_fps = max(target_fps, 8.0)  # Ensure at least 8fps
        frame_duration_ms = max(8, int(1000.0 / target_fps))
        
        # Don't use duration to calculate max_frames - it's unreliable
        # WhatsApp max: 10s * 20fps = 200 frames (safety limit)
        max_frames = 200

        # --- 2. Extract frames as RGBA PNG using ffmpeg ---
        # Force -c:v libvpx-vp9 (software decoder) — same as sticker-convert's
        # CodecContext.create("libvpx-vp9", "r"). The hardware vp9 decoder does NOT
        # expose the VP9 alpha plane; the software libvpx-vp9 decoder does.
        # Extract ALL available frames up to max_frames (ignoring broken duration metadata)
        extract_cmd = [
            'ffmpeg', '-y',
            '-c:v', 'libvpx-vp9',       # force software decoder to get alpha plane
            '-i', str(input_path),
            '-vf', f'fps={target_fps},format=rgba',
            '-vframes', str(max_frames),  # safety limit only
            '-vsync', 'cfr',
            str(frames_dir / 'frame_%04d.png')
        ]
        result = subprocess.run(extract_cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise Exception(f"ffmpeg frame extraction failed: {result.stderr[:300]}")

        frame_files = sorted(frames_dir.glob("frame_*.png"))
        if not frame_files:
            raise Exception("ffmpeg produced no frames from video")
        logger.info(f"Extracted {len(frame_files)} RGBA PNG frames from video")

        # --- 3. Build 512×512 RGBA PIL frames (transparent letterbox padding) ---
        pil_frames = []
        for ff in frame_files:
            try:
                img = Image.open(ff).convert("RGBA")
                canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))  # fully transparent
                img.thumbnail((512, 512), Image.LANCZOS)
                x = (512 - img.width) // 2
                y = (512 - img.height) // 2
                canvas.paste(img, (x, y), img)
                pil_frames.append(canvas)
            except Exception as fe:
                logger.warning(f"Skipping frame {ff.name}: {fe}")

        if len(pil_frames) < 2:
            raise Exception(f"Too few valid frames ({len(pil_frames)}) for animation")

        # --- 4. Encode animated WebP using shared helper ---
        # Uses finer quality steps + frame decimation to guarantee ≤500KB
        # while keeping kmax=1 / allow_mixed / alpha_quality intact.
        output = _encode_animated_webp_under_limit(
            pil_frames, frame_duration_ms, max_size=WA_ANIM_TARGET
        )
        final_size = output.seek(0, 2)
        output.seek(0)
        
        # CRITICAL: Verify output is under 500KB hard limit
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
    """
    Converts TGS or WebM data to an animated WebP.
    Raises Exception if conversion fails — callers must handle the fallback.
    """
    if is_tgs:
        logger.info("Converting TGS sticker to animated WebP...")
        return await convert_tgs_to_animated_webp(file_data)
    else:
        logger.info("Converting video sticker to animated WebP...")
        return await convert_video_to_animated_webp(file_data)


def _is_animated_webp_bytes(data: bytes) -> bool:
    """Check if raw bytes represent an animated WebP (has VP8X chunk with ANIM flag)."""
    if len(data) < 30:
        return False
    if data[0:4] != b'RIFF' or data[8:12] != b'WEBP':
        return False
    
    # Check for VP8X chunk (extended format with animation flag)
    if data[12:16] == b'VP8X':
        flags = data[20] & 0xFF
        return bool(flags & 0x02)  # Animation bit
    
    # Check for ANIM chunk (older format)
    # Search first 512 bytes for ANIM chunk
    search_end = min(len(data), 512)
    if b'ANIM' in data[12:search_end]:
        return True
    
    # If neither VP8X with anim flag nor ANIM chunk found, it's static
    return False


def split_stickers_by_type(stickers: list):
    """Splits stickers into static and animated/video lists."""
    static = [s for s in stickers if not (s.is_animated or s.is_video)]
    animated = [s for s in stickers if s.is_animated or s.is_video]
    return static, animated

def split_into_chunks(items: list, max_per_chunk: int = 30) -> list:
    """Splits list into chunks of max_per_chunk size."""
    return [items[i:i + max_per_chunk] for i in range(0, len(items), max_per_chunk)]

async def create_simple_zip(
    set_name: str,
    stickers: list,
    convert: bool = True,
    progress_callback=None
) -> tuple[Path, int]:
    """Creates a simple ZIP file with stickers (with or without conversion).
    Returns: (zip_path, valid_count)
    """
    logger.info(f"Creating simple ZIP for: {set_name} (convert={convert})")
    
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)
    
    # Use a safe unique workdir name
    import uuid
    unique_id = uuid.uuid4().hex[:8]
    work_dir = packs_dir / f"simple_{set_name}_{unique_id}"
    work_dir.mkdir(exist_ok=True)
    
    valid_count = 0
    total = len(stickers)
    stats = {
        'skipped': 0,
        'skipped_reasons': []
    }
    
    try:
        for i, sticker in enumerate(stickers, 1):
            try:
                if progress_callback:
                    await progress_callback(i, total)
                
                sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)
                logger.debug(f"Downloaded sticker {i}.")
                
                # VERIFY STICKER BEFORE PROCESSING (strict 3KB minimum)
                verification = await verify_sticker(
                    sticker_data,
                    sticker.is_animated,
                    sticker.is_video,
                    sticker.file_id
                )
                
                if not verification['valid']:
                    logger.warning(f"❌ Skipping sticker {i}/{total}: {verification['reason']}")
                    stats['skipped'] += 1
                    stats['skipped_reasons'].append(f"Sticker {i}: {verification['reason']}")
                    continue
                
                # Log warnings if any
                if verification['warnings']:
                    for warning in verification['warnings']:
                        logger.info(f"⚠️ Sticker {i}: {warning}")
                
                if convert:
                    # Convert to WhatsApp format
                    if sticker.is_animated or sticker.is_video:
                        converted = await convert_to_whatsapp_animated(sticker_data, sticker.is_animated)
                        extension = "webp"
                    else:
                        img = Image.open(BytesIO(sticker_data))
                        converted = convert_to_whatsapp_static(img)
                        extension = "webp"
                    
                    sticker_filename = f"sticker_{i:03d}.{extension}"
                    sticker_path = work_dir / sticker_filename
                    with open(sticker_path, 'wb') as f:
                        f.write(converted.getvalue())
                else:
                    # Save raw file with proper extension
                    if sticker.is_animated:
                        extension = "tgs"
                    elif sticker.is_video:
                        extension = "webm"
                    else:
                        extension = "webp"
                    
                    sticker_filename = f"sticker_{i:03d}.{extension}"
                    sticker_path = work_dir / sticker_filename
                    with open(sticker_path, 'wb') as f:
                        f.write(sticker_data)
                
                valid_count += 1
                await asyncio.sleep(0.3)  # Rate limiting
                
            except Exception as e:
                logger.error(f"Error processing sticker {i}: {e}")
                continue
        
        if valid_count == 0:
            raise ValueError("No valid stickers found after verification.")
        
        # Create ZIP
        zip_path = packs_dir / f"{set_name}.zip"
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file_path in work_dir.iterdir():
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
    progress_callback=None
) -> tuple[Path, int, dict]:
    """Creates a WhatsApp sticker pack ZIP file in a temporary folder.
    Returns: (zip_path, valid_count, stats_dict)
    """
    logger.info("Starting ZIP creation for pack: %s", set_name)
    
    # Create main wasticker packs directory
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)
    
    # Create working directory inside wasticker_packs
    import uuid
    unique_id = uuid.uuid4().hex[:8]
    work_dir = packs_dir / f"pack_{set_name}_{unique_id}"
    work_dir.mkdir(exist_ok=True)
    
    valid_count = 0
    total = len(stickers)
    emoji_map = {}  # Map sticker filename to emoji
    stats = {
        'skipped': 0,
        'corrupt': 0,
        'empty': 0,
        'invalid': 0,
        'warnings': 0,
        'skipped_reasons': []
    }
    
    try:
        # Save tray icon
        tray_path = work_dir / "tray.png"
        with open(tray_path, 'wb') as f:
            f.write(tray_bytes.getvalue())
        
        # Save author and title
        (work_dir / "author.txt").write_text(author, encoding='utf-8')
        (work_dir / "title.txt").write_text(title, encoding='utf-8')
        logger.debug("Added tray icon, author.txt, and title.txt to folder.")

        # Determine pack type ONCE before processing (not per-sticker)
        should_be_animated = any(s.is_animated or s.is_video for s in stickers)
        pack_type = "Animated" if should_be_animated else "Static"
        logger.info(f"Pack type determined: {pack_type} ({sum(1 for s in stickers if s.is_animated)} TGS + {sum(1 for s in stickers if s.is_video)} video + {sum(1 for s in stickers if not (s.is_animated or s.is_video))} static)")

        # Process each sticker
        for i, sticker in enumerate(stickers, 1):
            try:
                if progress_callback:
                    await progress_callback(i, total)
                
                sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)
                logger.debug(f"Downloaded sticker {i}.")
                
                # VERIFY STICKER BEFORE PROCESSING
                verification = await verify_sticker(
                    sticker_data,
                    sticker.is_animated,
                    sticker.is_video,
                    sticker.file_id
                )
                
                if not verification['valid']:
                    logger.warning(f"❌ Skipping sticker {i}/{total}: {verification['reason']}")
                    stats['skipped'] += 1
                    stats['skipped_reasons'].append(f"Sticker {i}: {verification['reason']}")
                    
                    # Categorize the issue
                    reason_lower = verification['reason'].lower()
                    if 'corrupt' in reason_lower or 'invalid' in reason_lower:
                        stats['corrupt'] += 1
                    elif 'empty' in reason_lower or 'transparent' in reason_lower:
                        stats['empty'] += 1
                    else:
                        stats['invalid'] += 1
                    
                    continue
                
                # Log warnings
                if verification['warnings']:
                    stats['warnings'] += len(verification['warnings'])
                    for warning in verification['warnings']:
                        logger.info(f"⚠️ Sticker {i}: {warning}")

                converted = None
                conversion_failed = False

                # === ANIMATED/VIDEO STICKER CONVERSION ===
                if sticker.is_animated or sticker.is_video:
                    logger.info(f"Sticker {i}/{total}: Converting {'TGS' if sticker.is_animated else 'video'} to animated WebP")
                    try:
                        animated_out = await convert_to_whatsapp_animated(sticker_data, sticker.is_animated)
                        animated_bytes = animated_out.getvalue()
                        
                        # Verify it's actually animated
                        is_anim = _is_animated_webp_bytes(animated_bytes)
                        logger.debug(f"Sticker {i}: Output is {'ANIMATED' if is_anim else 'STATIC'} ({len(animated_bytes)//1024}KB)")
                        
                        if not is_anim:
                            logger.error(f"Sticker {i}: Conversion produced STATIC WebP - skipping (this should not happen)")
                            stats['skipped'] += 1
                            stats['invalid'] += 1
                            stats['skipped_reasons'].append(f"Sticker {i}: conversion produced static instead of animated")
                            continue
                        else:
                            converted = BytesIO(animated_bytes)
                            logger.info(f"Sticker {i}: Successfully converted to animated WebP")
                            
                    except Exception as anim_err:
                        logger.error(f"Sticker {i}: Animated conversion failed: {anim_err}")
                        conversion_failed = True
                    
                    # Last resort fallback for animated stickers
                    if conversion_failed:
                        logger.error(f"Sticker {i}: {'TGS' if sticker.is_animated else 'video'} conversion failed - skipping")
                        stats['skipped'] += 1
                        stats['skipped_reasons'].append(f"Sticker {i}: {'TGS' if sticker.is_animated else 'video'} conversion failed")
                        continue

                # === STATIC STICKER IN ANIMATED PACK ===
                elif should_be_animated:
                    # Static stickers should not be mixed with animated packs - skip them
                    logger.warning(f"Sticker {i}/{total}: Static sticker found in animated pack - skipping to maintain pack consistency")
                    stats['skipped'] += 1
                    stats['skipped_reasons'].append(f"Sticker {i}: static sticker in animated pack")
                    continue

                # === STATIC STICKER IN STATIC PACK ===
                else:
                    logger.info(f"Sticker {i}/{total}: Converting static sticker")
                    try:
                        img = Image.open(BytesIO(sticker_data))
                        converted = convert_to_whatsapp_static(img)
                    except Exception as e:
                        logger.error(f"Sticker {i}: Failed to convert static sticker: {e}")
                        stats['skipped'] += 1
                        stats['skipped_reasons'].append(f"Sticker {i}: static conversion failed")
                        continue
                
                # Safety check
                if converted is None:
                    logger.error(f"Sticker {i}: Conversion resulted in None, skipping")
                    stats['skipped'] += 1
                    stats['skipped_reasons'].append(f"Sticker {i}: conversion produced None")
                    continue

                converted_bytes = converted.getvalue()
                
                # CRITICAL: Verify output is actually animated (not static)
                if should_be_animated and not _is_animated_webp_bytes(converted_bytes):
                    logger.error(f"Sticker {i}: Conversion resulted in STATIC WebP when animated was required!")
                    stats['skipped'] += 1
                    stats['invalid'] += 1
                    stats['skipped_reasons'].append(f"Sticker {i}: produced static WebP instead of animated")
                    continue

                # Hard size gate — reject anything still over 500 KB after conversion
                if len(converted_bytes) > WA_MAX_BYTES:
                    logger.warning(
                        f"❌ Skipping sticker {i}/{total}: converted size "
                        f"{len(converted_bytes) // 1024}KB exceeds WhatsApp's 500KB limit"
                    )
                    stats['skipped'] += 1
                    stats['invalid'] += 1
                    stats['skipped_reasons'].append(
                        f"Sticker {i}: oversized after conversion ({len(converted_bytes) // 1024}KB)"
                    )
                    continue

                is_valid, validation_reason = is_valid_webp_output(converted_bytes, require_animated=should_be_animated)
                if not is_valid:
                    logger.warning(f"❌ Skipping sticker {i}/{total}: {validation_reason}")
                    stats['skipped'] += 1
                    stats['invalid'] += 1
                    stats['skipped_reasons'].append(f"Sticker {i}: {validation_reason}")
                    continue

                # Use unique filename based on file_id to avoid collisions
                sticker_filename = f"{set_name}_{sticker.file_id[-12:]}.webp"
                sticker_path = work_dir / sticker_filename
                with open(sticker_path, 'wb') as f:
                    f.write(converted_bytes)
                
                # Extract and store emoji info for this sticker
                raw_emoji = getattr(sticker, 'emoji', "😀")
                
                # Robustly split emoji string using the emoji library
                emoji_list = emoji.distinct_emoji_list(raw_emoji)
                
                if not emoji_list:
                    # Fallback to simple split or default if something went wrong
                    emoji_list = [raw_emoji] if raw_emoji else ["😀"]
                
                # Limit to first 3 emojis (WhatsApp requirement)
                emoji_list = emoji_list[:3]
                
                emoji_map[sticker_filename] = emoji_list
                logger.debug(f"Sticker {valid_count + 1} emojis: {emoji_list}")
                
                valid_count += 1
                await asyncio.sleep(0.05) # Rate limiting delay

            except Exception as e:
                logger.error(f"Error processing sticker {i} in pack {set_name}: {e}")
                logger.debug(traceback.format_exc())
    
        if valid_count == 0:
            raise ValueError("No valid stickers found in the pack.")
        
        if valid_count < 3:
            raise ValueError(f"Pack has only {valid_count} stickers. WhatsApp requires minimum 3 stickers per pack.")
        
        if valid_count > 30:
            logger.warning(f"Pack has {valid_count} stickers, but WhatsApp allows maximum 30. This should have been split earlier.")

        # Save emoji mapping to emojis.json
        emojis_json_path = work_dir / "emojis.json"
        with open(emojis_json_path, 'w', encoding='utf-8') as f:
            json.dump(emoji_map, f, ensure_ascii=False, indent=2)
        logger.info(f"Saved emoji mappings for {len(emoji_map)} stickers")

        # Create WhatsApp-compliant contents.json
        import uuid
        pack_identifier = uuid.uuid4().hex[:16]  # 16-char unique ID
        stickers_array = []
        
        # Build stickers array with emoji mappings
        for sticker_file, emoji_list in emoji_map.items():
            stickers_array.append({
                "image_file": sticker_file,
                "emojis": emoji_list if emoji_list else ["😊"]
            })
        
        contents_json = {
            "android_play_store_link": "",
            "ios_app_store_link": "",
            "sticker_packs": [{
                "identifier": pack_identifier,
                "name": title,
                "publisher": author,
                "tray_image_file": "tray.png",
                "publisher_email": "",
                "publisher_website": "",
                "privacy_policy_website": "",
                "license_agreement_website": "",
                "image_data_version": "1",
                "avoid_cache": False,
                "animated_sticker_pack": True,  # All our packs are animated
                "stickers": stickers_array
            }]
        }
        
        contents_json_path = work_dir / "contents.json"
        with open(contents_json_path, 'w', encoding='utf-8') as f:
            json.dump(contents_json, f, ensure_ascii=False, indent=2)
        logger.info(f"Created WhatsApp-compliant contents.json with {len(stickers_array)} stickers")

        # Create ZIP from folder in wasticker_packs directory
        zip_path = packs_dir / f"{set_name}.wasticker"
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file_path in work_dir.iterdir():
                zipf.write(file_path, file_path.name)
        
        logger.info("ZIP creation finished for pack: %s with %d valid stickers.", set_name, valid_count)
        logger.info(f"Stats: {stats['skipped']} skipped ({stats['corrupt']} corrupt, {stats['empty']} empty, {stats['invalid']} invalid), {stats['warnings']} warnings")
        return zip_path, valid_count, stats
    
    finally:
        # Clean up working directory on exit
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)

async def download_file_by_id(bot_token: str, file_id: str) -> bytes:
    """Downloads a file from Telegram using bot token and file_id."""
    # Get file path
    url = f"https://api.telegram.org/bot{bot_token}/getFile"
    async with aiohttp.ClientSession() as session:
        async with session.get(url, params={"file_id": file_id}) as resp:
            data = await resp.json()
            if not data["ok"]:
                raise Exception(data["description"])
            file_path = data["result"]["file_path"]
        
        # Download file
        download_url = f"https://api.telegram.org/file/bot{bot_token}/{file_path}"
        async with session.get(download_url) as resp:
            if resp.status != 200:
                raise Exception(f"Failed to download file: HTTP {resp.status}")
            return await resp.read()

async def get_sticker_set_via_bot_api(bot_token: str, name: str):
    """Fetches sticker set information using Telegram Bot API."""
    url = f"https://api.telegram.org/bot{bot_token}/getStickerSet"
    async with aiohttp.ClientSession() as session:
        async with session.get(url, params={"name": name}) as resp:
            data = await resp.json()
            if not data["ok"]:
                raise Exception(data["description"])
            return data["result"]

@app.on_message(filters.command("auth") & filters.private)
async def authorize_chat(client: Client, message: Message):
    """Authorizes a chat ID (only for bot owner in private chat). Usage: /auth <chat_id>"""
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can use this command.")
        return
    
    if len(message.command) < 2:
        await message.reply_text("Usage: `/auth <chat_id>` (e.g., `/auth -1001234567890`)")
        return
    
    try:
        chat_id = int(message.command[1])
        AUTHORIZED_CHATS.add(chat_id)
        save_authorized_chats()
        await message.reply_text(f"✅ Chat `{chat_id}` authorized. The bot can now freely work there (ensure permissions are granted).")
    except ValueError:
        await message.reply_text("❌ Invalid chat ID. It should be a number (e.g., -1001234567890 for groups).")

@app.on_message(filters.command("deauth") & filters.private)
async def deauthorize_chat(client: Client, message: Message):
    """Deauthorizes a chat ID (only for bot owner in private chat). Usage: /deauth <chat_id>"""
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can use this command.")
        return
    
    if len(message.command) < 2:
        await message.reply_text("Usage: `/deauth <chat_id>` (e.g., `/deauth -1001234567890`)")
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
    """Lists all authorized chat IDs (only for bot owner in private chat)."""
    if message.from_user.id != OWNER_ID:
        await message.reply_text("❌ Only the bot owner can use this command.")
        return
    
    if not AUTHORIZED_CHATS:
        await message.reply_text("No authorized chats.")
    else:
        chats_list = "\n".join(str(chat_id) for chat_id in sorted(AUTHORIZED_CHATS))
        await message.reply_text(f"Authorized chats:\n`{chats_list}`")

@app.on_message(filters.command("start") & (filters.group | filters.private))
async def start_command(client: Client, message: Message):
    """Shows bot usage instructions."""
    instructions = """
🤖 **Telegram to WhatsApp Sticker Converter**

**Commands:**
• `/wast` - Reply to any sticker to convert its entire pack to WhatsApp format
• `/wast CustomName` - Convert with a custom pack name
• `/wast -z` - Download and ZIP all stickers (converted to WebP)
• `/wast -z -c` - Download and ZIP raw stickers (no conversion)
• `/loadsticker` - Reply to a sticker to import it to WhatsApp
• `/loadsticker PackName` - Import to a named pack
• `/local` - Process sticker files from a local 'stickers' folder
• `/upload` - Upload all .wasticker files from current directory
• `/help` - How to import stickers to WhatsApp
• `/start` - Show this help message

**How to use:**
1. **Telegram Stickers:** Reply to any sticker with `/wast` to convert the whole pack
   - Use `/wast MyCustomName` to give it a custom name
   - Use `/wast -z` to create a simple ZIP with converted stickers
   - Use `/wast -z -c` to download raw files without conversion
2. **Upload Packs:** Use `/upload` to send all .wasticker files from current directory to Telegram
3. **Load Single Sticker:** Reply to any sticker with `/loadsticker` to import it to WhatsApp
   - First time creates a new pack, next times auto-add to the same pack
   - Use `/loadsticker MyPack` for a custom pack name
4. **Local Files:** Create a 'stickers' folder with .webm, .tgs, .png, .jpg, .jpeg, or .webp files, then use `/local`
5. **ZIP Uploads:** Send a `.zip` file containing stickers directly to the bot.

**Features:**
✅ Converts static and animated stickers
✅ Preserves transparency (animated stickers)
✅ Automatic file size optimization
✅ Immediate upload when ready
✅ Progress tracking
✅ Local file processing
✅ Single-sticker import via /loadsticker

**Requirements:**
• Bot must be authorized in groups (owner only)
• FFmpeg installed for animated stickers
• 'stickers' folder for local processing

**Owner Commands (Private only):**
• `/auth <chat_id>` - Authorize a group
• `/deauth <chat_id>` - Remove authorization
• `/listauth` - List authorized groups
"""
    await message.reply_text(instructions)

@app.on_message(filters.command("help") & (filters.group | filters.private))
async def help_command(client: Client, message: Message):
    """Shows how to import stickers to WhatsApp."""
    help_text = """
**how to import stickers to whatsapp**

download the app using the button below
click on the wasticker file
tap "import to whatsapp"

if you're on ios, well fuck you and just tap the .wastickers file and pray to god.
"""
    
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("download wasticker app (android)", url="https://play.google.com/store/apps/details?id=com.marsvard.stickermakerforwhatsapp")]
    ])
    
    await message.reply_text(help_text, reply_markup=keyboard)

@app.on_message(
    filters.command("loadsticker") & (filters.group | filters.private)
)
async def loadsticker_command(client: Client, message: Message):
    """Reply to a sticker to instantly convert it and send as a .wasticker file for import into WhatsApp."""
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return

    # Must reply to a sticker
    replied = message.reply_to_message
    if not replied or not replied.sticker:
        await message.reply_text(
            "❌ Please reply to a sticker with `/loadsticker` to import it to WhatsApp.\n\n"
            "**Usage:**\n"
            "• `/loadsticker` — import to default pack (My Stickers)\n"
            "• `/loadsticker MyPack` — import to a named pack\n\n"
            "The sticker will be sent as a .idwasticker file.\n"
            "Tap it to open in the Sticker Maker app — first time creates a new pack, "
            "next times add to the same pack automatically!"
        )
        return

    user_id = message.from_user.id
    sticker = replied.sticker
    is_animated = sticker.is_animated or sticker.is_video
    author_name = message.from_user.first_name or "Telegram User"

    # Parse custom pack name
    args = message.command[1:] if len(message.command) > 1 else []
    pack_title = " ".join(args) if args else "My Stickers"

    msg = await message.reply_text("📥 Downloading sticker...")

    try:
        # Download the sticker
        sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)

        if not sticker_data or len(sticker_data) == 0:
            await msg.edit_text("❌ Failed to download sticker.")
            return

        await msg.edit_text("🔄 Converting to WhatsApp format...")

        # Convert to WhatsApp-compatible WebP
        if is_animated:
            is_tgs = sticker.is_animated
            converted = await convert_to_whatsapp_animated(sticker_data, is_tgs)
        else:
            img = Image.open(BytesIO(sticker_data))
            converted = convert_to_whatsapp_static(img)

        converted_bytes = converted.getvalue()

        # Create tray icon from the sticker
        tray_bytes = optimize_tray_icon(converted_bytes, is_animated=False)

        await msg.edit_text("📦 Packaging .wasticker file...")

        # Build a single-sticker .wasticker ZIP in memory
        wasticker_bio = BytesIO()
        with zipfile.ZipFile(wasticker_bio, 'w', zipfile.ZIP_DEFLATED) as zipf:
            zipf.writestr('title.txt', pack_title)
            zipf.writestr('author.txt', author_name)
            zipf.writestr('tray.png', tray_bytes.getvalue())
            zipf.writestr('sticker_001.webp', converted_bytes)

        wasticker_bio.seek(0)
        wasticker_name = f"{sanitize_filename(pack_title)}.idwasticker"

        await msg.edit_text("Sending file...")

        # Send the .idwasticker file
        target_chat = message.chat.id
        caption = (
            f"**{pack_title}**\n"
            f"sticker ready to import\n\n"
        )

        await client.send_document(
            chat_id=target_chat,
            document=wasticker_bio,
            file_name=wasticker_name,
            caption=caption,
            #reply_markup=build_open_app_keyboard(),
            disable_notification=True
        )

        await msg.delete()

    except Exception as e:
        logger.error(f"Loadsticker failed for user {user_id}: {e}")
        logger.error(traceback.format_exc())
        await msg.edit_text(f"Failed to load sticker: `{str(e)}`")

@app.on_message(
    filters.command("converts") & (filters.group | filters.private)
)
async def converts_command(client: Client, message: Message):
    """Reply to a sticker to convert it and send it as a regular .webp file for manual use."""
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized.")
        return

    # Must reply to a sticker
    replied = message.reply_to_message
    if not replied or not replied.sticker:
        await message.reply_text(
            "❌ Please reply to a sticker with `/converts` to get the raw converted file.\n"
        )
        return

    user_id = message.from_user.id
    sticker = replied.sticker
    is_animated = sticker.is_animated or sticker.is_video

    msg = await message.reply_text("📥 Downloading sticker for conversion...")

    try:
        # Download the sticker
        sticker_data = await download_file_by_id(BOT_TOKEN, sticker.file_id)

        if not sticker_data or len(sticker_data) == 0:
            await msg.edit_text("❌ Failed to download sticker.")
            return

        await msg.edit_text("🔄 Converting sticker to WebP format...")

        # Convert to WhatsApp-compatible WebP
        if is_animated:
            is_tgs = sticker.is_animated
            converted = await convert_to_whatsapp_animated(sticker_data, is_tgs)
        else:
            img = Image.open(BytesIO(sticker_data))
            converted = convert_to_whatsapp_static(img)

        converted_bytes = converted.getvalue()

        # Send the file
        await msg.edit_text("Sending file...")
        
        target_chat = message.chat.id
        
        file_bio = BytesIO(converted_bytes)
        file_bio.name = "converted_sticker.webp"

        await client.send_document(
            chat_id=target_chat,
            document=file_bio,
            caption="Here is your converted sticker file.",
            disable_notification=True
        )

        await msg.delete()

    except Exception as e:
        logger.error(f"Converts failed for user {user_id}: {e}")
        logger.error(traceback.format_exc())
        await msg.edit_text(f"Failed to convert sticker: `{str(e)}`")

active_sticker_sessions = {}

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
        set_name_sanitized = sanitize_filename(set_title)

        if not stickers:
            await msg.edit_text("No stickers provided.")
            return

        # Use first sticker for tray icon (any type)
        first_sticker = stickers[0]
        tray_file_id = first_sticker.file_id
        tray_is_animated = first_sticker.is_animated or first_sticker.is_video
        
        tray_data = await download_file_by_id(BOT_TOKEN, tray_file_id)
        optimized_tray_bytes = optimize_tray_icon(tray_data, is_animated=tray_is_animated)

        # Handle simple ZIP mode (-z flag)
        if use_simple_zip:
            await msg.edit_text(f"Creating {'raw' if skip_conversion else 'converted'} ZIP with {len(stickers)} stickers...")
            
            # Progress callback
            async def update_progress(current, total):
                if current % 5 != 0 and current != total:
                    return
                
                progress_bar = "█" * int(current / total * 20) + "░" * (20 - int(current / total * 20))
                percentage = int(current / total * 100)
                action = "Downloading" if skip_conversion else "Converting"
                progress_text = (
                    f"{action} stickers\n\n"
                    f"{progress_bar} {percentage}%\n"
                    f"Sticker {current}/{total}"
                )
                try:
                    await msg.edit_text(progress_text)
                except Exception:
                    pass
            
            zip_path, valid_count = await create_simple_zip(
                set_name_sanitized,
                stickers,
                convert=not skip_conversion,
                progress_callback=update_progress
            )
            
            # Upload the ZIP
            await msg.edit_text("Uploading ZIP file...")
            
            mode_desc = "Raw stickers" if skip_conversion else "Converted stickers"
            caption = f"{mode_desc}: {valid_count} files\n{set_title}"
            
            await client.send_document(
                chat_id=target_chat,
                document=str(zip_path),
                file_name=zip_path.name,
                caption=caption,
                disable_notification=True
            )
            
            # Clean up just the zip we created
            try:
                if zip_path.exists():
                    zip_path.unlink()
            except Exception as cleanup_error:
                logger.warning(f"Cleanup error: {cleanup_error}")
            
            if send_to_private and target_chat == from_user_id:
                try:
                    await msg.reply_text("ZIP file sent to your private chat!")
                except Exception:
                    pass
            else:
                try:
                    await msg.reply_text(f"Successfully zipped {valid_count} stickers!")
                except Exception:
                    pass
            
            return
        
        # unified pack mode
        has_animated = any(s.is_animated or s.is_video for s in stickers)
        type_name = "Animated" if has_animated else "Static"

        await msg.edit_text(f"Processing {len(stickers)} stickers as {type_name} pack...")

        types_to_process = [(type_name, stickers)]

        zip_files_info = []
        total_valid_stickers_count = 0

        for type_name, type_stickers in types_to_process:
            type_chunks = split_into_chunks(type_stickers)
            num_type_parts = len(type_chunks)

            for part_num, chunk in enumerate(type_chunks, 1):
                part_title = set_title + (f" {part_num}" if num_type_parts > 1 else "")
                type_letter = type_name[0].lower()
                internal_name = f"{set_name_sanitized}_{type_letter}_part_{part_num}"
                
                async def update_progress(current, total):
                    if current % 5 != 0 and current != total:
                        return
                    
                    progress_bar = "█" * int(current / total * 20) + "░" * (20 - int(current / total * 20))
                    percentage = int(current / total * 100)
                    progress_text = (
                        f"Processing **{type_name}** stickers" +
                        (f" (Part {part_num}/{num_type_parts})" if num_type_parts > 1 else "") +
                        f"\n\n{progress_bar} {percentage}%\n" +
                        f"Sticker {current}/{total}"
                    )
                    logger.info(f"Progress: {type_name} - {current}/{total} ({percentage}%)")
                    try:
                        await msg.edit_text(progress_text)
                    except Exception:
                        pass

                zip_path, valid_count, stats = await create_wastickers_zip(
                    internal_name,
                    optimized_tray_bytes,
                    chunk,
                    part_title,
                    author=author_name,
                    progress_callback=update_progress
                )

                part_suffix = f" (Part {part_num}/{num_type_parts})" if num_type_parts > 1 else ""

                # WhatsApp requires 3–30 stickers per pack. Skip packs that are too small.
                if valid_count < 3:
                    logger.warning(
                        f"Pack '{type_name}{part_suffix}' has only {valid_count} valid sticker(s) "
                        f"— WhatsApp requires ≥3. Skipping this pack."
                    )
                    try:
                        await msg.edit_text(
                            f"⚠️ Skipped {type_name} pack: only {valid_count} valid sticker(s) "
                            f"(WhatsApp requires ≥3 per pack)."
                        )
                    except Exception:
                        pass
                    if zip_path.exists():
                        zip_path.unlink(missing_ok=True)
                    continue

                total_valid_stickers_count += valid_count
                
                caption = f"{type_name} Stickers{part_suffix}: {valid_count} stickers"
                if stats['skipped'] > 0:
                    caption += f"\nSkipped {stats['skipped']} invalid:"
                    if stats['corrupt'] > 0:
                        caption += f" {stats['corrupt']} corrupt"
                    if stats['empty'] > 0:
                        caption += f" {stats['empty']} empty"
                    if stats['invalid'] > 0:
                        caption += f" {stats['invalid']} invalid"
                if stats['warnings'] > 0:
                    caption += f"\n{stats['warnings']} warning(s) logged"
                
                try:
                    await msg.edit_text(f"Uploading {type_name}{part_suffix}...")
                except Exception:
                    pass
                
                await client.send_document(
                    chat_id=target_chat,
                    document=str(zip_path),
                    file_name=zip_path.name,
                    caption=caption,
                    #reply_markup=build_open_app_keyboard(),
                    disable_notification=True
                )
                
                zip_files_info.append((zip_path, internal_name))
                await asyncio.sleep(1)


        try:
            for zp, _ in zip_files_info:
                if zp.exists():
                    zp.unlink()
        except Exception as cleanup_error:
            logger.warning(f"Cleanup error: {cleanup_error}")

        if send_to_private and target_chat == from_user_id:
            try:
                await msg.reply_text(
                    "📩 All sticker packs have been sent to your private chat!"
                )
            except Exception:
                pass
        else:
            try:
                await msg.reply_text(
                    f"✅ Successfully sent {total_valid_stickers_count} stickers!"
                )
            except Exception:
                pass

    except Exception as e:
        logger.error(f"Pack conversion failed in chat {target_chat} for pack {pack_name}: {e}")
        logger.error(traceback.format_exc())
        error_message = str(e).lower()
        if "bot was kicked" in error_message:
            await msg.edit_text("I've been kicked from the group. Please re-add me!")
        elif "not enough rights" in error_message or "chat write forbidden" in error_message:
            await msg.edit_text("I need permissions to send messages and files in this group!")
        elif "chat not found" in error_message:
            await msg.edit_text("I cannot access this group. Please ensure I'm added and have permissions!")
        elif "flood control" in error_message:
            await msg.edit_text("Rate limit exceeded. Please try again later.")
        else:
            await msg.edit_text(f"An unexpected error occurred: `{str(e)}`")

@app.on_message(
    filters.command("wast") & (filters.group | filters.private)
)
async def convert_pack(client: Client, message: Message):
    """Converts a Telegram sticker pack to WhatsApp compatible ZIP files."""
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("This chat is not authorized. Contact the bot owner to authorize it with `/auth <chat_id>` in private.")
        return

    # Parse flags and custom name
    args = message.command[1:] if len(message.command) > 1 else []
    flags = [arg for arg in args if arg.startswith('-')]
    name_parts = [arg for arg in args if not arg.startswith('-')]
    
    use_simple_zip = '-z' in flags
    skip_conversion = '-c' in flags
    is_session_mode = '-s' in flags
    
    custom_name = " ".join(name_parts) if name_parts else None
    
    # Validate flag combination
    if skip_conversion and not use_simple_zip:
        await message.reply_text("Flag `-c` (no conversion) requires `-z` flag.\nUsage: `/wast -z -c` or `/wast -z`")
        return

    if is_session_mode:
        session_key = (message.chat.id, message.from_user.id)
        keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("Make Sticker Pack", callback_data="make_pack")]
        ])
        
        mode_text = ""
        if use_simple_zip and skip_conversion:
            mode_text = " (Raw ZIP mode)"
        elif use_simple_zip:
            mode_text = " (Converted ZIP mode)"
            
        name_text = f" as **{custom_name}**" if custom_name else ""
            
        msg = await message.reply_text(
            f"⏳ Send stickers one by one to create a pack{name_text}{mode_text}.\n\nStickers added: 0",
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
            "from_user": message.from_user
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
    
    mode_text = ""
    if use_simple_zip and skip_conversion:
        mode_text = " (Raw ZIP mode)"
    elif use_simple_zip:
        mode_text = " (Converted ZIP mode)"
    
    msg = await message.reply_text(f"📦 Processing pack: **{pack_name}**{mode_text}...")

    try:
        sticker_set = await get_sticker_set_via_bot_api(BOT_TOKEN, pack_name)
        set_title = custom_name if custom_name else sticker_set["title"]

        class SimpleSticker:
            def __init__(self, file_id, is_animated, is_video, emoji):
                self.file_id = file_id
                self.is_animated = is_animated
                self.is_video = is_video
                self.emoji = emoji

        # Create sticker list and deduplicate by file_id
        seen_ids = set()
        stickers = []
        for s in sticker_set["stickers"]:
            fid = s["file_id"]
            if fid not in seen_ids:
                seen_ids.add(fid)
                stickers.append(SimpleSticker(
                    fid,
                    s.get("is_animated", False),
                    s.get("is_video", False),
                    s.get("emoji", "😀")
                ))
        
        if len(seen_ids) < len(sticker_set["stickers"]):
            logger.warning(f"Removed {len(sticker_set['stickers']) - len(seen_ids)} duplicate stickers from pack")
        
        send_to_private = "private" in message.text.lower() if message.text else False
        target_chat = message.from_user.id if send_to_private and message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] else message.chat.id

        await process_stickers(
            client=client,
            msg=msg,
            message_text=message.text,
            target_chat=target_chat,
            set_title=set_title,
            stickers=stickers,
            use_simple_zip=use_simple_zip,
            skip_conversion=skip_conversion,
            author_name=message.from_user.first_name or "Telegram User",
            pack_name=pack_name,
            send_to_private=send_to_private,
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
    if session_key in active_sticker_sessions:
        session = active_sticker_sessions[session_key]
        
        class SimpleSticker:
            def __init__(self, file_id, is_animated, is_video, emoji):
                self.file_id = file_id
                self.is_animated = is_animated
                self.is_video = is_video
                self.emoji = emoji

        session["stickers"].append(SimpleSticker(
            message.sticker.file_id,
            message.sticker.is_animated,
            message.sticker.is_video,
            message.sticker.emoji or "😀"
        ))
        
        keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("Make Sticker Pack", callback_data="make_pack")]
        ])
        
        try:
            await client.edit_message_text(
                chat_id=session["chat_id"],
                message_id=session["message_id"],
                text=f"⏳ Send stickers one by one.\n\nStickers added: {len(session['stickers'])}",
                reply_markup=keyboard
            )
        except Exception:
            pass
        
        try:
            # Optionally delete the user's sticker message to keep chat clean. 
            # Commenting out for now, to keep behavior transparent
            # await message.delete()
            pass
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
            client=client,
            msg=msg,
            message_text=message_text,
            target_chat=target_chat,
            set_title=set_title,
            stickers=stickers_list,
            use_simple_zip=session["use_simple_zip"],
            skip_conversion=session["skip_conversion"],
            author_name=from_user.first_name or "Telegram User",
            pack_name=set_title,
            send_to_private=send_to_private,
            from_user_id=from_user.id
        )
    finally:
        try:
            await msg.delete()
        except Exception:
            pass

@app.on_message(filters.command("upload") & (filters.group | filters.private))
async def upload_wasticker_files(client: Client, message: Message):
    """Uploads all .wasticker files from the wasticker_packs directory to Telegram."""
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized. Contact the bot owner to authorize it with `/auth <chat_id>` in private.")
        return

    # Find all .wasticker files in wasticker_packs directory
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
            await asyncio.sleep(1)  # Rate limiting
            
        except Exception as e:
            logger.error(f"Failed to upload {wasticker_file.name}: {e}")
            continue

    # Clean up wasticker_packs folder after successful upload
    cleanup_success = False
    try:
        if packs_dir.exists():
            shutil.rmtree(packs_dir, ignore_errors=True)
            cleanup_success = True
            logger.info("Cleaned up wasticker_packs folder after upload.")
    except Exception as cleanup_error:
        logger.warning(f"Cleanup error: {cleanup_error}")
    
    success_msg = f"✅ Uploaded {uploaded_count}/{len(wasticker_files)} sticker packs!"
    if cleanup_success:
        success_msg += "\n🗑️ Cleaned up temporary files."
    
    if send_to_private and target_chat == message.from_user.id:
        await message.reply_text(success_msg + "\n(Sent to private chat)")
    else:
        await message.reply_text(success_msg)
    
    try:
        await msg.delete()
    except Exception:
        pass

@app.on_message(filters.command("local") & (filters.group | filters.private))
async def process_local_folder(client: Client, message: Message):
    """Processes local sticker files from a 'stickers' folder in current directory."""
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized. Contact the bot owner to authorize it with `/auth <chat_id>` in private.")
        return

    stickers_dir = Path("stickers")
    if not stickers_dir.exists():
        await message.reply_text("❌ No 'stickers' folder found in current directory. Create a 'stickers' folder and put your .webm/.tgs files there.")
        return

    # Find all sticker files
    sticker_files = []
    for ext in ['*.webm', '*.tgs', '*.png', '*.jpg', '*.jpeg', '*.webp']:
        sticker_files.extend(stickers_dir.glob(ext))

    if not sticker_files:
        await message.reply_text("❌ No sticker files found in 'stickers' folder. Supported formats: .webm, .tgs, .png, .jpg, .jpeg, .webp")
        return

    msg = await message.reply_text(f"📦 Processing {len(sticker_files)} local sticker files...")

    try:
        static_files = []
        animated_files = []
        
        for f in sticker_files:
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
                except:
                    static_files.append(f)
            else:
                static_files.append(f)

        types_to_process = []
        if static_files:
            types_to_process.append(("Static", static_files))
        if animated_files:
            types_to_process.append(("Animated", animated_files))

        total_processed = 0
        zip_paths = []

        for type_name, files in types_to_process:
            chunks = split_into_chunks(files, 30)
            num_parts = len(chunks)
            
            for part_num, chunk in enumerate(chunks, 1):
                type_letter = type_name[0].lower()
                processing_dir = Path(f"processed_{type_letter}_{part_num}")
                processing_dir.mkdir(exist_ok=True)
                
                processed_count = 0
                for i, sticker_file in enumerate(chunk, 1):
                    try:
                        part_suffix = f" (Part {part_num}/{num_parts})" if num_parts > 1 else ""
                        try:
                            await msg.edit_text(f"📦 Processing {type_name}{part_suffix} file {i}/{len(chunk)}: {sticker_file.name}")
                        except Exception:
                            pass

                        # Read file
                        with open(sticker_file, 'rb') as f:
                            file_data = f.read()

                        is_tgs = sticker_file.suffix.lower() == '.tgs'

                        # Convert
                        if sticker_file in animated_files:
                            converted = await convert_to_whatsapp_animated(file_data, is_tgs)
                        else:
                            img = Image.open(BytesIO(file_data))
                            converted = convert_to_whatsapp_static(img)

                        # Save to processed directory
                        output_name = f"{sticker_file.stem}_whatsapp.webp"
                        output_path = processing_dir / output_name
                        with open(output_path, 'wb') as f:
                            f.write(converted.getvalue())

                        processed_count += 1
                        logger.info(f"Processed {sticker_file.name} -> {output_name}")

                    except Exception as e:
                        logger.error(f"Error processing {sticker_file.name}: {e}")
                        continue

                if processed_count == 0:
                    shutil.rmtree(processing_dir, ignore_errors=True)
                    continue

                # Create tray icon
                tray_path = processing_dir / "tray.png"
                try:
                    first_file = chunk[0]
                    with open(first_file, 'rb') as f:
                        tray_data = f.read()
                    is_animated = first_file in animated_files
                    optimized_tray = optimize_tray_icon(tray_data, is_animated=is_animated)
                    with open(tray_path, 'wb') as f:
                        f.write(optimized_tray.getvalue())
                except Exception as e:
                    logger.warning(f"Could not create tray icon: {e}")

                # Create ZIP file
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

                # Clean up processing dir
                shutil.rmtree(processing_dir, ignore_errors=True)

        if total_processed > 0:
            await msg.edit_text(f"✅ Processed {total_processed} stickers! ZIP files created: {', '.join(zip_paths)}")
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
    """Processes an uploaded ZIP file containing stickers."""
    if message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP] and message.chat.id not in AUTHORIZED_CHATS:
        await message.reply_text("❌ This chat is not authorized. Contact the bot owner to authorize it with `/auth <chat_id>` in private.")
        return

    if not message.document.file_name or not message.document.file_name.lower().endswith('.zip'):
        return

    msg = await message.reply_text("📥 Downloading ZIP file...")

    try:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            
            # Download file
            zip_path = await message.download(file_name=str(tmp_path / message.document.file_name))
            
            await msg.edit_text("📦 Extracting ZIP file...")
            
            extract_dir = tmp_path / "extracted"
            extract_dir.mkdir()
            
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_dir)
            
            # Find all sticker files
            sticker_files = []
            for ext in ['*.webm', '*.tgs', '*.png', '*.jpg', '*.jpeg', '*.webp']:
                sticker_files.extend(extract_dir.rglob(ext))

            if not sticker_files:
                await msg.edit_text("❌ No supported sticker files found in the ZIP. Supported formats: .webm, .tgs, .png, .jpg, .jpeg, .webp")
                return

            static_files = []
            animated_files = []
            
            for f in sticker_files:
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
                    except:
                        static_files.append(f)
                else:
                    static_files.append(f)

            types_to_process = []
            if static_files:
                types_to_process.append(("Static", static_files))
            if animated_files:
                types_to_process.append(("Animated", animated_files))

            pack_name_base = message.document.file_name[:-4]  # remove .zip
            author_name = message.from_user.first_name or "Telegram User"
            total_packs_sent = 0
            # Initialise here so the post-loop check is never unbound
            # even when types_to_process is empty or no iterations complete.
            send_to_private = bool(message.caption and "private" in message.caption.lower())
            target_chat = (
                message.from_user.id
                if send_to_private and message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP]
                else message.chat.id
            )

            for type_name, files in types_to_process:
                chunks = split_into_chunks(files, 30)
                num_parts = len(chunks)
                
                for part_num, chunk in enumerate(chunks, 1):
                    type_letter = type_name[0].lower()
                    part_title = pack_name_base + (f" - Part {part_num}" if num_parts > 1 else "")
                    
                    processing_dir = tmp_path / f"processed_{type_letter}_{part_num}"
                    processing_dir.mkdir()

                    processed_count = 0
                    for i, sticker_file in enumerate(chunk, 1):
                        try:
                            if i % 5 == 0 or i == len(chunk):
                                try:
                                    part_suffix = f" (Part {part_num}/{num_parts})" if num_parts > 1 else ""
                                    await msg.edit_text(f"📦 Processing {type_name}{part_suffix} file {i}/{len(chunk)}: {sticker_file.name}")
                                except Exception:
                                    pass
                            
                            with open(sticker_file, 'rb') as f:
                                file_data = f.read()

                            is_tgs = sticker_file.suffix.lower() == '.tgs'
                            
                            if sticker_file in animated_files:
                                converted = await convert_to_whatsapp_animated(file_data, is_tgs)
                            else:
                                img = Image.open(BytesIO(file_data))
                                converted = convert_to_whatsapp_static(img)

                            output_name = f"{sticker_file.stem}_whatsapp.webp"
                            output_path = processing_dir / output_name
                            with open(output_path, 'wb') as f:
                                f.write(converted.getvalue())
                            
                            processed_count += 1
                        except Exception as e:
                            logger.error(f"Error processing {sticker_file.name}: {e}")
                            continue

                    if processed_count == 0:
                        continue

                    # Tray icon
                    tray_path = processing_dir / "tray.png"
                    try:
                        first_file = chunk[0]
                        with open(first_file, 'rb') as f:
                            tray_data = f.read()
                        is_animated = first_file in animated_files
                        optimized_tray = optimize_tray_icon(tray_data, is_animated=is_animated)
                        with open(tray_path, 'wb') as f:
                            f.write(optimized_tray.getvalue())
                    except Exception as e:
                        logger.warning(f"Could not create tray icon: {e}")

                    # ZIP up to .wasticker
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

                    send_to_private = bool(message.caption and "private" in message.caption.lower())
                    target_chat = (
                        message.from_user.id
                        if send_to_private and message.chat.type in [ChatType.GROUP, ChatType.SUPERGROUP]
                        else message.chat.id
                    )

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

            if send_to_private and target_chat == message.from_user.id:
                try:
                    await message.reply_text("📩 ZIP processing complete. Packs sent to your private chat!")
                except Exception:
                    pass
            else:
                try:
                    await msg.delete()
                except Exception:
                    pass

            if total_packs_sent == 0:
                await msg.reply_text("❌ Failed to process any sticker packs from the ZIP.")

    except Exception as e:
        logger.error(f"ZIP processing failed: {e}")
        try:
            await msg.edit_text(f"❌ Processing failed: {str(e)}")
        except Exception:
            pass

if __name__ == "__main__":
    logger.info("Starting Sticker Pack Bot...")
    logger.info(f"Loaded {len(AUTHORIZED_CHATS)} authorized chats.")
    app.run()