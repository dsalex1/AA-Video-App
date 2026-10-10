import { storage } from '#imports';

export interface Settings {
  enabled: boolean;
  /** Playback rate during normal (loud) parts */
  playbackSpeed: number;
  /** Playback rate during silent parts */
  silenceSpeed: number;
  /** Mute audio while in a silent part */
  muteSilence: boolean;
  /**
   * Delay audio slightly so the unmute lands exactly when speech returns
   * (see LOOKAHEAD_S in detector.ts)
   */
  lookahead: boolean;
  /** Auto-derive the threshold from the media's noise floor */
  dynamicThreshold: boolean;
  /** Threshold in dBFS, used when dynamicThreshold is off (and as warm-up fallback) */
  manualThresholdDb: number;
}

export const DEFAULT_SETTINGS: Settings = {
  enabled: false, // opt-in — the popup nudges toward the switch
  playbackSpeed: 1,
  silenceSpeed: 3,
  muteSilence: true,
  lookahead: true,
  dynamicThreshold: true,
  manualThresholdDb: -40,
};

/** Fill in fields added after the user first stored their settings. */
export function withDefaults(value: Partial<Settings> | null | undefined): Settings {
  return { ...DEFAULT_SETTINGS, ...value };
}

export const settings = storage.defineItem<Settings>('sync:settings', {
  fallback: DEFAULT_SETTINGS,
});

/**
 * Per-site overrides, keyed by hostname. When a site has an entry it fully
 * replaces the global settings on that site.
 */
// ponytail: local (not sync) — sync's 8KB/item quota caps at ~60 sites.
export const siteSettings = storage.defineItem<Record<string, Settings>>('local:siteSettings', {
  fallback: {},
});

export const timeSavedMs = storage.defineItem<number>('local:timeSavedMs', {
  fallback: 0,
});

/** Whether the "Skip Silence 6" intro has been shown (shown until 2026-10-01). */
export const introSeen = storage.defineItem<boolean>('local:introSeen', {
  fallback: false,
});
