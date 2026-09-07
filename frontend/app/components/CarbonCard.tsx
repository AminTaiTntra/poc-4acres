import type { CarbonData } from '../lib/types'

export function CarbonCard({ data }: { data: CarbonData }) {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-4">
      <h3 className="text-blue-400 font-semibold flex items-center gap-2">
        <span>🌳</span> Carbon / Forest
      </h3>
      <div className="grid grid-cols-3 gap-3">
        <Stat label="Tree cover" value={data.treeCoverPercent.toFixed(1)} unit="%" />
        <Stat label="Carbon density" value={data.carbonDensityMgHa.toFixed(1)} unit="Mg/ha" />
        <Stat label="Cover loss" value={data.coverLossHa.toFixed(2)} unit="ha" highlight={data.coverLossHa > 0.1} />
      </div>
    </div>
  )
}

function Stat({ label, value, unit, highlight }: { label: string; value: string; unit: string; highlight?: boolean }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-3">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className={`text-xl font-bold ${highlight ? 'text-red-400' : 'text-white'}`}>
        {value}
        {unit && <span className="text-sm font-normal text-slate-400 ml-1">{unit}</span>}
      </p>
    </div>
  )
}
