package com.cretas.aims.dto.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@NoArgsConstructor
@Schema(description = "分页响应对象")
public class PageResponse<T> {

    @Schema(description = "数据列表")
    private List<T> content;

    @Schema(description = "当前页码", example = "1")
    private Integer page;

    @JsonProperty("currentPage")
    @Schema(description = "当前页码（与page相同，为兼容性保留）", example = "1")
    private Integer currentPage;

    @Schema(description = "每页大小", example = "20")
    private Integer size;

    @Schema(description = "总记录数", example = "100")
    private Long totalElements;

    @Schema(description = "总页数", example = "5")
    private Integer totalPages;

    @Schema(description = "是否第一页", example = "true")
    private Boolean first;

    @Schema(description = "是否最后一页", example = "false")
    private Boolean last;

    public static <T> PageResponse<T> of(List<T> content, Integer page, Integer size, Long totalElements) {
        PageResponse<T> response = new PageResponse<>();
        response.setContent(content);
        response.setPage(page);
        response.setCurrentPage(page); // 同时设置currentPage字段
        response.setSize(size);
        response.setTotalElements(totalElements);
        response.setTotalPages((int) Math.ceil((double) totalElements / size));
        response.setFirst(page == 1);
        response.setLast(page >= response.getTotalPages());
        return response;
    }

    // Manual getters and setters (backup for Lombok)
    public List<T> getContent() {
        return content;
    }

    public void setContent(List<T> content) {
        this.content = content;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(Integer currentPage) {
        this.currentPage = currentPage;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public Long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(Long totalElements) {
        this.totalElements = totalElements;
    }

    public Integer getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(Integer totalPages) {
        this.totalPages = totalPages;
    }

    public Boolean getFirst() {
        return first;
    }

    public void setFirst(Boolean first) {
        this.first = first;
    }

    public Boolean getLast() {
        return last;
    }

    public void setLast(Boolean last) {
        this.last = last;
    }

    // ==================== 前端字段别名 ====================

    /**
     * number 别名（兼容前端）
     * 前端使用 number，后端使用 page
     * Spring Data JPA 的 Page 对象使用 number 表示当前页（从0开始）
     * 但我们的 page 是从1开始的，所以这里返回 page - 1 来兼容前端
     */
    @JsonProperty("number")
    @Schema(description = "当前页码(前端别名，从0开始)")
    public Integer getNumber() {
        return page != null ? page - 1 : 0;
    }

    /**
     * empty 别名（兼容前端）
     * 表示内容是否为空
     */
    @JsonProperty("empty")
    @Schema(description = "是否为空")
    public Boolean getEmpty() {
        return content == null || content.isEmpty();
    }
}