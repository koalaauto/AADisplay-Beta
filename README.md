# AADisplay-Beta

> ⚠️ **Archived & unmaintained.** This fork is no longer developed — kept here for reference only.
>
> - **Want the open-source module?** Follow upstream → [`Nitsuya/AADisplay`](https://github.com/Nitsuya/AADisplay).
> - **Want a maintained, ready-to-use app?** Try **KoalaMirror**, independently rebuilt from what we learned here → **[koalamirror.com](https://koalamirror.com)**.
>
> Still GPL-3.0, with thanks to [@Nitsuya](https://github.com/Nitsuya). The original project README is preserved below. 🐨

---

[![AADisplay-Beta](https://img.shields.io/badge/AADisplayBeta-Project-blue?logo=github)](https://github.com/bikekoala/AADisplay-Beta)
[![GitHub Release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/io.github.bikekoala.aa.display.beta)](https://github.com/Xposed-Modules-Repo/io.github.bikekoala.aa.display.beta/releases)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK](https://img.shields.io/badge/Android%20SDK-min%2031%20%C2%B7%20target%2036-brightgreen?logo=android)

A personal fork of [`Nitsuya/AADisplay`](https://github.com/Nitsuya/AADisplay) — an Xposed / LSPosed module that mirrors almost any app onto the Android Auto screen via a VirtualDisplay.

## Requirements

- Android 12+ (SDK 31+; Android 10–11 unsupported)
- Rooted device with **LSPosed** (or a compatible Xposed environment)
- Working Android Auto (`com.google.android.projection.gearhead`)

> Some ROMs may be unstable or crash — use at your own risk.

## Usage

1. Enable the module in **LSPosed**, scoped to **System Framework** + **Android Auto**.
2. Set your launcher's package name in the module settings.
3. Optional: tune **DPI** for the car screen, or inject Android Auto **properties**.

Root is only used for user-configured shell commands — deny it if you don't need that.

For build instructions and full details, see the upstream project: [`Nitsuya/AADisplay`](https://github.com/Nitsuya/AADisplay).

## License

Same as upstream — see `LICENSE`.
