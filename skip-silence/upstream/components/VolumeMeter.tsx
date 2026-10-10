import { browser } from '#imports';
import { PauseCircle, ShieldAlert, VideoOff } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { MeterMessage } from '@/entrypoints/content';

const BAR_COUNT = 64;
const DB_FLOOR = -70;
const NO_SIGNAL_TIMEOUT_MS = 600;

const OVERLAYS = {
  'no-media': { icon: VideoOff, text: 'No media playing on this page' },
  cors: { icon: ShieldAlert, text: "This media can't be analyzed" },
  drm: { icon: ShieldAlert, text: 'DRM-protected media' },
  paused: {
    icon: PauseCircle,
    text: 'Skip Silence is off — flip the switch above to start',
  },
} as const;

type Status = 'active' | keyof typeof OVERLAYS;

interface Frame {
  bars: { db: number; silent: boolean }[];
  thresholdDb: number;
  lastSampleAt: number;
}

/** Live scrolling volume meter, streamed from the active tab's content script. */
export function VolumeMeter({ enabled }: { enabled: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const frameRef = useRef<Frame>({ bars: [], thresholdDb: -40, lastSampleAt: 0 });
  const [status, setStatus] = useState<Status>('no-media');
  const [skipping, setSkipping] = useState(false);

  useEffect(() => {
    let port: ReturnType<typeof browser.tabs.connect> | undefined;
    let cancelled = false;

    browser.tabs
      .query({ active: true, currentWindow: true })
      .then(([tab]) => {
        if (cancelled || tab?.id == null) return;
        port = browser.tabs.connect(tab.id);
        port.onMessage.addListener((message: MeterMessage) => {
          const frame = frameRef.current;
          frame.lastSampleAt = performance.now();
          if (message.state === 'active') {
            setStatus('active');
            setSkipping(message.silent);
            frame.bars.push({ db: message.volumeDb, silent: message.silent });
            if (frame.bars.length > BAR_COUNT) frame.bars.shift();
            frame.thresholdDb = message.thresholdDb;
          } else {
            setStatus(message.state);
          }
        });
        port.onDisconnect.addListener(() => setStatus('no-media'));
      })
      .catch(() => setStatus('no-media')); // chrome:// pages etc.

    const staleCheck = setInterval(() => {
      if (performance.now() - frameRef.current.lastSampleAt > NO_SIGNAL_TIMEOUT_MS) {
        setStatus('no-media');
        setSkipping(false);
        frameRef.current.bars = [];
      }
    }, NO_SIGNAL_TIMEOUT_MS);

    return () => {
      cancelled = true;
      clearInterval(staleCheck);
      try {
        port?.disconnect();
      } catch {}
    };
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext('2d')!;
    const scale = devicePixelRatio;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    canvas.width = width * scale;
    canvas.height = height * scale;
    ctx.scale(scale, scale);

    const loudFill = ctx.createLinearGradient(0, 0, 0, height);
    loudFill.addColorStop(0, '#a3e635'); // lime-400
    loudFill.addColorStop(1, '#65a30d'); // lime-600

    const dbToY = (db: number) =>
      height - ((Math.min(Math.max(db, DB_FLOOR), 0) - DB_FLOOR) / -DB_FLOOR) * (height - 14);

    let raf: number;
    const draw = () => {
      raf = requestAnimationFrame(draw);
      const { bars, thresholdDb } = frameRef.current;
      ctx.clearRect(0, 0, width, height);

      // Faint gridlines
      ctx.fillStyle = 'rgba(0, 0, 0, 0.045)';
      for (const db of [-20, -40, -60]) ctx.fillRect(0, Math.round(dbToY(db)), width, 1);

      // Whole-pixel bar positions keep the meter crisp — sub-pixel offsets
      // would anti-alias every bar into a blur.
      const barWidth = width / BAR_COUNT;
      for (let i = 0; i < bars.length; i++) {
        const bar = bars[i];
        const x = Math.round(width - (bars.length - i) * barWidth) + 1;
        const y = Math.round(dbToY(bar.db));
        const barHeight = height - y;
        if (barHeight < 1) continue;
        ctx.fillStyle = bar.silent ? '#e4e4e7' : loudFill;
        ctx.beginPath();
        ctx.roundRect(x, y, Math.floor(barWidth) - 2, barHeight + 4, 2);
        ctx.fill();
      }

      // Threshold line + label
      const thresholdY = Math.round(dbToY(thresholdDb)) + 0.5;
      ctx.strokeStyle = 'rgba(63, 63, 70, 0.45)';
      ctx.setLineDash([3, 3]);
      ctx.beginPath();
      ctx.moveTo(0, thresholdY);
      ctx.lineTo(width, thresholdY);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.font = '600 9px system-ui';
      ctx.fillStyle = 'rgba(63, 63, 70, 0.55)';
      ctx.textAlign = 'left';
      ctx.fillText(`${Math.round(thresholdDb)} dB`, 6, thresholdY - 4);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, []);

  const overlay = !enabled ? OVERLAYS.paused : status !== 'active' ? OVERLAYS[status] : undefined;
  const showBadge = enabled && status === 'active';

  return (
    <div className="relative h-28 w-full overflow-hidden rounded-xl border border-zinc-200/80 bg-white shadow-[0_1px_2px_rgba(0,0,0,0.03)]">
      <canvas ref={canvasRef} className="h-full w-full" />
      {showBadge && (
        <span
          className={
            'absolute right-2 top-2 rounded-full px-2 py-0.5 text-[10px] font-semibold transition-colors duration-200 ' +
            (skipping
              ? 'bg-primary text-primary-foreground'
              : 'border border-zinc-200 bg-white/85 text-zinc-500 backdrop-blur-sm')
          }
        >
          {skipping ? 'Skipping' : 'Playing'}
        </span>
      )}
      {overlay && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-1.5 bg-white/85 px-6 text-center backdrop-blur-[2px]">
          <overlay.icon className="size-4 text-zinc-400" strokeWidth={1.75} />
          <p className="text-xs font-medium text-zinc-500">{overlay.text}</p>
        </div>
      )}
    </div>
  );
}
