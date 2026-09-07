import type { BiodiversityData } from '../lib/types'

export function BiodiversityCard({ data }: { data: BiodiversityData }) {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-4">
      <h3 className="text-emerald-400 font-semibold flex items-center gap-2">
        <span>🌿</span> Biodiversity
      </h3>
      <div className="grid grid-cols-2 gap-3">
        <Stat label="Species recorded" value={data.speciesCount.toString()} />
        <Stat label="Threatened" value={data.threatenedCount.toString()} highlight={data.threatenedCount > 0} />
      </div>
      {data.topSpecies.length > 0 && (
        <div>
          <p className="text-xs text-slate-400 mb-2 uppercase tracking-wider">Top Species</p>
          <ul className="space-y-1">
            {data.topSpecies.map(s => (
              <li key={s.name} className="text-sm flex justify-between">
                <span className="text-slate-200 italic">{s.name}</span>
                <span className="text-slate-500 text-xs">{s.kingdom}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-3">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className={`text-2xl font-bold ${highlight ? 'text-red-400' : 'text-white'}`}>{value}</p>
    </div>
  )
}
