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

export interface PatchInsights {
  biodiversity: BiodiversityData
  soil: SoilData
  carbon: CarbonData
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
