package com.cretas.aims.controller;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.OssService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerProductImageTest {

    @Mock OssService ossService;

    @Test
    void validPngReadsMetadataThenUploadsToFactoryProductImagePath() {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=");
        MockMultipartFile file = new MockMultipartFile("file", "sku.png", "image/png", png);
        when(ossService.uploadImage(file, "product-images", "F006"))
                .thenReturn("https://media.example.com/F006/images/product-images/2026/07/16/a.png");

        var response = new FileUploadController(ossService).uploadProductImage("F006", file);

        assertThat(response.getData().get("url")).contains("/F006/images/product-images/");
        verify(ossService).uploadImage(file, "product-images", "F006");
    }

    @Test
    void spoofedImageContentIsRejectedBeforeOssUpload() {
        MockMultipartFile file = new MockMultipartFile("file", "sku.png", "image/png", "not-png".getBytes());

        assertThatThrownBy(() -> new FileUploadController(ossService).uploadProductImage("F006", file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JPEG/PNG");
        verify(ossService, never()).uploadImage(file, "product-images", "F006");
    }
}
