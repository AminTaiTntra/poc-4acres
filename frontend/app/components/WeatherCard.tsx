'use client'

import type { PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  insights: PatchInsights | undefined
  className?: string
}

export function WeatherCard({ insights, className }: Props) {
  const weather = insights?.weather

  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-sky-400 mb-2">☁️ Weather</p>
      {weather && weather.condition !== 'Unavailable' ? (
        <>
          <div className="flex items-center gap-2 mb-2">
            {weather.conditionIconUrl && (
              <img
                src={weather.conditionIconUrl}
                alt={weather.condition}
                className="w-8 h-8 flex-shrink-0"
              />
            )}
            <div>
              <p className="text-xl font-bold leading-none">{weather.tempC.toFixed(1)}°C</p>
              <p className="text-slate-400 text-xs mt-0.5">{weather.condition}</p>
            </div>
          </div>
          <div className="flex gap-3 text-slate-500 text-xs mb-3">
            <span>💨 {weather.windKph.toFixed(0)} km/h</span>
            <span>💧 {weather.humidity}%</span>
            <span>UV {weather.uvIndex.toFixed(0)}</span>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-0.5">
            {weather.forecast.map(day => {
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
