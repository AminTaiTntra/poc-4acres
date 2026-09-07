import type { SoilData } from '../lib/types'

export function SoilCard({ data }: { data: SoilData }) {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-4">
      <h3 className="text-amber-400 font-semibold flex items-center gap-2">
        <span>🌱</span> Soil Health
      </h3>
      <div className="grid grid-cols-3 gap-3">
        <Stat label="Organic carbon" value={data.organicCarbonGKg.toFixed(1)} unit="g/kg" />
        <Stat label="pH" value={data.ph.toFixed(1)} unit="" />
        <Stat label="Clay" value={data.clayPercent.toFixed(1)} unit="%" />
      </div>
    </div>
  )
}

function Stat({ label, value, unit }: { label: string; value: string; unit: string }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-3">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className="text-xl font-bold text-white">
        {value}
        {unit && <span className="text-sm font-normal text-slate-400 ml-1">{unit}</span>}
      </p>
    </div>
  )
}
