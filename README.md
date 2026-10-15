# Wani Terminal v1.3

Android app jo Debian (proot) chala kar asli Linux shell deti hai.

## Is version mein kya naya hai

- **Tap-to-type:** input bar nahi hai. Terminal screen par tap karein to keyboard khulta hai, aur keys seedha shell ko jaati hain (nano, vim, top ke liye). Keyboard chip (⌨) se bhi khul jata hai.
- **Full-screen apps:** nano, vim, top, htop ka screen sahi draw hota hai (cursor movement, alternate screen, scroll region).
- **Ctrl / Alt keys:** `Ctrl` aur `Alt` sticky toggle hain. `^O ^X ^S ^W ^K ^G ^C ^Z` waghera ki chips hain.
- **Arrows, Esc, Home, End, PgUp, PgDn, Del, Ins** ki chips.
- **Graphics:** shell mein `wani-show chart.png` chalayein, image upar strip mein aati hai. Tap karke preview aur "Save to Downloads".
- **Charts:** `wani-install-charts` (pehli dafa), phir `wani-chart-demo`.
- **Setup:** 2 clear steps (package list, basic tools: nano, python3, git, curl). Har line par progress.

## Build

Repo variables (Settings > Secrets and variables > Actions > Variables):
- `ROOTFS_URL`: arm64 Debian rootfs ka public link
- `ROOTFS_SHA256`: us file ka SHA-256
- `PROOT_URL` (optional): `libproot.so` ka link, agar repo mein na ho

Workflow: `.github/workflows/build.yml`. Manual run: Actions > build-apk > Run workflow.

## Helper commands (rootfs ke andar)

- `wani-show FILE` : png/jpg/gif/webp ko graphics strip mein bhejta hai
- `wani-install-charts` : matplotlib install karta hai
- `wani-chart-demo` : demo chart banata aur dikhata hai

## Limitations

- Terminal ka size shell start hone par fix hota hai. Rotate ya keyboard ke baad naya size lene ke liye menu se "Restart shell".
- SVG images abhi nahi dikhti (sirf bitmap formats).
- Kuch keyboards (Gboard composition) mein duplicate letters aa sakte hain. Aise mein suggestions band karke type karein.
- targetSdk 28 (proot ke liye). Play Store par nahi chalegi, sirf APK sideload.
