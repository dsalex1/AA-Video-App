/**
 * Stand-ins for the WXT / WebExtension APIs used by the upstream Skip Silence code, backed by the Android app's
 * `AASkip` JavaScript interface (available in every frame, so the page, embedded players and the settings page
 * all share one settings store).
 */
interface Bridge {
  get(key: string): string | null;
  set(key: string, json: string): void;
  pageUrl(): string | null;
  meterWanted(): boolean;
  setMeterWanted(wanted: boolean): void;
  pushMeter(json: string): void;
  readMeter(): string | null;
}

const bridge = (window as unknown as { AASkip?: Bridge }).AASkip;

/** Same-origin path the app serves the worklet from, so page CSPs that only allow 'self' still load it. */
export const PROCESSOR_PATH = "/__aa_skip_silence/volume-processor.js";

function read<T>(key: string, fallback: T): T {
  try {
    const raw = bridge?.get(key);
    return raw == null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

function event<A extends unknown[]>() {
  const listeners = new Set<(...args: A) => void>();
  return {
    addListener: (listener: (...args: A) => void) => void listeners.add(listener),
    removeListener: (listener: (...args: A) => void) => void listeners.delete(listener),
    emit: (...args: A) => listeners.forEach((listener) => listener(...args)),
  };
}

export const storage = {
  defineItem<T>(key: string, options: { fallback: T }) {
    return {
      getValue: async (): Promise<T> => read(key, options.fallback),
      setValue: async (value: T): Promise<void> => bridge?.set(key, JSON.stringify(value)),
      /** chrome.storage.onChanged equivalent: poll the shared store. */
      watch(callback: (value: T) => void): () => void {
        let last = bridge?.get(key) ?? null;
        const id = setInterval(() => {
          const now = bridge?.get(key) ?? null;
          if (now !== last) {
            last = now;
            callback(read(key, options.fallback));
          }
        }, 1000);
        return () => clearInterval(id);
      },
    };
  },
};

type Port = {
  postMessage(message: unknown): void;
  onDisconnect: ReturnType<typeof event<[]>>;
};

export const browser = {
  runtime: {
    getURL(path: string): string {
      return path === "/volume-processor.js" ? location.origin + PROCESSOR_PATH : path;
    },
    /** Content side of the live volume meter: hand out a port while the settings page is open. */
    onConnect: {
      addListener(callback: (port: Port) => void) {
        let port: (Port & { disconnect: () => void }) | undefined;
        setInterval(() => {
          const wanted = !!bridge?.meterWanted();
          if (wanted && !port) {
            const onDisconnect = event<[]>();
            port = {
              postMessage: (message) => bridge?.pushMeter(JSON.stringify(message)),
              onDisconnect,
              disconnect: () => onDisconnect.emit(),
            };
            callback(port);
          } else if (!wanted && port) {
            port.disconnect();
            port = undefined;
          }
        }, 500);
      },
    },
  },
  tabs: {
    async query(_query: unknown) {
      return [{ id: 1, url: bridge?.pageUrl() ?? undefined }];
    },
    /** Settings-page side of the live volume meter. */
    connect(_tabId: number) {
      bridge?.setMeterWanted(true);
      const onMessage = event<[unknown]>();
      const onDisconnect = event<[]>();
      const id = setInterval(() => {
        const raw = bridge?.readMeter();
        if (raw) onMessage.emit(JSON.parse(raw));
      }, 33);
      return {
        onMessage,
        onDisconnect,
        disconnect() {
          clearInterval(id);
          bridge?.setMeterWanted(false);
        },
      };
    },
  },
};

export const defineContentScript = <T>(definition: T) => definition;
export const defineUnlistedScript = (main: () => void) => main;
