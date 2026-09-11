"use client";

import { useInsights } from "../lib/queries";
import { BiodiversityCard } from "./BiodiversityCard";
import { SoilCard } from "./SoilCard";
import { CarbonCard } from "./CarbonCard";
import { WaterCard } from "./WaterCard";
import { TerrainCard } from "./TerrainCard";
import { LandCoverCard } from "./LandCoverCard";
import { GeographicContextCard } from "./GeographicContextCard";
import { WeatherCard } from "./WeatherCard";
import { SatelliteCard } from "./SatelliteCard";

interface Props {
  patchId: string | null;
  patchName: string;
}

export function InsightsDrawer({ patchId, patchName }: Props) {
  const { data, isLoading, isError } = useInsights(patchId);
  console.log("DATA", data);

  if (!patchId) {
    return (
      <div
        className="w-96 flex-shrink-0 bg-slate-900/90 backdrop-blur border-l border-slate-700
                      flex items-center justify-center text-slate-500 text-sm p-6 text-center"
      >
        Select a patch to view environmental insights
      </div>
    );
  }

  return (
    <aside
      className="w-96 flex-shrink-0 bg-slate-900/90 backdrop-blur border-l border-slate-700
                      overflow-y-auto"
    >
      <div className="p-4 border-b border-slate-700">
        <h2 className="text-white font-semibold text-lg">{patchName}</h2>
        <p className="text-slate-400 text-xs mt-1">
          4 acres · Environmental insights
        </p>
      </div>

      <div className="p-4 space-y-4">
        {isLoading && (
          <>
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
          </>
        )}
        {isError && (
          <div className="text-red-400 text-sm bg-red-900/20 rounded-lg p-4">
            Failed to load insights. The external APIs may be unavailable.
          </div>
        )}
        {data && (
          <>
            <BiodiversityCard data={data.biodiversity} />
            <SoilCard data={data.soil} />
            <CarbonCard data={data.carbon} />
            <WaterCard data={data.water} />
            <TerrainCard data={data.terrain} />
            <LandCoverCard data={data.landCover} />
            <GeographicContextCard data={data.geographicContext} />
            <WeatherCard data={data.weather} />
            <SatelliteCard data={data.satellite} />
          </>
        )}
      </div>
    </aside>
  );
}

function SkeletonCard() {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-3 animate-pulse">
      <div className="h-4 bg-slate-700 rounded w-32" />
      <div className="grid grid-cols-3 gap-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="bg-slate-700 rounded-lg h-16" />
        ))}
      </div>
    </div>
  );
}
