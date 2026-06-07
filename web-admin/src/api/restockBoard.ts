import { get } from './request'

export interface RestockRow {
  productTypeId: string
  productName: string
  unit: string
  demandQty: number | null
  fgAvailableQty: number
  wipEstimatedQty: number | null
  scheduledQty: number
  totalAvailableQty: number | null
  shortfallQty: number | null
  status: 'SATISFIED' | 'SHORTFALL' | 'UNIT_INCONSISTENT'
  wipIsEstimated: boolean
  conversionWarning: string | null
}

export interface RestockBoard {
  deliveryDate: string
  rows: RestockRow[]
  summary: { totalProducts: number; shortfallProducts: number; fullySatisfiedProducts: number }
}

export interface RestockHorizonDayCell {
  deliveryDate: string
  demandQty: number
  availableBeforeDemandQty: number
  availableAfterDemandQty: number
  shortfallQty: number | null
  warning: string | null
  status: 'COVERED' | 'SHORTFALL' | 'UNIT_INCONSISTENT'
}

export interface RestockHorizonProductRow {
  productTypeId: string
  productName: string
  unit: string
  totalDemandQty: number
  fgAvailableQty: number
  /** WH-LOG 物流仓现货（盒）。只有此仓成品可直接发货；车间仓需先调拨过来。T134 */
  fgShippableQty: number
  wipAvailableQty: number
  wipEstimatedQty: number | null
  scheduledQty: number
  rawAvailableQty: number | null
  rawUnit: string | null
  rawMaterialName: string | null
  rawEstimatedFgQty: number | null
  rawToWipYield: number | null
  wipToFgYield: number | null
  rawToFgYield: number | null
  startingCoverQty: number
  endingAvailableQty: number
  endingShortfallQty: number
  conversionWarning: string | null
  days: RestockHorizonDayCell[]
}

export interface RestockHorizon {
  startDate: string
  endDate: string
  dates: string[]
  rows: RestockHorizonProductRow[]
  summary: { totalProducts: number; shortfallProducts: number; fullyCoveredProducts: number; days: number }
}

export function getRestockBoard(factoryId: string, deliveryDate: string) {
  return get<RestockBoard>(`/${factoryId}/restock-board`, { params: { deliveryDate } })
}

export function getRestockHorizon(factoryId: string, startDate: string, endDate: string) {
  return get<RestockHorizon>(`/${factoryId}/restock-board/horizon`, { params: { startDate, endDate } })
}
