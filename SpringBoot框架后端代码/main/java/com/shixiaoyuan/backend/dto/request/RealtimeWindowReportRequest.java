package com.shixiaoyuan.backend.dto.request;

import java.util.Map;

public class RealtimeWindowReportRequest {
    public String schema_version;
    public String event_type;
    public String generated_at;
    public String device_id;
    public String class_id;
    public Window window;
    public RuntimeInfo runtime;

    public static class Window {
        public Integer index;
        public Double start_ts;
        public Double end_ts;
        public Double elapsed_s;
        public Integer frame_count;
        public Integer det_frame_count;
        public Double avg_conf;
        public String dominant_behavior;
        public Map<String, Integer> behavior_counts;
        public Map<String, Double> behavior_rates;
        public Map<String, Integer> detection_counts;
    }

    public static class RuntimeInfo {
        public String backend;
        public String input_mode;
        public Camera camera;
    }

    public static class Camera {
        public String source;
        public Integer width;
        public Integer height;
        public Double fps;
    }
}
