package com.cretas.aims.dto.config;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EffectiveField {
    private String code;
    private String label;
    private String type;
    private boolean required;
    private boolean visible;
    private boolean readonly;
    private Object defaultValue;
    private Object options; // List<String> (Phase 2d) or List<Map> (Phase 1)
    private String group;
    private int order;
    private Map<String, Object> extra;
    private String visibleWhen;   // SpEL condition for frontend conditional rendering
    private String computedWhen;  // SpEL expression for frontend dynamic calculation
    private String source;        // "jpa" or "dynamic" — tells frontend which CRUD path to use
}
