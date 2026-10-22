# Wani Terminal v2.0.1

An Android app that runs a real Debian (proot) Linux shell, with a built-in AI assistant that can keep working in the background.

## What's fixed in 2.0.1

- **Arrow keys in nano, vim, htop:** these apps ask for the ESC O sequence. The terminal now tracks that mode (DECCKM), so arrows work in them.
- **Terminal resize:** when the keyboard opens or closes, or the phone rotates, the terminal screen and the shell's window size both update. Full-screen apps redraw by themselves. You no longer need Restart shell for this.
- **Paste:** pasting multi-line text sends it as one block when the shell supports bracketed paste (bash does). Lines no longer run one by one.
- **Erase colours:** full-screen apps that erase with a background colour (status bars) now show that colour.
- **Download resume:** if the rootfs download stops (network drop), Retry continues from where it stopped instead of starting over.
- **Image save on Android 9 and older:** the storage permission is asked before saving to Downloads.
- **GitHub screen:** the Connected state is now updated on the main thread (no more state writes from a background thread).
- **Release check:** `scripts/verify-apk.sh` strict mode no longer misses placeholder ROOTFS settings.

## What's new in 2.0

- **Shortcut bar you control.** The Delete (backspace) button is gone from the bar. Your keyboard's delete key still works. In Settings > Shortcut bar, tap any button to show or hide it. Choices include Esc, Tab, Ctrl, Alt, arrows, ^C ^D ^Z ^L, Home/End/PgUp/PgDn, / - | ~ ., the AI button and the AI cell button.
- **Better Gboard typing.** Text that the keyboard is still composing (underlined word) is not sent until it is committed. Pressing Enter sends everything typed so far. Backspace is still forwarded to the shell.
- **Sessions (tabs).** Use the `+` chip under the header (or the menu) to open up to 5 shells. Each session keeps running in the background. Close one from the menu.
- **AI cell.** Tap the AI cell button (or menu > Show AI cell). A small AI box opens above the shortcut bar. Ask a question there and the reply shows inside the cell. The terminal size does not change.
- **AI actions in the terminal.** Commands the AI runs and their output appear in the terminal too (turn off in Settings > Show AI actions in terminal).
- **Stronger AI:**
  - New `read_terminal` tool: the AI can read what is on your terminal screen.
  - New `set_app_setting` tool: the AI can change the shortcut bar and the terminal logging setting. It cannot change the API key, model or approval rules. Each change asks you first, unless "Run without asking" is on.
  - Extra instructions field (Settings) is added to the AI's system prompt.
  - Quick setup presets (OpenRouter, OpenAI, Groq, local).
  - Chat and history are saved on the phone, so they survive app restarts.
  - A tool that is stopped midway no longer breaks the next request.
  - Long conversations are trimmed automatically, so requests stay fast.
  - Tool errors go back to the AI as results, so one failure does not end the run.
  - A partial wake lock keeps the phone working while the AI runs.
- **Run 24/7.** The foreground service keeps the shell and the AI alive. Settings > Run 24/7 opens the battery settings, where you can set Wani to unrestricted.

## What was in 1.5 and 1.6

- **AI tools:** run commands, start background jobs (check with job_status), type into the visible terminal, write and read files, and open live pages.
- **Live pages:** the AI can show a small HTML page on the phone to ask you a question. Your answer goes back to the AI.
- **Upload button** in the AI chat: files are copied to `/root/uploads`, and the AI gets the path.
- **Light / dark theme** (AI screen header or Settings). The terminal stays dark.
- **Simple first-run setup.** It downloads and prepares Debian in the background, then opens the terminal. Install what you need yourself, for example `apt-get update && apt-get install python3`.
- **Storage helper.** `wani-setup-storage` links `~/storage/downloads` to the Downloads folder.
- **GitHub build page.** Paste a GitHub token, enter `owner/repo`, tap Connect, then Start APK build.

## Not in 2.0 (planned)

- Controlling the phone from a chat app (for example a Telegram bot). This needs its own security design, so it is not included yet.

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

- Emoji and wide CJK characters take one cell in some full-screen apps, so columns can shift slightly there.
- Gboard behaviour differs between keyboard versions. If a word does not appear until you type a space or press Enter, that is the composing-text rule above.
- SVG images are not shown (bitmap formats only).
- targetSdk 28 (needed for proot). Not for the Play Store; install the APK directly.
