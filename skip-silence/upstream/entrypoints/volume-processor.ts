import { defineUnlistedScript } from "#imports";

/**
 * AudioWorklet processor: passes audio through untouched and posts the RMS of
 * every ~21ms window (1024 samples) to the main thread. Runs on the audio
 * thread, so detection keeps its cadence even when the tab's timers are
 * throttled in the background.
 *
 * Loaded via audioWorklet.addModule(runtime.getURL('/volume-processor.js')) —
 * listed in web_accessible_resources.
 */

declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}
declare function registerProcessor(
  name: string,
  ctor: new () => AudioWorkletProcessor,
): void;

const WINDOW_SAMPLES = 1024;

export default defineUnlistedScript(() => {
  class VolumeProcessor extends AudioWorkletProcessor {
    #sumSquares = 0;
    #count = 0;
    #reporting = true;

    constructor() {
      super();
      this.port.onmessage = (event: MessageEvent<boolean>) => {
        this.#reporting = event.data;
      };
    }

    process(inputs: Float32Array[][], outputs: Float32Array[][]) {
      const input = inputs[0];
      const output = outputs[0];
      if (!input.length) return true;

      const samples = input[0].length;
      for (let i = 0; i < samples; i++) {
        let squareSum = 0;
        for (let channel = 0; channel < input.length; channel++) {
          const sample = input[channel][i];
          squareSum += sample * sample;
          if (output[channel]) output[channel][i] = sample;
        }
        this.#sumSquares += squareSum / input.length;
      }
      this.#count += samples;

      if (this.#count >= WINDOW_SAMPLES) {
        if (this.#reporting) {
          this.port.postMessage(Math.sqrt(this.#sumSquares / this.#count));
        }
        this.#sumSquares = 0;
        this.#count = 0;
      }
      return true;
    }
  }

  registerProcessor("volume-processor", VolumeProcessor);
});
