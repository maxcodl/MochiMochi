"""Bot application construction and handler binding."""

import logging
import os
import sys
from pathlib import Path

from dotenv import find_dotenv, load_dotenv
from pyrogram import Client

from config import BASE_DIR
from handlers.auth import register_auth_handlers
from handlers.local_folder import register_local_handlers
from handlers.settings import register_settings_handlers
from handlers.stickers import register_sticker_handlers
from handlers.zip_upload import register_zip_upload_handlers

logger = logging.getLogger(__name__)

PLACEHOLDER_VALUES = {
    "API_ID": {"your_api_id_here", "your_api_id", "0", ""},
    "API_HASH": {"your_api_hash_here", "your_api_hash", ""},
    "BOT_TOKEN": {"your_bot_token_here", "your_bot_token", ""},
}


def clean_env_var(val: str | None) -> str | None:
    """Strips whitespace and surrounding quotes."""
    if val is None:
        return None
    val = val.strip()
    if len(val) >= 2 and (
        (val.startswith('"') and val.endswith('"')) or
        (val.startswith("'") and val.endswith("'"))
    ):
        val = val[1:-1].strip()
    return val if val else None


def load_environment() -> list[Path]:
    """
    Searches for and loads .env from candidate paths:
    - Current working directory
    - mochibot/ directory
    - Project root directory (parent of mochibot/)
    - find_dotenv() discovery
    """
    candidate_paths: list[Path] = [
        Path.cwd() / ".env",
        BASE_DIR / ".env",
        BASE_DIR.parent / ".env",
    ]

    discovered = find_dotenv(usecwd=True)
    if discovered:
        candidate_paths.append(Path(discovered))

    loaded_paths: list[Path] = []
    seen: set[Path] = set()

    for p in candidate_paths:
        try:
            resolved = p.resolve()
        except Exception:
            continue
        if resolved not in seen and resolved.is_file():
            seen.add(resolved)
            load_dotenv(dotenv_path=resolved, override=False)
            loaded_paths.append(resolved)

    if loaded_paths:
        logger.info(f"Loaded environment file(s): {', '.join(str(p) for p in loaded_paths)}")
    else:
        load_dotenv()
        logger.warning(
            f"No .env file found in checked paths: {[str(p) for p in candidate_paths[:3]]}"
        )

    return loaded_paths


def create_bot() -> tuple[Client, str, int]:
    """Loads environment variables and constructs Kurigram Client."""
    load_environment()

    api_id_raw = clean_env_var(os.getenv("API_ID"))
    api_hash = clean_env_var(os.getenv("API_HASH"))
    bot_token = clean_env_var(os.getenv("BOT_TOKEN"))
    owner_id_raw = clean_env_var(os.getenv("OWNER_ID"))

    missing: list[str] = []
    invalid: list[str] = []

    # Check API_ID
    if not api_id_raw or api_id_raw in PLACEHOLDER_VALUES["API_ID"]:
        missing.append("API_ID (Telegram API ID from https://my.telegram.org/apps)")
    elif not (api_id_raw.isdigit() or (api_id_raw.startswith("-") and api_id_raw[1:].isdigit())):
        invalid.append(f"API_ID must be an integer, got: '{api_id_raw}'")

    # Check API_HASH
    if not api_hash or api_hash in PLACEHOLDER_VALUES["API_HASH"]:
        missing.append("API_HASH (Telegram API Hash from https://my.telegram.org/apps)")

    # Check BOT_TOKEN
    if not bot_token or bot_token in PLACEHOLDER_VALUES["BOT_TOKEN"]:
        missing.append("BOT_TOKEN (Telegram Bot Token from @BotFather)")

    if missing or invalid:
        if missing:
            logger.error("Missing required environment variable(s):\n  * " + "\n  * ".join(missing))
        if invalid:
            logger.error("Invalid environment variable value(s):\n  * " + "\n  * ".join(invalid))
        logger.info(
            "Please create a .env file with your credentials (see .env.example) in the project root or mochibot/ directory."
        )
        sys.exit(1)

    api_id = int(api_id_raw)  # type: ignore

    # Check OWNER_ID
    owner_id = 0
    if owner_id_raw and owner_id_raw not in ("0", "your_user_id_here"):
        try:
            owner_id = int(owner_id_raw)
        except ValueError:
            logger.warning(f"OWNER_ID '{owner_id_raw}' is not a valid integer. Authorization commands disabled.")
            owner_id = 0
    else:
        logger.warning("OWNER_ID not set in .env. Authorization commands will be disabled!")

    app = Client(
        "sticker_pack_bot",
        api_id=api_id,
        api_hash=api_hash,  # type: ignore
        bot_token=bot_token  # type: ignore
    )

    # Register handlers
    register_auth_handlers(app, owner_id)
    register_settings_handlers(app, owner_id)
    register_sticker_handlers(app, bot_token)  # type: ignore
    register_zip_upload_handlers(app)
    register_local_handlers(app)

    return app, bot_token, owner_id  # type: ignore

