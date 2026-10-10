import { browser } from "#imports";

/**
 * Real-time silence detection for a single media element.
 *
 * Audio graph: MediaElementSource → VolumeProcessor (AudioWorklet, passthrough
 * + RMS reporting) → DelayNode (lookahead) → GainNode (mute) → destination.
 *
 * Detection taps the audio BEFORE the delay, so with lookahead enabled we know
 * about transitions LOOKAHEAD_S before the user hears them — the controller
 * uses that to schedule the unmute exactly on the speech onset.
 *
 * The element's audio is permanently rerouted through this graph
 * (createMediaElementSource is once-per-element-forever), so the graph is
 * cached per element.
 */

export interface VolumeSample {
  volumeDb: number;
  thresholdDb: number;
  silent: boolean;
}

export interface ThresholdConfig {
  dynamicThreshold: boolean;
  manualThresholdDb: number;
}

/**
 * Audio output delay while lookahead is active. Audio lagging video becomes
 * detectable at ~125ms (ITU-R BT.1359 lip-sync threshold) — stay well under,
 * while leaving room for detection latency (~21ms) + the scheduled unmute.
 */
export const LOOKAHEAD_S = 0.06;

const WINDOW_SAMPLES = 1024; // must match volume-processor.ts (~21ms at 48kHz)
const RING_SIZE = 470; // ~10s of volume history
const RECALC_EVERY_SAMPLES = 23; // recompute dynamic threshold every ~500ms
const WARMUP_SAMPLES = 47; // ~1s before the dynamic threshold kicks in
const NOISE_FLOOR_PERCENTILE = 0.15;
const THRESHOLD_MARGIN_DB = 3;
const HYSTERESIS_DB = 3; // Schmitt trigger: exit silence only this far above threshold
const MIN_THRESHOLD_DB = -60;
const MAX_THRESHOLD_DB = -20;
const MIN_VOLUME_DB = -100; // reported floor for digital silence

interface AudioGraph {
  worklet: AudioWorkletNode;
  delay: DelayNode;
  gain: GainNode;
}

let sharedCtx: AudioContext | undefined;
let workletModule: Promise<void> | undefined;
const graphs = new WeakMap<HTMLMediaElement, Promise<AudioGraph>>();

function getContext(): AudioContext {
  sharedCtx ??= new AudioContext();
  return sharedCtx;
}

// ponytail: the context is never suspended — suspending would kill audio for
// elements that keep playing while we're detached. Idle graph cost is tiny.
function resumeContext(ctx: AudioContext) {
  if (ctx.state !== "suspended") return;
  ctx.resume();
  // Autoplay policy may keep the context suspended, which would leave the
  // rerouted media silent — retry on the next user gesture.
  const retry = () => ctx.resume();
  document.addEventListener("pointerdown", retry, { once: true, capture: true });
  document.addEventListener("keydown", retry, { once: true, capture: true });
}

function getGraph(el: HTMLMediaElement): Promise<AudioGraph> {
  let graph = graphs.get(el);
  if (!graph) {
    graph = (async () => {
      const ctx = getContext();
      workletModule ??= ctx.audioWorklet.addModule(
        browser.runtime.getURL("/volume-processor.js"),
      );
      await workletModule;
      const source = ctx.createMediaElementSource(el);
      const worklet = new AudioWorkletNode(ctx, "volume-processor");
      const delay = ctx.createDelay(1);
      const gain = ctx.createGain();
      source.connect(worklet);
      worklet.connect(delay);
      delay.connect(gain);
      gain.connect(ctx.destination);
      return { worklet, delay, gain };
    })();
    graphs.set(el, graph);
  }
  return graph;
}

export class SilenceDetector {
  onSample: (sample: VolumeSample) => void = () => {};

  #el: HTMLMediaElement;
  #getConfig: () => ThresholdConfig;
  #graph: AudioGraph | undefined;
  #running = false;
  #lookahead = false;

  #history: number[] = [];
  #dynamicThresholdDb: number | undefined;
  #samplesSinceRecalc = 0;
  #silent = false;

  constructor(el: HTMLMediaElement, getConfig: () => ThresholdConfig) {
    this.#el = el;
    this.#getConfig = getConfig;
  }

  /** Undefined until the async graph setup completes. */
  get gain(): GainNode | undefined {
    return this.#graph?.gain;
  }

  /** Duration of one volume sample in ms. */
  get windowMs(): number {
    return (WINDOW_SAMPLES / getContext().sampleRate) * 1000;
  }

  setLookahead(active: boolean) {
    this.#lookahead = active;
    if (this.#graph && this.#running) {
      this.#graph.delay.delayTime.value = active ? LOOKAHEAD_S : 0;
    }
  }

  async start() {
    if (this.#running) return;
    this.#running = true;
    resumeContext(getContext());
    let graph: AudioGraph;
    try {
      graph = await getGraph(this.#el);
    } catch (error) {
      console.warn("[skip-silence] failed to set up audio analysis", error);
      this.#running = false;
      return;
    }
    if (!this.#running) return; // stopped while setting up
    this.#graph = graph;
    graph.delay.delayTime.value = this.#lookahead ? LOOKAHEAD_S : 0;
    graph.worklet.port.postMessage(true);
    graph.worklet.port.onmessage = (event: MessageEvent<number>) => {
      if (this.#running) this.#onRms(event.data);
    };
  }

  stop() {
    if (!this.#running) return;
    this.#running = false;
    this.#silent = false;
    if (this.#graph) {
      this.#graph.worklet.port.postMessage(false);
      this.#graph.delay.delayTime.value = 0;
    }
  }

  #onRms(rms: number) {
    const rawDb = 20 * Math.log10(rms); // -Infinity on digital silence
    const volumeDb = Math.max(rawDb, MIN_VOLUME_DB);
    const thresholdDb = this.#updateThreshold(rawDb);

    // Schmitt trigger: enter below threshold, exit only above threshold + gap
    if (this.#silent) {
      if (volumeDb > thresholdDb + HYSTERESIS_DB) this.#silent = false;
    } else if (volumeDb < thresholdDb) {
      this.#silent = true;
    }

    this.onSample({ volumeDb, thresholdDb, silent: this.#silent });
  }

  #updateThreshold(rawDb: number): number {
    const config = this.#getConfig();
    if (!config.dynamicThreshold) return config.manualThresholdDb;

    if (Number.isFinite(rawDb)) {
      this.#history.push(rawDb);
      if (this.#history.length > RING_SIZE) this.#history.shift();
    }

    if (
      this.#dynamicThresholdDb === undefined ||
      ++this.#samplesSinceRecalc >= RECALC_EVERY_SAMPLES
    ) {
      this.#samplesSinceRecalc = 0;
      if (this.#history.length >= WARMUP_SAMPLES) {
        const sorted = [...this.#history].sort((a, b) => a - b);
        const noiseFloor = sorted[Math.floor(sorted.length * NOISE_FLOOR_PERCENTILE)];
        this.#dynamicThresholdDb = Math.min(
          Math.max(noiseFloor + THRESHOLD_MARGIN_DB, MIN_THRESHOLD_DB),
          MAX_THRESHOLD_DB,
        );
      }
    }

    return this.#dynamicThresholdDb ?? config.manualThresholdDb;
  }
}
