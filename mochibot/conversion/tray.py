"""Tray icon generation and optimization for WhatsApp sticker packs."""

import gzip
import logging
from io import BytesIO

import av
from PIL import Image

from .video import _frame_to_rgba_pil

logger = logging.getLogger(__name__)


def optimize_tray_icon(tray_data: bytes, is_animated: bool = False) -> BytesIO:
    """Optimizes tray icon image to 96x96 PNG under 50KB."""
    img = None
    try:
        if is_animated:
            is_tgs = len(tray_data) >= 2 and tray_data[:2] == b'\x1f\x8b'
            if is_tgs:
                try:
                    json_bytes = gzip.decompress(tray_data)
                    from lottie.exporters.cairo import export_png
                    from lottie.parsers.tgs import parse_tgs_json
                    anim = parse_tgs_json(BytesIO(json_bytes))
                    frame_buf = BytesIO()
                    export_png(anim, frame_buf, frame=0)
                    frame_buf.seek(0)
                    img = Image.open(frame_buf).copy()
                    frame_buf.close()
                    logger.info("Rendered TGS first frame via lottie/cairo for tray icon")
                except Exception as e:
                    logger.debug(f"lottie cairo render failed for tray icon: {e}")
                    img = None

            if img is None:
                # Try Pillow seek(0) first
                try:
                    tmp_img = Image.open(BytesIO(tray_data))
                    tmp_img.seek(0)
                    img = tmp_img.copy()
                    tmp_img.close()
                    logger.info("Opened animated sticker first frame via Pillow for tray icon")
                except Exception:
                    img = None

            if img is None:
                # PyAV extraction of frame 0 directly from video container (no ffmpeg CLI subprocess)
                try:
                    with av.open(BytesIO(tray_data)) as container:
                        if container.streams.video:
                            stream = container.streams.video[0]
                            decoder = None
                            if stream.codec_context.name == "vp9" and "libvpx-vp9" in av.codecs_available:
                                try:
                                    decoder = av.CodecContext.create("libvpx-vp9", "r")
                                    if stream.codec_context.extradata:
                                        decoder.extradata = stream.codec_context.extradata
                                except Exception:
                                    decoder = None

                            if decoder:
                                for packet in container.demux(stream):
                                    try:
                                        frames = decoder.decode(packet)
                                    except (av.error.EOFError, av.error.FFmpegError):
                                        break
                                    for frame in frames:
                                        img = _frame_to_rgba_pil(frame)
                                        break
                                    if img is not None:
                                        break
                            else:
                                for frame in container.decode(video=0):
                                    img = _frame_to_rgba_pil(frame)
                                    break
                            if img is not None:
                                logger.info("Successfully extracted first frame via PyAV for tray icon")
                except Exception as av_err:
                    logger.warning(f"PyAV frame 0 extraction failed for tray icon: {av_err}")
                    img = None
        else:
            with Image.open(BytesIO(tray_data)) as static_img:
                img = static_img.copy()

    except Exception as e:
        logger.warning(f"Tray icon conversion failed, using transparent placeholder: {e}")
        img = None

    if img is None:
        img = Image.new("RGBA", (96, 96), (0, 0, 0, 0))

    try:
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

        canvas.close()
        output.seek(0)
        return output
    finally:
        if img is not None:
            img.close()
