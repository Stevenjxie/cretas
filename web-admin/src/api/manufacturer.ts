import { del, get, post, put } from './request';

export interface ManufacturerRegistry {
  id: string;
  factoryId: string;
  code: string;
  name: string;
  originPlace?: string | null;
  isActive: boolean;
  remark?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface ManufacturerPayload {
  code?: string;
  name?: string;
  originPlace?: string | null;
  isActive?: boolean;
  remark?: string | null;
}

export function listManufacturers(factoryId: string, active = false) {
  return get<ManufacturerRegistry[]>(`/${factoryId}/manufacturers`, {
    params: active ? { active: true } : undefined,
  });
}

export function createManufacturer(factoryId: string, payload: ManufacturerPayload) {
  return post<ManufacturerRegistry>(`/${factoryId}/manufacturers`, payload);
}

export function updateManufacturer(factoryId: string, id: string, payload: ManufacturerPayload) {
  return put<ManufacturerRegistry>(`/${factoryId}/manufacturers/${id}`, payload);
}

export function deleteManufacturer(factoryId: string, id: string) {
  return del<void>(`/${factoryId}/manufacturers/${id}`);
}
