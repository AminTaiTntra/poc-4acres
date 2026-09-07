'use client'

import type { WeatherData } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  data: WeatherData | null
  className?: string
}

export function WeatherCard({ data, className }: Props) {
  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-sky-400 mb-2">☁️ Weather</p>
      {data && data.condition !== 'Unknown' ? (
        <>
          <div className="flex items-center gap-2 mb-2">
            {data.conditionIconUrl && (
              <img
                src={data.conditionIconUrl}
                alt={data.condition}
                className="w-8 h-8 flex-shrink-0"
              />
            )}
            <div>
              <p className="text-xl font-bold leading-none">{data.tempC.toFixed(1)}°C</p>
              <p className="text-slate-400 text-xs mt-0.5">{data.condition}</p>
            </div>
          </div>
          <div className="flex gap-3 text-slate-500 text-xs mb-3">
            <span>💨 {data.windKph.toFixed(0)} km/h</span>
            <span>💧 {data.humidity}%</span>
            <span>UV {data.uvIndex.toFixed(0)}</span>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-0.5">
            {data.forecast.map(day => {
              const label = new Date(day.date + 'T12:00:00')
                .toLocaleDateString('en', { weekday: 'short' })
              return (
                <div key={day.date} className="text-center flex-shrink-0 min-w-[28px]">
                  <p className="text-slate-500 text-xs">{label}</p>
                  <p className="text-xs font-medium">{day.maxTempC.toFixed(0)}°</p>
                  <p className="text-slate-500 text-xs">{day.minTempC.toFixed(0)}°</p>
                </div>
              )
            })}
          </div>
        </>
      ) : (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      )}
    </div>
  )
}
