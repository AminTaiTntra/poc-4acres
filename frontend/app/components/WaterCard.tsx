"use client";

import type { WaterData } from "../lib/types";

const GLASS =
  "bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white";

interface Props {
  data: WaterData | null;
  className?: string;
}

export function WaterCard({ data, className }: Props) {

  return (
    <div className={`${GLASS} ${className ?? ""}`}>
      <p className="text-xs font-semibold text-cyan-400 mb-2">💧 Water</p>
      {data ? (
        <>
          <div className="grid grid-cols-3 gap-2 mb-2">
            <Stat
              label="Detected"
              value={data.surfaceWaterHa.toFixed(2)}
              unit="ha"
            />
            <Stat label="Occurrence" value={data.occurrenceClass} unit="" />
            <Stat
              label="Recurrence"
              value={data.recurrencePercent.toFixed(0)}
              unit="%"
            />
          </div>
          <p className="text-slate-500 text-xs">
            From available satellite observations ({data.period}) — indicative,
            not a survey of every stream.
          </p>
        </>
      ) : (
        <div className="space-y-2">
          <div className="h-4 bg-white/10 rounded animate-pulse" />
          <div className="h-3 bg-white/10 rounded animate-pulse w-3/4" />
        </div>
      )}
    </div>
  );
}

function Stat({
  label,
  value,
  unit,
}: {
  label: string;
  value: string;
  unit: string;
}) {
  return (
    <div>
      <p className="text-slate-500 text-xs">{label}</p>
      <p className="text-sm font-medium leading-tight">
        {value}
        {unit && <span className="text-slate-400 text-xs ml-0.5">{unit}</span>}
      </p>
    </div>
  );
}
