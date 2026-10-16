# Wani Terminal v1.6.1

An Android app that runs a real Debian (proot) Linux shell, with a built-in AI assistant.

## What's new in 1.5

- **AI tools:** run commands, start background jobs (check with job_status), type into the visible terminal, write and read files, and open live pages.
- **Live pages:** the AI can show a small HTML page on the phone to ask you a question. Your answer goes back to the AI.
- **Upload button** in the AI chat: files are copied to `/root/uploads`, and the AI gets the path.
- **Light / dark theme** (AI screen header or Settings). The terminal stays dark.
- Error fixes and cleanup from 1.4.

## What was in 1.4

- **Simple first-run setup.** It downloads and prepares Debian in the background, then opens the terminal. It does not install Python or any other packages. Install what you need yourself, for example `apt-get update && apt-get install python3`.
- **One-line shortcut bar.** Ctrl, Alt, Tab, arrows, Esc, Home/End/PgUp/PgDn, ^C ^D ^Z and more, symbols, and the AI button all sit in one horizontally scrollable line.
- **Sideways scroll** for long terminal lines instead of wrapping them.
- **AI assistant** (AI button, or type `@ai <question>` in the shell):
  - Works with any OpenAI-compatible API. Set the base URL, model and API key in Settings.
  - Can run shell commands and create files. Each command asks Yes/No first, unless you turn on "Run without asking".
  - Can read files in `/root` and in Downloads (`/sdcard/Download`). Storage access is asked for once, with Yes/No.
- **Storage helper.** `wani-setup-storage` links `~/storage/downloads` to the Downloads folder.
- **GitHub build page.** Paste a GitHub token, enter `owner/repo`, tap Connect, then Start APK build. Check latest build shows the status and opens the run page, where the APK is in Artifacts.
- All UI text is in English.

## Build

Repo variables (Settings > Secrets and variables > Actions > Variables):
- `ROOTFS_URL`: public link to an arm64 Debian rootfs (.tar.xz)
- `ROOTFS_SHA256`: SHA-256 of that file
- `PROOT_URL` (optional): link to `libproot.so`, if it is not in the repo

Workflow: `.github/workflows/build.yml`. Manual run: Actions > build-apk > Run workflow.

## Helper commands (inside the Linux shell)

- `wani-setup-storage` : link phone storage (Downloads) into `~/storage`
- `wani-show FILE` : send a png/jpg/gif/webp to the graphics strip
- `wani-install-charts` : install matplotlib (only when you want it)
- `wani-chart-demo` : draw a demo chart

## Security notes

- The AI API key and GitHub token are stored in the app's private settings on the phone. They are sent only to the API or GitHub you configured.
- Turn on "Run without asking" only if you trust what the AI will do.

## Limitations

- Terminal size is set when the shell starts. After rotating or changing the keyboard, use Restart shell.
- SVG images are not shown (bitmap formats only).
- targetSdk 28 (needed for proot). Not for the Play Store; install the APK directly.
