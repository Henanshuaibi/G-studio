# G studio project conventions

- Visible application name: `G studio`. Retain package `cn.g.studio` and the original signing identity for updates.
- User preference: app-owned selection, action, confirmation and preview surfaces use the shared `SheetDialog` bottom sheet. Do not introduce centered `AlertDialog` popups. The attachment plus button opens an animated bottom sheet with image and file cards. Keep the left navigation as the existing sliding drawer.
- Sheets support backdrop dismissal, system Back, and dragging the top handle downward. Keep long content scrollable and model search usable above the keyboard.
- System file pickers remain platform activities. The user removed speech input: do not restore its button, recognition calls, result handling, or service discovery without a new request.
- The read-aloud feature was explicitly removed. Do not reintroduce text-to-speech, related menu entries or TTS service discovery without a new user request.
- Never package signing keys or the signing password in APKs or source archives. Build with the existing key, increment the version code for releases, and verify old/new certificates.
- Distinguish compile/package/core checks from real device interaction tests. The current Termux environment has no working UI touch test harness.

- Creative requests use standard Images generation JSON, Responses image_generation tools, and Videos multipart (or explicitly selected JSON) protocols. Preserve remote video IDs and cached image results before follow-up work. Never automatically repeat a creation POST after a timeout or process death; resuming must use cached results or query the existing video task.
- Generated media downloads may follow redirects, but only attach the configured API key to the original API origin, comparing scheme, host and effective port. Keep media in the app-private creative store; export only through the user's selected document destination.

- Keep conversations in the drawer (including load-more and long-press rename/delete); do not restore the removed duplicate history page or chat export action.
- Long-term memory is explicitly saved and editable by the user, shared across chats, and sent only when enabled. Keep a stable memory snapshot for each chat request. Do not silently auto-extract or auto-write memories.
