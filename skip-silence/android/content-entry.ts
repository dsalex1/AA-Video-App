import script from "../upstream/entrypoints/content";

// Injected by the app at document start into every frame (like the extension's all_frames content script).
const w = window as unknown as { __aaSkipSilence?: boolean; AASkip?: unknown };
if (w.AASkip && !w.__aaSkipSilence) {
  w.__aaSkipSilence = true;
  script.main().catch((error: unknown) => console.warn("[skip-silence]", error));
}
