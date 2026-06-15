package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.CreateManufacturerRequest;
import com.cretas.aims.dto.material.ManufacturerRegistryDTO;
import com.cretas.aims.dto.material.UpdateManufacturerRequest;

import java.util.List;

public interface ManufacturerRegistryService {

    List<ManufacturerRegistryDTO> list(String factoryId, boolean activeOnly);

    ManufacturerRegistryDTO create(String factoryId, CreateManufacturerRequest request);

    ManufacturerRegistryDTO update(String factoryId, String id, UpdateManufacturerRequest request);

    void delete(String factoryId, String id);
}
