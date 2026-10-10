import { ExternalLink, Heart, Sparkles, Waves } from "lucide-react";
import icon from "~/assets/icon.svg";

const POINTS = [
  {
    icon: Sparkles,
    text: "Rewritten from scratch",
    subtext:
      "4 years after Skip Silence 5 - sorry for making you wait! Faster, smarter and more reliable on every site",
  },
  {
    icon: Waves,
    text: "Better dynamic volume detection",
    subtext:
      "Hopefully no manual adjustments needed for most sites, ever again",
  },
  {
    icon: Heart,
    text: "Completely free!",
    subtext:
      "Thanks to all Skip Silence Plus supporters, I can keep this version free for everyone.",
  },
] as const;

/** One-time welcome overlay for new installs and upgrades. */
export function IntroModal({ onDismiss }: { onDismiss: () => void }) {
  return (
    <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-6 bg-white/95 px-6 backdrop-blur-sm">
      <img src={icon} className="size-16 rounded-lg" />

      <div className="flex items-center gap-2.5">
        <h2 className="text-2xl font-bold tracking-tight">Skip Silence</h2>
        <span className="rounded-lg bg-gradient-to-br from-lime-300 via-lime-500 to-emerald-500 p-[2.5px] shadow-lime-500/25">
          <span className="flex size-10 items-center justify-center rounded-[8px] bg-white">
            <span className="bg-gradient-to-br from-lime-500 to-emerald-600 bg-clip-text text-3xl font-bold text-transparent">
              6
            </span>
          </span>
        </span>
      </div>

      <ul className="space-y-3">
        {POINTS.map(({ icon: Icon, text, subtext }) => (
          <li key={text} className="flex items-start gap-2.5">
            <Icon
              className="mt-0.5 size-3.5 shrink-0 text-lime-600"
              strokeWidth={2.25}
            />
            <div className="grid gap-1">
              <p className="leading-snug text-zinc-600">{text}</p>
              <p className="text-xs leading-snug text-zinc-400">{subtext}</p>
            </div>
          </li>
        ))}
      </ul>

      <a
        href="https://github.com/vantezzen/skip-silence/discussions/153"
        target="_blank"
        className="text-xs text-zinc-500 underline"
      >
        Read more or share feedback <ExternalLink className="inline size-3" />
      </a>

      <button
        onClick={onDismiss}
        className="w-full rounded-xl bg-gradient-to-br from-lime-400 to-lime-600 py-2.5 text-sm font-semibold text-white shadow-md shadow-lime-500/25 transition-transform active:scale-[0.98]"
      >
        Let's skip some silence
      </button>
    </div>
  );
}
