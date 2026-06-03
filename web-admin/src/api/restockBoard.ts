/** 备货看板 API */
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

/** 获取某交货日备货看板 */
export function getRestockBoard(factoryId: string, deliveryDate: string) {
  return get<RestockBoard>(`/${factoryId}/restock-board`, { params: { deliveryDate } })
}
