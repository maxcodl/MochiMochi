<div align="center">

# MochiMochi 🍡

**A cozy sticker manager for WhatsApp — import from Telegram in seconds.**

![Platform](https://img.shields.io/badge/platform-Android-green?style=flat-square)
![Min SDK](https://img.shields.io/badge/minSdk-21-blue?style=flat-square)
![License](https://img.shields.io/badge/license-BSD-orange?style=flat-square)

</div>

---

## ✨ Features

- **Import `.wasticker` packs** — tap any `.wasticker` file from Telegram, Files, or any other app and MochiMochi imports it instantly
- **Import straight from Telegram** — pick a Telegram sticker pack right from within the app, no separate tool needed
- **Animated & static stickers** — full support for animated WebP packs, with real frame-rate detection
- **Pack manager** — view, edit, delete, and reorder your sticker packs
- **Large packs, handled automatically** — packs over WhatsApp's 30-sticker limit are chunked and added seamlessly
- **Companion Telegram bot** — pair with `tg-wa.py` to batch-convert entire Telegram sticker packs from a chat
- **Pack details** — per-sticker preview, file size, frame count, FPS, and an animated/static badge
- **Material You** — dynamic Monet theming, with Light / Dark / AMOLED modes
- **No ads, no tracking** — everything runs locally, nothing leaves your device

---

## 📸 Screenshots

<div align="center">
<img src="https://github.com/user-attachments/assets/4bc78d5c-29e0-431c-acac-41c42b667565" width="300">&nbsp;&nbsp;&nbsp;
<img src="https://github.com/user-attachments/assets/eb47af3e-8e22-4c77-a83e-91ca9765e386" width="300">
</div>

---

## 📦 Installation

1. Clone the repo and open the `Android/` folder in **Android Studio**
2. Connect a device or start an emulator
3. Click **Run ▶** — that's it

> Requires Android 5.0+ (API 21). WhatsApp or WhatsApp Business must be installed on the device to add packs.

---

## 🗂 Project Structure

```
Android/
└── app/src/main/
    ├── java/com/kawai/mochi/
    │   ├── StickerPackListActivity.kt      # Home screen — list of all packs
    │   ├── StickerPackDetailsActivity.java  # Pack details + sticker grid
    │   ├── StickerPackInfoActivity.java     # Per-pack metadata + sticker list
    │   ├── EditStickerPackActivity.java     # Create / edit a pack
    │   ├── TelegramImportActivity.java      # In-app "import from Telegram" flow
    │   ├── SettingsActivity.java            # Theme, folder, diagnostics, about
    │   ├── EntryActivity.java               # Launch + deep-link handler
    │   ├── WastickerParser.java             # .wasticker import / export / save logic
    │   ├── StickerPackChunkManager.java     # Splits packs >30 stickers for WhatsApp
    │   ├── StickerPackLoader.kt             # Loads pack + sticker metadata for the UI
    │   ├── StickerUpdateManager.kt          # App-wide "packs changed" event bus
    │   ├── ThumbnailRegenerationManager.kt  # Background thumbnail rebuild + progress
    │   ├── StickerInfoAdapter.java          # Sticker list adapter (info page)
    │   ├── StickerContentProvider.java      # WhatsApp content provider
    │   └── BaseActivity.java                # Theme application base
    └── res/
        ├── layout/                          # Activity & item layouts
        └── values/                          # Strings, styles, colours
```

---

## 🤖 Telegram Bot (companion)

The repo also includes a Python Telegram bot (`tg-wa.py`) that:

- Accepts forwarded Telegram sticker packs
- Converts them to `.wasticker` format (static → WebP, animated → animated WebP)
- Sends the ready-to-import file straight back to you in chat

**Setup:**

```bash
cp .env.example .env   # fill in your bot token and API keys
pip install -r requirements.txt
python tg-wa.py
```

Conversion concurrency (download/render/encode workers) is configurable at runtime via the bot's `/settings` command — useful for tuning throughput to your hardware.

---

## 🎨 Theming

Three modes available in **Settings → Theme**:

| Mode           | Description                                   |
| -------------- | --------------------------------------------- |
| System Default | Follows Android's system dark/light setting   |
| Light          | Always light                                  |
| Dark           | Material dark surfaces                        |
| AMOLED Dark    | True black — battery friendly on OLED screens |

All modes use **Material You / Monet** dynamic colours where supported (Android 12+).

---

## 🙏 Acknowledgments

MochiMochi is built on top of [WhatsApp's official Stickers sample app](https://github.com/WhatsApp/stickers) (the content provider and pack-parsing logic in particular), used under its BSD license — see below.

---

## ❤️ Support

If MochiMochi saved you from manually screenshotting stickers like a caveman, consider supporting:

> **[Donate $69](https://maxcodl.github.io/)** — or send your kidney to Max ❤️

---

## 📄 License

BSD 3-Clause — see [LICENSE](LICENSE) for details.

---

<div align="center">
Made with ❤️ and too many stickers by <b>Max</b>
</div>