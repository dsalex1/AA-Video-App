import { LOOKAHEAD_S, SilenceDetector, type VolumeSample } from "./detector";
import { timeSavedMs, type Settings } from "./settings";
import { createRateSetter } from "./speed";

// Silence must hold this long before we speed up — short enough to catch real
// pauses, long enough that brief dips don't cause rapid skip/unskip flapping.
const HOLD_MS = 500;
const RAMP_MS = 200; // linear speed ramp into silence
const MUTE_TIME_CONSTANT = 0.015; // fast fade-out avoids clicks
const UNMUTE_TIME_CONSTANT = 0.04; // fade-in without lookahead
const SCHEDULED_UNMUTE_TIME_CONSTANT = 0.02; // sharper is fine when timed precisely
/**
 * With lookahead, start unmuting this early (in heard time) so the fade
 * completes before the speech onset and quiet lead-ins (breaths, soft first
 * syllables) make it through — a real margin-before, from the delay buffer.
 */
const UNMUTE_LEAD_S = 0.04;
const FLUSH_EVERY_MS = 5000; // persist time saved every ~5s

/**
 * Per-element playback policy: watches the detector's sample stream and
 * switches between normal and silence speed with hysteresis, speed ramping
 * and click-free gain muting. With lookahead enabled, unmutes are scheduled
 * on the AudioContext clock to land exactly when speech reaches the ears.
 */
export class MediaController {
  lastSample: VolumeSample | undefined;

  #detector: SilenceDetector;
  #setRate: (rate: number) => void;
  #getSettings: () => Settings;

  #silentMs = 0;
  #fast = false;
  #sinceFlushMs = 0;
  #pendingSavedMs = 0;

  constructor(el: HTMLMediaElement, getSettings: () => Settings) {
    this.#getSettings = getSettings;
    this.#setRate = createRateSetter(el);
    this.#detector = new SilenceDetector(el, getSettings);
    this.#detector.onSample = (sample) => this.#onSample(sample);
  }

  start() {
    const cfg = this.#getSettings();
    this.#setRate(cfg.playbackSpeed);
    this.#detector.setLookahead(cfg.lookahead && cfg.muteSilence);
    this.#detector.start();
  }

  /** Stop detection and restore normal playback. */
  stop(restoreRate = this.#getSettings().playbackSpeed) {
    this.#detector.stop();
    this.#restoreGain();
    this.#setRate(restoreRate);
    this.#silentMs = 0;
    this.#fast = false;
    this.lastSample = undefined;
    this.#flushSaved();
  }

  /** Re-apply settings that changed while running (speeds, mute, lookahead). */
  refresh() {
    const cfg = this.#getSettings();
    this.#detector.setLookahead(cfg.lookahead && cfg.muteSilence);
    if (this.#fast) {
      this.#setRate(cfg.silenceSpeed);
      if (!cfg.muteSilence) this.#restoreGain();
    } else {
      this.#setRate(cfg.playbackSpeed);
    }
  }

  #onSample(sample: VolumeSample) {
    this.lastSample = sample;
    const cfg = this.#getSettings();
    const windowMs = this.#detector.windowMs;
    this.#sinceFlushMs += windowMs;

    if (sample.silent) {
      const prevSilentMs = this.#silentMs;
      this.#silentMs += windowMs;
      if (this.#silentMs >= HOLD_MS) {
        if (!this.#fast) this.#enterSilence(cfg);
        if (prevSilentMs < HOLD_MS + RAMP_MS) {
          const progress = Math.min((this.#silentMs - HOLD_MS) / RAMP_MS, 1);
          this.#setRate(
            cfg.playbackSpeed + (cfg.silenceSpeed - cfg.playbackSpeed) * progress,
          );
        }
        this.#pendingSavedMs += windowMs * (1 - cfg.playbackSpeed / cfg.silenceSpeed);
      }
    } else {
      this.#silentMs = 0;
      if (this.#fast) this.#exitSilence(cfg);
    }

    if (this.#sinceFlushMs >= FLUSH_EVERY_MS) {
      this.#sinceFlushMs = 0;
      this.#flushSaved();
    }
  }

  #enterSilence(cfg: Settings) {
    this.#fast = true;
    const gain = this.#detector.gain;
    if (cfg.muteSilence && gain) {
      const now = gain.context.currentTime;
      gain.gain.cancelScheduledValues(now);
      gain.gain.setTargetAtTime(0, now, MUTE_TIME_CONSTANT);
    }
  }

  #exitSilence(cfg: Settings) {
    this.#fast = false;
    this.#setRate(cfg.playbackSpeed);
    const gain = this.#detector.gain;
    if (!gain) return;
    const now = gain.context.currentTime;
    gain.gain.cancelScheduledValues(now);
    if (cfg.lookahead && cfg.muteSilence) {
      // The onset is still LOOKAHEAD_S away from the ears — schedule the
      // unmute to finish right on it instead of fading over the first word.
      gain.gain.setTargetAtTime(
        1,
        now + LOOKAHEAD_S - UNMUTE_LEAD_S,
        SCHEDULED_UNMUTE_TIME_CONSTANT,
      );
    } else {
      gain.gain.setTargetAtTime(1, now, UNMUTE_TIME_CONSTANT);
    }
  }

  #restoreGain() {
    const gain = this.#detector.gain;
    if (!gain) return;
    gain.gain.cancelScheduledValues(gain.context.currentTime);
    gain.gain.value = 1;
  }

  #flushSaved() {
    const ms = this.#pendingSavedMs;
    if (ms < 1) return;
    this.#pendingSavedMs = 0;
    // ponytail: read-modify-write can race across tabs; a lost tick of the
    // fun counter is fine.
    timeSavedMs.getValue().then((total) => timeSavedMs.setValue(total + ms));
  }
}
