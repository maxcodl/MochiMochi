"""ZIP and .wasticker package assembly module."""

import asyncio
import json
import logging
import shutil
import uuid
import zipfile
import hashlib
from io import BytesIO
from pathlib import Path

from PIL import Image

from config import BASE_DIR, STICKER_THUMB_QUALITY, STICKER_THUMB_SIZE, WA_MAX_BYTES
from conversion.animated import _count_webp_frames, _is_animated_webp_bytes, is_valid_webp_output
from conversion.static import (
    _convert_static_bytes_to_webp,
    convert_to_whatsapp_one_frame_animation,
    verify_sticker,
)
from conversion.tgs import convert_to_whatsapp_animated
from resources import resources
from telegram_api import download_file_by_id

logger = logging.getLogger(__name__)


def generate_telegram_identifier(set_name: str) -> str:
    clean = set_name.strip().lower() if set_name else ""
    if not clean:
        return uuid.uuid4().hex[:16]
    return hashlib.sha1(f"telegram:{clean}".encode('utf-8')).hexdigest()[:16]


def generate_thumbnail_from_webp_bytes(
    webp_bytes: bytes, thumb_size: int = STICKER_THUMB_SIZE
) -> bytes:
    """Builds a small static WebP thumbnail from converted sticker bytes."""
    with Image.open(BytesIO(webp_bytes)) as img:
        try:
            img.seek(0)
        except Exception:
            pass
        img_rgba = img.convert("RGBA")
        frame = img_rgba.copy()
        img_rgba.close()
        frame.thumbnail((thumb_size, thumb_size), Image.LANCZOS)

        canvas = Image.new("RGBA", (thumb_size, thumb_size), (0, 0, 0, 0))
        position = ((thumb_size - frame.width) // 2, (thumb_size - frame.height) // 2)
        canvas.paste(frame, position, frame)
        frame.close()

        out = BytesIO()
        canvas.save(out, format="WEBP", quality=STICKER_THUMB_QUALITY, method=4)
        canvas.close()
        val = out.getvalue()
        out.close()
        return val


def _build_contents_json(identifier: str, name: str, publisher: str, emoji_map: dict, animated: bool, telegram_set_name: str = "") -> dict:
    stickers_array = [
        {"image_file": fname, "emojis": emojis if emojis else ["😊"]}
        for fname, emojis in emoji_map.items()
    ]
    pack = {
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
    }
    if telegram_set_name:
        pack["telegram_set_name"] = telegram_set_name.strip().lower()
    return {
        "android_play_store_link": "",
        "ios_app_store_link": "",
        "sticker_packs": [pack],
    }


async def create_simple_zip(
    bot_token: str,
    set_name: str,
    stickers: list,
    convert: bool = True,
    progress_callback=None
) -> tuple[Path, int]:
    logger.info(f"Creating simple ZIP for: {set_name} (convert={convert})")
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)

    work_dir = packs_dir / f"simple_{set_name}_{uuid.uuid4().hex[:8]}"
    work_dir.mkdir(exist_ok=True)

    valid_count = 0
    total = len(stickers)
    stats = {'skipped': 0, 'skipped_reasons': []}
    sem = resources.get_cpu_semaphore()

    async def _process_one(i: int, sticker):
        async with sem:
            try:
                sticker_data = await download_file_by_id(bot_token, sticker.file_id)
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
                        converted = await resources.run_cpu_bound(_convert_static_bytes_to_webp, sticker_data)
                    converted_bytes = converted.getvalue()
                    converted.close()
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
    bot_token: str,
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

        sem = resources.get_cpu_semaphore()

        async def process_one_sticker(i, sticker):
            async with sem:
                try:
                    sticker_data = await download_file_by_id(bot_token, sticker.file_id)
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
                            animated_out.close()
                            if not _is_animated_webp_bytes(converted_bytes) or _count_webp_frames(converted_bytes) < 2:
                                logger.warning(f"Sticker {i}: animated conversion produced <2 frames — wrapping as 1-frame animation")
                                try:
                                    with Image.open(BytesIO(converted_bytes)) as img:
                                        fallback_out = await resources.run_cpu_bound(convert_to_whatsapp_one_frame_animation, img)
                                        converted_bytes = fallback_out.getvalue()
                                        fallback_out.close()
                                except Exception as fe:
                                    return {'index': i, 'ok': False, 'reason': f'conversion produced static and 1-frame fallback failed: {fe}', 'kind': 'invalid', 'warnings': verification['warnings']}
                        except Exception:
                            return {'index': i, 'ok': False, 'reason': f"{'TGS' if sticker.is_animated else 'video'} conversion failed", 'kind': 'invalid', 'warnings': verification['warnings']}

                    elif should_be_animated:
                        logger.info(f"Sticker {i}/{total}: static sticker in animated pack — converting to 1-frame animation")
                        try:
                            with Image.open(BytesIO(sticker_data)) as img:
                                fallback_out = await resources.run_cpu_bound(convert_to_whatsapp_one_frame_animation, img)
                                converted_bytes = fallback_out.getvalue()
                                fallback_out.close()
                        except Exception as fe:
                            return {'index': i, 'ok': False, 'reason': f'static-to-animated fallback failed: {fe}', 'kind': 'invalid', 'warnings': verification['warnings']}

                    else:
                        try:
                            converted = await resources.run_cpu_bound(_convert_static_bytes_to_webp, sticker_data)
                            converted_bytes = converted.getvalue()
                            converted.close()
                        except Exception:
                            return {'index': i, 'ok': False, 'reason': 'static conversion failed', 'kind': 'invalid', 'warnings': verification['warnings']}

                    if should_be_animated and (not _is_animated_webp_bytes(converted_bytes) or _count_webp_frames(converted_bytes) < 2):
                        logger.warning(f"Sticker {i}: output has <2 frames before validation — applying 1-frame animation wrapper")
                        try:
                            with Image.open(BytesIO(converted_bytes)) as img:
                                fallback_out = await resources.run_cpu_bound(convert_to_whatsapp_one_frame_animation, img)
                                converted_bytes = fallback_out.getvalue()
                                fallback_out.close()
                        except Exception as fe:
                            return {'index': i, 'ok': False, 'reason': f'1-frame fallback failed: {fe}', 'kind': 'invalid', 'warnings': verification['warnings']}

                    if len(converted_bytes) > WA_MAX_BYTES:
                        return {'index': i, 'ok': False, 'reason': f"oversized after conversion ({len(converted_bytes) // 1024}KB)", 'kind': 'invalid', 'warnings': verification['warnings']}

                    is_valid, validation_reason = is_valid_webp_output(converted_bytes, require_animated=should_be_animated)
                    if not is_valid:
                        return {'index': i, 'ok': False, 'reason': validation_reason, 'kind': 'invalid', 'warnings': verification['warnings']}

                    emoji_list = sticker.emojis[:3] if sticker.emojis else ["\U0001F600"]
                    return {'index': i, 'ok': True, 'bytes': converted_bytes, 'file_id': sticker.file_id, 'emoji_list': emoji_list, 'warnings': verification['warnings']}

                except Exception as e:
                    logger.error(f"Error processing sticker {i} in pack {set_name}: {e}")
                    return {'index': i, 'ok': False, 'reason': 'unexpected processing error', 'kind': 'invalid', 'warnings': []}

        tasks = [asyncio.create_task(process_one_sticker(i, sticker)) for i, sticker in enumerate(stickers, 1)]
        results = []
        completed = 0
        for fut in asyncio.as_completed(tasks):
            res = await fut
            results.append(res)
            completed += 1
            if progress_callback:
                await progress_callback(completed, total)

        for res in sorted(results, key=lambda r: r['index']):
            if res['warnings']:
                stats['warnings'] += len(res['warnings'])
                for warning in res['warnings']:
                    logger.info(f"⚠️ Sticker {res['index']}: {warning}")

            if not res['ok']:
                stats['skipped'] += 1
                stats['skipped_reasons'].append(f"Sticker {res['index']}: {res['reason']}")
                kind = res.get('kind', 'invalid')
                if kind == 'corrupt':
                    stats['corrupt'] += 1
                elif kind == 'empty':
                    stats['empty'] += 1
                else:
                    stats['invalid'] += 1
                logger.warning(f"❌ Skipping sticker {res['index']}/{total}: {res['reason']}")
                continue

            sticker_filename = f"{set_name}_{res['file_id'][-12:]}.webp"
            sticker_path = work_dir / sticker_filename
            with open(sticker_path, 'wb') as f:
                f.write(res['bytes'])

            try:
                thumb_bytes = generate_thumbnail_from_webp_bytes(res['bytes'])
                thumbs_dir = work_dir / "thumbnails"
                thumbs_dir.mkdir(exist_ok=True)
                with open(thumbs_dir / f"thumb_{sticker_filename}", 'wb') as f:
                    f.write(thumb_bytes)
            except Exception as e:
                logger.warning(f"Failed to generate thumbnail for {sticker_filename}: {e}")

            emoji_map[sticker_filename] = res['emoji_list']
            valid_entries.append({
                'file_id': res['file_id'],
                'bytes': res['bytes'],
                'emoji_list': res['emoji_list'],
            })
            valid_count += 1

        if return_valid_results:
            return valid_entries, stats

        if valid_count == 0:
            raise ValueError("No valid stickers found in the pack.")
        if valid_count < 3:
            raise ValueError(f"Pack has only {valid_count} stickers. WhatsApp requires minimum 3 stickers per pack.")

        emojis_json_path = work_dir / "emojis.json"
        with open(emojis_json_path, 'w', encoding='utf-8') as f:
            json.dump(emoji_map, f, ensure_ascii=False, indent=2)

        pack_identifier = generate_telegram_identifier(set_name)
        contents_json = _build_contents_json(pack_identifier, title, author, emoji_map, should_be_animated, telegram_set_name=set_name)
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
    set_name: str, tray_bytes: BytesIO, valid_entries: list, title: str, author: str, animated_sticker_pack: bool
) -> Path:
    packs_dir = BASE_DIR / "wasticker_packs"
    packs_dir.mkdir(exist_ok=True)

    filtered_entries = [e for e in valid_entries if len(e['bytes']) <= WA_MAX_BYTES]
    if len(filtered_entries) < 3:
        raise ValueError("Pack has fewer than 3 valid stickers after size enforcement.")

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

        pack_identifier = generate_telegram_identifier(set_name)
        contents_json = _build_contents_json(pack_identifier, title, author, emoji_map, animated_sticker_pack, telegram_set_name=set_name)
        contents_json_path = work_dir / "contents.json"
        with open(contents_json_path, 'w', encoding='utf-8') as f:
            json.dump(contents_json, f, ensure_ascii=False, indent=2)

        zip_path = packs_dir / f"{set_name}.wasticker"
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for file_path in work_dir.iterdir():
                if file_path.is_dir():
                    for sub_file in file_path.iterdir():
                        zipf.write(sub_file, f"{file_path.name}/{sub_file.name}")
                else:
                    zipf.write(file_path, file_path.name)
        return zip_path
    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)
