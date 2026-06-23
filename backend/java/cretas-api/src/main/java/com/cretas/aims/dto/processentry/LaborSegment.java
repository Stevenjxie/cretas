package com.cretas.aims.dto.processentry;

import lombok.Data;

/** 工时记录单时段 (SP-F): 起止时间 + 人数。多段由 computeLaborCost 求和。 */
@Data
public class LaborSegment {
    private String startTime;   // "HH:mm"
    private String endTime;     // "HH:mm"
    private Integer workerCount;
}
