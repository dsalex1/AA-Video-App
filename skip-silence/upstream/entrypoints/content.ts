import { browser, defineContentScript } from "#imports";
import { MediaController } from "@/lib/controller";
import { settings, siteSettings, withDefaults, type Settings } from "@/lib/settings";

export type MeterMessage =
  | { state: "active"; volumeDb: number; thresholdDb: number; silent: boolean }
  | { state: "cors" | "drm" };

const METER_INTERVAL_MS = 33;

/**
 * createMediaElementSource() on cross-origin media without CORS headers
 * outputs pure silence and permanently kills the element's audio — never
 * attach to those.
 */
function isCorsBlocked(el: HTMLMediaElement): boolean {
  if (el.crossOrigin != null) return false;
  try {
    return new URL(el.currentSrc).origin !== location.origin;
  } catch {
    return false; // empty/relative currentSrc — same-origin enough
  }
}

/**
 * Hostname of the top-level page, so per-site settings apply to embedded
 * players too. ancestorOrigins is Chrome/Safari-only — Firefox iframes fall
 * back to their own hostname.
 */
function getTopHostname(): string {
  const origins = location.ancestorOrigins;
  if (origins?.length) {
    try {
      return new URL(origins[origins.length - 1]).hostname;
    } catch {}
  }
  return location.hostname;
}

export default defineContentScript({
  matches: ["<all_urls>"],
  allFrames: true,
  async main() {
    const host = getTopHostname();
    let global = withDefaults(await settings.getValue());
    let sites = await siteSettings.getValue();
    let cfg: Settings = withDefaults(sites[host] ?? global);

    const controllers = new WeakMap<HTMLMediaElement, MediaController>();
    const active = new Set<MediaController>();
    let current: MediaController | undefined; // most recently started
    let blocked: "cors" | "drm" | undefined;

    const onPlay = (el: HTMLMediaElement) => {
      if (!cfg.enabled) return;
      if (el.mediaKeys != null) return void (blocked = "drm");
      if (isCorsBlocked(el)) return void (blocked = "cors");
      if (el.duration === Infinity) return; // live stream — nothing to skip

      let controller = controllers.get(el);
      if (!controller) {
        controller = new MediaController(el, () => cfg);
        controllers.set(el, controller);
      }
      controller.start();
      active.add(controller);
      current = controller;
      blocked = undefined;
    };

    const onStop = (el: HTMLMediaElement) => {
      const controller = controllers.get(el);
      if (controller && active.has(controller)) {
        controller.stop();
        active.delete(controller);
        if (current === controller) current = [...active].pop();
      }
    };

    const forMediaTarget =
      (handler: (el: HTMLMediaElement) => void) => (event: Event) => {
        if (event.target instanceof HTMLMediaElement) handler(event.target);
      };

    // Media events don't bubble but do capture — this catches every element,
    // including dynamically added ones, without a MutationObserver.
    document.addEventListener("play", forMediaTarget(onPlay), true);
    document.addEventListener("pause", forMediaTarget(onStop), true);
    document.addEventListener("ended", forMediaTarget(onStop), true);
    document.addEventListener("emptied", forMediaTarget(onStop), true);

    const attachAlreadyPlaying = () => {
      for (const el of document.querySelectorAll<HTMLMediaElement>("video, audio")) {
        if (!el.paused) onPlay(el);
      }
    };
    attachAlreadyPlaying();

    const applySettings = () => {
      const wasEnabled = cfg.enabled;
      cfg = withDefaults(sites[host] ?? global);
      if (wasEnabled && !cfg.enabled) {
        for (const controller of active) controller.stop(1);
        active.clear();
        current = undefined;
      } else if (!wasEnabled && cfg.enabled) {
        attachAlreadyPlaying();
      } else {
        for (const controller of active) controller.refresh();
      }
    };

    settings.watch((newValue) => {
      global = withDefaults(newValue);
      applySettings();
    });
    siteSettings.watch((newValue) => {
      sites = newValue ?? {};
      applySettings();
    });

    // Popup meter stream. Only post when this frame has something to say so
    // sibling frames don't fight over the popup's port.
    browser.runtime.onConnect.addListener((port) => {
      const interval = setInterval(() => {
        const sample = current?.lastSample;
        let message: MeterMessage | undefined;
        if (sample) {
          message = { state: "active", ...sample };
        } else if (blocked) {
          message = { state: blocked };
        }
        if (message) port.postMessage(message);
      }, METER_INTERVAL_MS);
      port.onDisconnect.addListener(() => clearInterval(interval));
    });
  },
});
