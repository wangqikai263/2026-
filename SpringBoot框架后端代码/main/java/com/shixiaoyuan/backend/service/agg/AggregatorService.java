// service/agg/AggregatorService.java
package com.shixiaoyuan.backend.service.agg;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AggregatorService {

    // 参数为各个小模型的结果 Map（已经统一为 Map 后，由这里对齐时间线）
    public Map<String, Object> aggregate(List<Map<String, Object>> moduleResults) {
        // 返回结构必须带 schema_version，比如 "schema_version": "0.1.0"
        return Map.of(
                "schema_version", "0.1.0",
                "modules_present", List.of("focus", "phone", "movement")
                // ... 以及 segments/events 等
        );
    }
}
