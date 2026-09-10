// frontend/app/lib/types.ts

export interface SpeciesEntry {
  name: string
  kingdom: string
}

export interface BiodiversityData {
  speciesCount: number
  topSpecies: SpeciesEntry[]
  threatenedCount: number
}

export interface SoilData {
  organicCarbonGKg: number
  ph: number
  clayPercent: number
}

export interface CarbonData {
  treeCoverPercent: number
  carbonDensityMgHa: number
  coverLossHa: number
}

export interface GeographicContextData {
  placeName: string
  neighborhood: string | null
  city: string
  region: string
  country: string
  fullAddress: string
}

export interface ForecastDay {
  date: string
  maxTempC: number
  minTempC: number
  condition: string
}

export interface WeatherData {
  tempC: number
  condition: string
  conditionIconUrl: string
  windKph: number
  humidity: number
  uvIndex: number
  forecast: ForecastDay[]
}

export interface SatelliteSceneData {
  hasRecentScene: boolean
  latestSceneDate: string | null
  cloudCoverPercent: number
  productType: string | null
  thumbnailUrl: string | null
}

export interface LandCoverClassShare {
  className: string
  percent: number
  areaAcres: number
  areaHectares: number
}

export interface LandCoverYear {
  year: string
  classes: LandCoverClassShare[]
}

export interface LandCoverChange {
  className: string
  startPercent: number
  endPercent: number
  deltaPercent: number
}

export interface LandCoverTransition {
  fromClass: string
  toClass: string
  areaAcres: number
  percent: number
}

export interface LandCoverData {
  hasData: boolean
  source: string
  probabilityThreshold: number
  confidentPercent: number
  parcelAcres: number
  observationCount: number
  latestPeriod: string | null
  composition: LandCoverClassShare[]
  history: LandCoverYear[]
  changes: LandCoverChange[]
  transitions: LandCoverTransition[]
  trend: string
}

export interface PatchInsights {
  biodiversity: BiodiversityData
  soil: SoilData
  carbon: CarbonData
  geographicContext: GeographicContextData | null
  weather: WeatherData | null
  satellite: SatelliteSceneData | null
  landCover: LandCoverData | null
}

export interface BoundaryGeoJson {
  type: 'Polygon'
  coordinates: number[][][]
}

export type PatchStatus = 'AVAILABLE' | 'CLAIMED'

export interface Patch {
  id: string
  name: string
  ecosystemType: string
  country: string
  centerLat: number
  centerLng: number
  boundaryGeoJson: BoundaryGeoJson
  status: PatchStatus
  ownerName: string | null
}

export interface ClaimDto {
  id: string
  patchId: string
  stewardName: string
  stewardEmail: string
  claimedAt: string
}

export interface GeoJsonPolygon {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface PatchRegistrationRequest {
  name: string
  description?: string
  ownerName: string
  country: string
  ecosystemType: string
  boundary: GeoJsonPolygon
}

export interface ClaimRequest {
  stewardName: string
  stewardEmail: string
}
