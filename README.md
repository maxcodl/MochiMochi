# MochiMochi

![Works with Android](https://img.shields.io/badge/Works_with-Android-green?style=flat-square) ![GitHub release](https://img.shields.io/github/v/release/maxcodl/MochiMochi-?style=flat-square)

Convert Telegram sticker packs to WhatsApp format and send them directly to WhatsApp.


 <img src="https://github.com/user-attachments/assets/4bc78d5c-29e0-431c-acac-41c42b667565" width="300">    <img src="https://github.com/user-attachments/assets/eb47af3e-8e22-4c77-a83e-91ca9765e386" width="300"> 
## Features

- Convert Telegram sticker packs (static, animated TGS, and video WebM) to WhatsApp-compatible WebP format
- Send converted packs directly to WhatsApp via the Android app
- Supports packs of up to 30 stickers per pack
- Telegram bot (`tg-wa.py`) for automated conversion

## Requirements

- Android 5.0+
- WhatsApp installed

## Installation

Download the latest APK from the [Releases](https://github.com/maxcodl/MochiMochi-/releases) page and install it on your device.

## Bot Setup

1. Install dependencies:
   ```
   pip install -r requirements.txt
   ```
2. Create a `.env` file with your Telegram API credentials:
   ```
   API_ID=your_api_id
   API_HASH=your_api_hash
   BOT_TOKEN=your_bot_token
   ```
3. Run the bot:
   ```
   python tg-wa.py
   ```

## Building from source

```
cd Android
./gradlew assembleRelease
```

## License

[MIT](LICENSE)
