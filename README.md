# Wani Terminal

Android app jo Debian rootfs download karke `proot` ke zariye Linux shell chalati hai.

## Setup se pehle zaroori cheezein

1. **proot binaries (arm64):** Inhe `app/src/main/jniLibs/arm64-v8a/` mein rakhein:
   - `libproot.so` (proot binary, zaroori)
   - `libprootloader.so` (proot ka loader, optional lekin behtar)

   Ya GitHub repo variables `PROOT_URL` / `PROOT_LOADER_URL` mein direct download links dein, workflow khud download kar legi. Workflow check karta hai ke file arm64 ELF ho.
2. **Rootfs:** Apna arm64 Debian `.tar.xz` host karein aur uska SHA-256 nikalein: `sha256sum debian-arm64.tar.xz`
3. Rootfs settings do tareeqon mein se kisi se dein:
   - GitHub repo variables: `ROOTFS_URL` aur `ROOTFS_SHA256` (**Settings > Secrets and variables > Actions > Variables**). Recommended.
   - Local build: `-ProotfsUrl=...` aur `-ProotfsSha256=...` ya environment variables `ROOTFS_URL` / `ROOTFS_SHA256`.

   Settings Gradle `BuildConfig` mein jaati hain, `Config.kt` ko edit karne ki zaroorat nahi.

## Build

### GitHub Actions (recommended)

- Har push aur pull request par `build` job chalti hai, aur `wani-terminal-debug` artifact milta hai. Isme APK, `.sha256` file aur summary hoti hai.
- **Tag** push karein (`git tag v1.2 && git push --tags`) to APK GitHub Release mein bhi upload hoti hai. Tag builds **strict** hoti hain: `libproot.so` ya rootfs settings missing ho to build fail hoti hai.
- Manual run: Actions > build-apk > Run workflow, `strict` checkbox se strict mode on karein.

Runner `ubuntu-24.04` par pinned hai. Android SDK platform 34 aur build-tools 34.0.0 workflow khud install karta hai.

### Local build (JDK 17 + Android SDK)

```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug -ProotfsUrl=https://... -ProotfsSha256=<64 hex>
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Scripts

- `scripts/fetch-proot.sh`: proot binaries download karta hai aur architecture check karta hai. `STRICT=true` par missing binary par fail karta hai.
- `scripts/verify-apk.sh <apk> [strict] [outdir]`: APK ke andar `classes.dex`, `libproot.so` aur placeholder settings check karta hai, phir SHA-256 likhta hai.

## Features

- Asli PTY (rootfs ke `script` ke zariye): colors, Tab completion, history (↑/↓), Ctrl+C / Ctrl+D, `top` waghera
- ANSI colors, bold, underline render hote hain
- Setup screen par download progress, extract count, apt output
- Download ka SHA-256 verify hota hai, aur tar extraction symlink ke zariye rootfs se bahar nahi likh sakti
- Notification mein Exit button, Copy / Paste / Clear / Restart menu

## Limitations

- Full-screen programs (`vim`, `nano`, `htop`) theek nahi chalenge: cursor movement ka emulator nahi hai.
- Terminal ka size shell shuru hone par fix hota hai (rotate karne par restart karein).
- **targetSdk 28:** Android 10+ par app data se binary chalane ke liye. Play Store par nahi chalegi, sirf APK sideload.
- Kuch phones `proot` ko /proc ya /sys bind karne nahi dete.
- APK debug key se sign hoti hai, isliye sideload ke liye direct install ho jati hai.
