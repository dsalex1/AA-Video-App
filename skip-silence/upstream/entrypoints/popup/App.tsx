import { browser } from "#imports";
import {
  AppWindow,
  Coffee,
  FastForward,
  Volume2,
  VolumeX,
  Zap,
} from "lucide-react";
import { useEffect, useState } from "react";
import { IntroModal } from "@/components/IntroModal";
import { SpeedCard } from "@/components/SpeedCard";
import { VolumeMeter } from "@/components/VolumeMeter";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import {
  introSeen,
  settings,
  siteSettings,
  timeSavedMs,
  withDefaults,
  type Settings,
} from "@/lib/settings";
import { cn } from "@/lib/utils";
import logo from "~/assets/icon.svg";

function formatDuration(ms: number) {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes >= 60) return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

interface SettingRowProps {
  id: string;
  label: string;
  description: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

function SettingRow({
  id,
  label,
  description,
  checked,
  onChange,
}: SettingRowProps) {
  return (
    <div className="flex items-center justify-between gap-4 px-3.5 py-3">
      <div>
        <Label htmlFor={id} className="text-[13px] font-medium">
          {label}
        </Label>
        <p className="mt-0.5 text-[11px] leading-snug text-zinc-400">
          {description}
        </p>
      </div>
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
    </div>
  );
}

export default function App() {
  const [global, setGlobal] = useState<Settings>();
  const [sites, setSites] = useState<Record<string, Settings>>({});
  const [host, setHost] = useState<string>();
  const [saved, setSaved] = useState(0);
  const [showIntro, setShowIntro] = useState(false);

  useEffect(() => {
    settings.getValue().then((value) => setGlobal(withDefaults(value)));
    siteSettings.getValue().then(setSites);
    timeSavedMs.getValue().then(setSaved);
    introSeen.getValue().then((seen) => {
      if (!seen && Date.now() < Date.parse("2026-10-01")) setShowIntro(true);
    });
    browser.tabs
      .query({ active: true, currentWindow: true })
      .then(([tab]) => {
        // activeTab (granted by opening the popup) exposes the URL
        if (tab?.url?.startsWith("http")) setHost(new URL(tab.url).hostname);
      })
      .catch(() => {});
    return timeSavedMs.watch((value) => setSaved(value ?? 0));
  }, []);

  if (!global) return <div className="h-96 w-[340px]" />;

  const override = host ? sites[host] : undefined;
  const cfg = override ? withDefaults(override) : global;

  // Sliders update local state while dragging and persist on commit,
  // to stay under chrome.storage.sync write quotas.
  const stage = (patch: Partial<Settings>) => {
    const next = { ...cfg, ...patch };
    if (override && host) setSites({ ...sites, [host]: next });
    else setGlobal(next);
  };
  const commit = (patch: Partial<Settings>) => {
    const next = { ...cfg, ...patch };
    if (override && host) {
      const nextSites = { ...sites, [host]: next };
      setSites(nextSites);
      siteSettings.setValue(nextSites);
    } else {
      setGlobal(next);
      settings.setValue(next);
    }
  };
  const setSiteCustom = (custom: boolean) => {
    if (!host) return;
    const nextSites = { ...sites };
    if (custom) nextSites[host] = cfg;
    else delete nextSites[host];
    setSites(nextSites);
    siteSettings.setValue(nextSites);
  };

  const dismissIntro = () => {
    setShowIntro(false);
    introSeen.setValue(true);
  };

  return (
    <div className="relative w-[340px] bg-zinc-50">
      {showIntro && <IntroModal onDismiss={dismissIntro} />}
      <header className="flex items-center justify-between px-4 pb-3 pt-4">
        <div className="flex items-center gap-2.5">
          <img src={logo} className="size-8" />
          <div>
            <h1 className="text-sm font-semibold leading-none">Skip Silence</h1>
            <p
              className={cn(
                "mt-1 text-[11px] leading-none",
                cfg.enabled ? "text-zinc-400" : "font-medium text-lime-600",
              )}
            >
              {cfg.enabled
                ? "Watching for silence"
                : "Flip the switch to start →"}
            </p>
          </div>
        </div>
        <div className="relative">
          {!cfg.enabled && (
            <span
              aria-hidden
              className="absolute inset-0 animate-ping rounded-full bg-lime-400/60"
            />
          )}
          <Switch
            checked={cfg.enabled}
            onCheckedChange={(enabled) => commit({ enabled })}
            className="relative"
          />
        </div>
      </header>

      <main className="space-y-3 px-4 pb-4">
        <VolumeMeter enabled={cfg.enabled} />

        <div
          className={cn(
            "space-y-3 transition-opacity duration-200",
            !cfg.enabled && "pointer-events-none opacity-45",
          )}
        >
          <div className="grid grid-cols-2 gap-3">
            <SpeedCard
              icon={Volume2}
              label="Speech"
              value={cfg.playbackSpeed}
              min={0.5}
              max={4}
              step={0.25}
              onChange={(playbackSpeed) => stage({ playbackSpeed })}
              onCommit={(playbackSpeed) => commit({ playbackSpeed })}
            />
            <SpeedCard
              icon={FastForward}
              label="Silence"
              value={cfg.silenceSpeed}
              min={1}
              max={10}
              step={0.5}
              onChange={(silenceSpeed) => stage({ silenceSpeed })}
              onCommit={(silenceSpeed) => commit({ silenceSpeed })}
            />
          </div>

          <div className="divide-y divide-zinc-100 rounded-xl border border-zinc-200/80 bg-white shadow-[0_1px_2px_rgba(0,0,0,0.03)]">
            <SettingRow
              id="mute-silence"
              label="Mute silence"
              description="Silence leftover noise while skipping"
              checked={cfg.muteSilence}
              onChange={(muteSilence) => commit({ muteSilence })}
            />
            <SettingRow
              id="lookahead"
              label="Smooth transitions"
              description="Delays audio ~60 ms to unmute right as speech returns"
              checked={cfg.lookahead}
              onChange={(lookahead) => commit({ lookahead })}
            />
            <SettingRow
              id="dynamic-threshold"
              label="Auto threshold"
              description="Adapts to each video's noise floor"
              checked={cfg.dynamicThreshold}
              onChange={(dynamicThreshold) => commit({ dynamicThreshold })}
            />
            {!cfg.dynamicThreshold && (
              <div className="space-y-2.5 px-3.5 py-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 text-zinc-400">
                    <VolumeX className="size-3.5" strokeWidth={2} />
                    <span className="text-[11px] font-medium tracking-wide">
                      Silence below
                    </span>
                  </div>
                  <span className="text-xs font-medium tabular-nums text-zinc-500">
                    {cfg.manualThresholdDb} dB
                  </span>
                </div>
                <Slider
                  min={-70}
                  max={-10}
                  step={1}
                  value={[cfg.manualThresholdDb]}
                  onValueChange={([manualThresholdDb]) =>
                    stage({ manualThresholdDb })
                  }
                  onValueCommit={([manualThresholdDb]) =>
                    commit({ manualThresholdDb })
                  }
                />
              </div>
            )}
          </div>

          {host && (
            <div className="rounded-xl border border-zinc-200/80 bg-white shadow-[0_1px_2px_rgba(0,0,0,0.03)]">
              <SettingRow
                id="site-custom"
                label="Custom for this site"
                description={
                  override ? `Using separate settings on ${host}` : host
                }
                checked={!!override}
                onChange={setSiteCustom}
              />
            </div>
          )}
        </div>

        <div className="flex flex-col items-center justify-center gap-3 pt-0.5 text-[11px] font-medium text-zinc-400">
          {saved >= 1000 && (
            <span className="flex items-center gap-1">
              <Zap
                className="size-3 text-lime-500"
                fill="currentColor"
                strokeWidth={0}
              />
              Saved {formatDuration(saved)}
            </span>
          )}
          <a
            href="https://silence.vantezzen.io/"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1 transition-colors hover:text-lime-600"
          >
            <AppWindow className="size-3" />
            Remove silence from a file? Try the web app
          </a>
          <a
            href="https://www.buymeacoffee.com/vantezzen"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-1 transition-colors hover:text-lime-600"
          >
            <Coffee className="size-3" />
            Buy me a coffee
          </a>
        </div>
      </main>
    </div>
  );
}
