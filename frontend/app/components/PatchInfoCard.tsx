import type { ClaimDto, Patch, PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

function Skeleton() {
  return <div className="h-4 bg-white/10 rounded animate-pulse" />
}

interface IdentityProps {
  type: 'identity'
  patch: Patch
  claim: ClaimDto | null | undefined
  className?: string
}
interface MetricProps {
  type: 'biodiversity' | 'soil' | 'carbon'
  insights: PatchInsights | null | undefined
  className?: string
}
type Props = IdentityProps | MetricProps

export function PatchInfoCard(props: Props) {
  if (props.type === 'identity') {
    const { patch, claim } = props
    return (
      <div className={`${GLASS} ${props.className ?? ''}`}>
        <div className="flex items-start justify-between gap-2 mb-2">
          <h1 className="text-base font-bold leading-tight">{patch.name}</h1>
          <span className={`text-xs px-2 py-0.5 rounded-full font-medium flex-shrink-0 ${
            patch.status === 'CLAIMED' ? 'bg-emerald-500/30 text-emerald-300' : 'bg-amber-500/30 text-amber-300'
          }`}>
            {patch.status === 'CLAIMED' ? 'Claimed' : 'Available'}
          </span>
        </div>
        <p className="text-slate-400 text-xs mb-3">{patch.ecosystemType} · {patch.country}</p>
        {patch.ownerName && (
          <p className="text-slate-300 text-xs mb-1">
            <span className="text-slate-500">Land owner</span> {patch.ownerName}
          </p>
        )}
        {claim ? (
          <p className="text-slate-300 text-xs">
            <span className="text-slate-500">Steward</span> {claim.stewardName}
          </p>
        ) : (
          <p className="text-slate-500 text-xs">Unclaimed — visit /explore to claim</p>
        )}
      </div>
    )
  }

  if (props.type === 'biodiversity') {
    const bio = props.insights?.biodiversity
    return (
      <div className={`${GLASS} ${props.className ?? ''}`}>
        <p className="text-xs font-semibold text-emerald-400 mb-2">🌿 Biodiversity</p>
        {bio ? (
          <>
            <p className="text-2xl font-bold">{bio.speciesCount}</p>
            <p className="text-slate-400 text-xs mb-1">species recorded</p>
            <p className="text-slate-300 text-xs">{bio.threatenedCount} threatened</p>
            {bio.topSpecies[0] && (
              <p className="text-slate-500 text-xs mt-1 truncate">{bio.topSpecies[0].name}</p>
            )}
          </>
        ) : <Skeleton />}
      </div>
    )
  }

  if (props.type === 'soil') {
    const soil = props.insights?.soil
    return (
      <div className={`${GLASS} ${props.className ?? ''}`}>
        <p className="text-xs font-semibold text-amber-400 mb-2">🌱 Soil Health</p>
        {soil ? (
          <div className="grid grid-cols-3 gap-2 text-center">
            <div>
              <p className="text-lg font-bold">{soil.organicCarbonGKg.toFixed(1)}</p>
              <p className="text-slate-400 text-xs">g/kg C</p>
            </div>
            <div>
              <p className="text-lg font-bold">{soil.ph.toFixed(1)}</p>
              <p className="text-slate-400 text-xs">pH</p>
            </div>
            <div>
              <p className="text-lg font-bold">{soil.clayPercent.toFixed(0)}%</p>
              <p className="text-slate-400 text-xs">clay</p>
            </div>
          </div>
        ) : <Skeleton />}
      </div>
    )
  }

  // carbon
  const carbon = props.insights?.carbon
  return (
    <div className={`${GLASS} ${props.className ?? ''}`}>
      <p className="text-xs font-semibold text-blue-400 mb-2">🌲 Carbon / Forest</p>
      {carbon ? (
        <div className="grid grid-cols-3 gap-2 text-center">
          <div>
            <p className="text-lg font-bold">{carbon.treeCoverPercent.toFixed(1)}%</p>
            <p className="text-slate-400 text-xs">tree cover</p>
          </div>
          <div>
            <p className="text-lg font-bold">{carbon.carbonDensityMgHa.toFixed(1)}</p>
            <p className="text-slate-400 text-xs">Mg/ha C</p>
          </div>
          <div>
            <p className="text-lg font-bold">{carbon.coverLossHa.toFixed(2)}</p>
            <p className="text-slate-400 text-xs">ha lost</p>
          </div>
        </div>
      ) : <Skeleton />}
    </div>
  )
}
