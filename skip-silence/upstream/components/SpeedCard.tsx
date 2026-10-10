import type { LucideIcon } from 'lucide-react';
import { Slider } from '@/components/ui/slider';

interface SpeedCardProps {
  icon: LucideIcon;
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (value: number) => void;
  onCommit: (value: number) => void;
}

/** Card with a large speed readout and a slider underneath. */
export function SpeedCard({ icon: Icon, label, value, min, max, step, onChange, onCommit }: SpeedCardProps) {
  return (
    <div className="space-y-2.5 rounded-xl border border-zinc-200/80 bg-white p-3 shadow-[0_1px_2px_rgba(0,0,0,0.03)]">
      <div className="flex items-center gap-1.5 text-zinc-400">
        <Icon className="size-3.5" strokeWidth={2} />
        <span className="text-[11px] font-medium tracking-wide">{label}</span>
      </div>
      <div className="text-2xl font-semibold tracking-tight tabular-nums">
        {value}
        <span className="ml-0.5 text-sm font-medium text-zinc-400">×</span>
      </div>
      <Slider
        min={min}
        max={max}
        step={step}
        value={[value]}
        onValueChange={([v]) => onChange(v)}
        onValueCommit={([v]) => onCommit(v)}
      />
    </div>
  );
}
