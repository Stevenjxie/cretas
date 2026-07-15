package com.cretas.aims.service.productimport;

import com.cretas.aims.dto.producttype.importing.SkuImportConfirmResultDTO;
import com.cretas.aims.dto.producttype.importing.SkuImportPreviewDTO;
import org.springframework.web.multipart.MultipartFile;

public interface SkuImportService {
    byte[] createTemplate();

    SkuImportPreviewDTO preview(String factoryId, Long userId, MultipartFile file, String imageMappingsJson);

    SkuImportConfirmResultDTO confirm(String factoryId, Long userId, String previewToken);
}
