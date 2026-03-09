<div align="center">

# MochiMochi ジ

**A cozy sticker manager for WhatsApp — import from Telegram in seconds.**

![Platform](https://img.shields.io/badge/platform-Android-green?style=flat-square)
![Min SDK](https://img.shields.io/badge/minSdk-21-blue?style=flat-square)
![License](https://img.shields.io/badge/license-BSD-orange?style=flat-square)

</div>

---

## ✨ Features

- **Import `.wasticker` packs** — tap any `.wasticker` file from Telegram, Files, or any other app and MochiMochi imports it instantly
- **Animated & static stickers** — full support for animated WebP packs (with real frame-rate detection)
- **Pack manager** — view, edit, delete, and reorder your sticker packs
- **Bot integration** — pair with the companion Telegram bot to convert entire Telegram sticker packs automatically
- **Pack details** — per-sticker preview, file size, frame count, FPS and animated/static badge
- **Material You** — dynamic Monet theming, Light / Dark / AMOLED modes
- **No ads, no tracking** — completely local, nothing leaves your device

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
    │   ├── StickerPackListActivity.java   # Home screen — list of all packs
    │   ├── StickerPackDetailsActivity.java # Pack details + sticker grid
    │   ├── StickerPackInfoActivity.java   # Per-pack metadata + sticker list
    │   ├── EditStickerPackActivity.java   # Create / edit a pack
    │   ├── SettingsActivity.java          # Theme, folder, diagnostics, about
    │   ├── EntryActivity.java             # Launch + deep-link handler
    │   ├── WastickerParser.java           # .wasticker import / export logic
    │   ├── StickerInfoAdapter.java        # Sticker list adapter (info page)
    │   ├── StickerContentProvider.java    # WhatsApp content provider
    │   └── BaseActivity.java             # Theme application base
    └── res/
        ├── layout/                        # Activity & item layouts
        └── values/                        # Strings, styles, colours
```

---

## 🤖 Telegram Bot (companion)

The repo also includes a Python Telegram bot (`tg-wa.py`) that:

- Accepts forwarded Telegram sticker packs
- Converts them to `.wasticker` format (static → WebP, animated → animated WebP)
- Sends the ready-to-import file back to the user

**Setup:**

```bash
cp .env.example .env   # fill in your bot token and API keys
pip install -r requirements.txt
python tg-wa.py
```

---

## 🎨 Theming

Three modes available in **Settings → Theme**:

| Mode           | Description                                   |
| -------------- | --------------------------------------------- |
| System Default | Follows Android system dark/light setting     |
| Light          | Always light                                  |
| Dark           | Material dark surfaces                        |
| AMOLED Dark    | True black — battery friendly on OLED screens |

All modes use **Material You / Monet** dynamic colours where supported (Android 12+).

---

## ❤️ Support

If MochiMochi saved you from manually screenshotting stickers like a caveman, consider supporting:

> **[Donate $69](https://github.com/maxcodl/MochiMochi)** — or send your kidney to Max ❤️

---

## 📄 License

BSD 3-Clause — see [LICENSE](https://github.com/maxcodl/MochiMochi?tab=License-1-ov-file) for details.

---

<div align="center">
Made with ❤️ and too many stickers by <b>Max</b>
</div>
