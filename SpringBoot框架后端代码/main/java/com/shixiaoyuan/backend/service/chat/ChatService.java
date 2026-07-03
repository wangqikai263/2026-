// src/main/java/com/shixiaoyuan/backend/service/chat/ChatService.java
package com.shixiaoyuan.backend.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixiaoyuan.backend.client.llm.LlmClient;
import com.shixiaoyuan.backend.prompts.PromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@Service
public class ChatService {
    private static final int MAX_TRACKS_FOR_LLM = 40;
    private static final int MAX_MINUTE_SEGMENTS_FOR_LLM = 240;
    private static final int DETECTION_HEAD_FOR_LLM = 10;
    private static final int DETECTION_KEY_MOMENTS_FOR_LLM = 12;

    private final LlmClient llmClient;
    private final PromptService promptService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.llm.model}")
    private String model;

    public ChatService(LlmClient llmClient, PromptService promptService) {
        this.llmClient = llmClient;
        this.promptService = promptService;
    }

    public Mono<String> chat(String message, Map<String, Object> classData) {
        ChatPromptContext context = buildChatPromptContext(message, classData);
        // 调用底层 LLM，并抽取 content 字段
        return llmClient.chat(model, context.systemPrompt(), context.userContent())
                .map(this::extractLlmContent)
                .onErrorResume(ex -> {
                    log.warn("[ChatService] 解析 LLM 响应失败，返回原始文本", ex);
                    return Mono.just("【系统提示】LLM 响应解析失败，原始内容如下：\n\n" + ex.getMessage());
                });
    }

    public Flux<String> chatStream(String message, Map<String, Object> classData) {
        ChatPromptContext context = buildChatPromptContext(message, classData);
        return llmClient.chatStream(model, context.systemPrompt(), context.userContent())
                .onErrorResume(ex -> {
                    log.warn("[ChatService] 流式响应失败，回退错误提示", ex);
                    return Flux.just("【系统提示】流式调用失败：" + ex.getMessage());
                });
    }

    private ChatPromptContext buildChatPromptContext(String message, Map<String, Object> classData) {
        // 1. 兜底
        Map<String, Object> safeData = (classData == null ? Map.of() : classData);
        // 2. 默认精简传输：只给 LLM 发送 summary + minute_segments + timeline_light + tracks
        Map<String, Object> payloadForLlm = Map.of();
        try {
            Map<String, Object> compactPayload = new LinkedHashMap<>();
            Map<String, Object> summarySubset = buildSummarySubset(safeData.get("summary"));
            if (!summarySubset.isEmpty()) {
                compactPayload.put("summary", summarySubset);
            }
            List<Map<String, Object>> minuteSegments =
                    buildMinuteDeviceUsageSegmentsSubset(safeData.get("device_usage_segments"));
            if (!minuteSegments.isEmpty()) {
                compactPayload.put("device_usage_segments", minuteSegments);
            }
            Map<String, Object> detectionTimelineLight =
                    buildDetectionStatsTimelineLight(safeData.get("detection_stats_timeline"));
            if (!detectionTimelineLight.isEmpty()) {
                compactPayload.put("detection_stats_timeline_light", detectionTimelineLight);
            }
            List<Map<String, Object>> tracksSubset = buildTracksSubset(safeData.get("tracks"));
            if (!tracksSubset.isEmpty()) {
                compactPayload.put("tracks", tracksSubset);
            }
            payloadForLlm = compactPayload;
        } catch (Exception e) {
            log.warn("[ChatService] 提取 summary/minute_segments/timeline_light/tracks 失败，回退为空 JSON", e);
            payloadForLlm = Map.of();
        }
        boolean useStructuredTemplate = wantsStructuredOutput(message);

        // 3. 转成 JSON 字符串（传给 LLM 的精简 classData）
        String classJson;
        try {
            classJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payloadForLlm);
        } catch (Exception e) {
            log.warn("[ChatService] 序列化 classData 失败，使用空 JSON", e);
            classJson = "{}";
        }

        log.info("[ChatService] 收到老师问题: {}", message);
        log.info("[ChatService] 传给 LLM 的课堂统计 JSON: {}", classJson);

        // 4. System Prompt（保持你当前使用的文件名）
        String systemPrompt = promptService.getSystemPrompt("system_prompt_shixiaoyuan_v1.2");

        String outputGuidance = useStructuredTemplate
                ? """
                输出模式：结构化课堂报告（用户已明确要求“生成课堂报告/结构化输出”）
                - 顶层标题使用：# 课堂分析结论
                - 建议使用以下层级：
                  ## 一、整体结论
                  ## 二、关键数据对比
                  ## 三、课堂问题诊断
                  ## 四、改进建议
                - 结合 JSON 里的比例、趋势、区间给出定量结论，不要空话。
                - 禁止输出具体累计检测次数（如 1150、2330 这类绝对计数）。
                """
                : """
                输出模式：默认灵活回答
                - 不要强行套固定模板，不强制“课堂报告”四段式。
                - 直接围绕老师本次问题，优先给最相关结论。
                - 可按需要使用短列表或小表格，但不是必须。
                - 若是追问，只回答追问点，避免重复整篇报告。
                - 禁止输出具体累计检测次数，优先用“高/中/低、上升/下降、占比变化”等表述。
                """;

        // 5. User 内容：包含老师问题 + 完整 JSON + 输出模式指令
        String userContent = """
                你是一名课堂分析助手，需要根据课堂行为分析 JSON 回答老师问题。

                【老师的问题】
                %s

                【课堂行为统计 JSON（完整）】
                %s

                【输出要求】
                %s

                如果 JSON 为 {} 或 summary 下没有有效字段，请直接说明“当前没有可用的统计数据，无法进行有效分析”。
                """.formatted(message, classJson, outputGuidance);
        return new ChatPromptContext(systemPrompt, userContent);
    }

    private record ChatPromptContext(String systemPrompt, String userContent) {}

    private boolean wantsStructuredOutput(String rawMessage) {
        String latest = extractLatestUserInput(rawMessage);
        String normalized = normalizeIntentText(latest);
        if (normalized.isEmpty()) {
            return false;
        }
        // 用户明确反向要求时，优先不套模板
        if (normalized.contains("不要结构化输出")
                || normalized.contains("不用结构化输出")
                || normalized.contains("不要生成课堂报告")
                || normalized.contains("不要报告格式")) {
            return false;
        }
        return normalized.contains("生成课堂报告")
                || normalized.contains("课堂报告")
                || normalized.contains("结构化输出")
                || normalized.contains("按模板输出")
                || normalized.contains("markdown报告");
    }

    private String extractLatestUserInput(String rawMessage) {
        String text = rawMessage == null ? "" : rawMessage;
        String marker = "老师本次输入：";
        int idx = text.lastIndexOf(marker);
        if (idx >= 0) {
            return text.substring(idx + marker.length()).trim();
        }
        return text.trim();
    }

    private String normalizeIntentText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\s+", "")
                .replace("：", ":")
                .toLowerCase();
    }

    private Map<String, Object> buildSummarySubset(Object summaryObj) {
        Map<String, Object> subset = new LinkedHashMap<>();
        if (!(summaryObj instanceof Map<?, ?> summaryMapRaw)) {
            return subset;
        }
        putIfNonNull(subset, "total_segments", summaryMapRaw.get("total_segments"));
        putIfNonNull(subset, "avg_confidence", summaryMapRaw.get("avg_confidence"));
        putIfNonNull(subset, "total_tracks", summaryMapRaw.get("total_tracks"));
        putIfNonNull(subset, "detection_stats", summaryMapRaw.get("detection_stats"));
        return subset;
    }

    private List<Map<String, Object>> buildMinuteDeviceUsageSegmentsSubset(Object segmentsObj) {
        if (!(segmentsObj instanceof List<?> segmentsRaw) || segmentsRaw.isEmpty()) {
            return List.of();
        }

        TreeMap<Integer, MinuteSegmentAccumulator> buckets = new TreeMap<>();
        Set<String> rateKeys = new LinkedHashSet<>();

        for (Object item : segmentsRaw) {
            if (!(item instanceof Map<?, ?> segmentRaw)) continue;
            double startS = Math.max(0, firstFinite(segmentRaw.get("start_s"), segmentRaw.get("start"), segmentRaw.get("ts_s"), 0));
            double endS = firstFinite(segmentRaw.get("end_s"), segmentRaw.get("end"), startS + 1);
            if (endS <= startS) {
                endS = startS + 1;
            }
            int minuteIdx = (int) Math.floor(startS / 60.0);
            MinuteSegmentAccumulator bucket = buckets.computeIfAbsent(minuteIdx, k -> new MinuteSegmentAccumulator((int) k));
            bucket.absorbWindow(startS, endS);

            for (Map.Entry<?, ?> entry : segmentRaw.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.endsWith("_rate")) continue;
                double value = toDoubleOrNaN(entry.getValue());
                if (!Double.isFinite(value)) continue;
                rateKeys.add(key);
                bucket.addRate(key, value);
            }
            double conf = firstFinite(segmentRaw.get("conf"), segmentRaw.get("avg_conf"), Double.NaN);
            if (Double.isFinite(conf)) {
                bucket.addConf(conf);
            }
        }
        if (buckets.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        List<String> orderedRateKeys = new ArrayList<>(rateKeys);
        int startMinuteIdx = buckets.firstKey();
        int endMinuteIdx = buckets.lastKey();
        for (int minuteIdx = startMinuteIdx; minuteIdx <= endMinuteIdx; minuteIdx++) {
            MinuteSegmentAccumulator bucket = buckets.get(minuteIdx);
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("index", out.size());
            segment.put("start_min", minuteIdx + 1);
            segment.put("end_min", minuteIdx + 1);
            segment.put("start_s", minuteIdx * 60.0);
            segment.put("end_s", (minuteIdx + 1) * 60.0);
            for (String rateKey : orderedRateKeys) {
                double avg = bucket == null ? 0.0 : bucket.avgRate(rateKey);
                segment.put(rateKey, avg);
            }
            if (bucket != null && bucket.confCount > 0) {
                segment.put("conf", bucket.confSum / bucket.confCount);
            }
            out.add(segment);
        }
        if (out.size() > MAX_MINUTE_SEGMENTS_FOR_LLM) {
            return new ArrayList<>(out.subList(0, MAX_MINUTE_SEGMENTS_FOR_LLM));
        }
        return out;
    }

    private List<Map<String, Object>> buildTracksSubset(Object tracksObj) {
        if (!(tracksObj instanceof List<?> tracksRaw) || tracksRaw.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> compactTracks = new ArrayList<>();
        for (Object item : tracksRaw) {
            if (!(item instanceof Map<?, ?> trackRaw)) continue;
            Map<String, Object> compact = new LinkedHashMap<>();
            putIfNonNull(compact, "track_id", trackRaw.get("track_id"));
            putIfNonNull(compact, "cls_name", trackRaw.get("cls_name"));
            putIfNonNull(compact, "cls_id", trackRaw.get("cls_id"));
            putIfNonNull(compact, "first_ts_s", trackRaw.get("first_ts_s"));
            putIfNonNull(compact, "last_ts_s", trackRaw.get("last_ts_s"));
            putIfNonNull(compact, "duration_s", trackRaw.get("duration_s"));
            putIfNonNull(compact, "total_frames", trackRaw.get("total_frames"));
            putIfNonNull(compact, "avg_conf", trackRaw.get("avg_conf"));
            if (!compact.isEmpty()) {
                compactTracks.add(compact);
            }
        }
        compactTracks.sort((a, b) -> {
            int byDuration = Double.compare(toDouble(b.get("duration_s")), toDouble(a.get("duration_s")));
            if (byDuration != 0) return byDuration;
            return Double.compare(toDouble(b.get("total_frames")), toDouble(a.get("total_frames")));
        });
        if (compactTracks.size() > MAX_TRACKS_FOR_LLM) {
            return new ArrayList<>(compactTracks.subList(0, MAX_TRACKS_FOR_LLM));
        }
        return compactTracks;
    }

    private Map<String, Object> buildDetectionStatsTimelineLight(Object timelineObj) {
        if (!(timelineObj instanceof List<?> timelineRaw) || timelineRaw.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> minuteTimeline = aggregateDetectionTimelineByMinute(timelineRaw);
        List<Map<String, Object>> compactTimeline = new ArrayList<>();
        for (Map<String, Object> minutePoint : minuteTimeline) {
            Map<String, Object> point = compactDetectionPoint(minutePoint);
            if (!point.isEmpty()) compactTimeline.add(point);
        }
        if (compactTimeline.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> head = new ArrayList<>();
        int headCount = Math.min(DETECTION_HEAD_FOR_LLM, compactTimeline.size());
        for (int i = 0; i < headCount; i++) {
            Map<String, Object> p = new LinkedHashMap<>(compactTimeline.get(i));
            p.put("pick_reason", "head");
            head.add(p);
        }

        Map<String, Map<String, Object>> keyMomentsMap = new LinkedHashMap<>();
        String prevClass = String.valueOf(compactTimeline.get(0).getOrDefault("dominant_class", "none"));
        for (int i = 1; i < compactTimeline.size(); i++) {
            String curClass = String.valueOf(compactTimeline.get(i).getOrDefault("dominant_class", "none"));
            if (!curClass.equals(prevClass) && !"none".equalsIgnoreCase(curClass)) {
                addKeyMoment(keyMomentsMap, compactTimeline.get(i), "dominant_class_change");
                if (keyMomentsMap.size() >= DETECTION_KEY_MOMENTS_FOR_LLM) {
                    break;
                }
            }
            prevClass = curClass;
        }

        List<Map<String, Object>> byObjects = new ArrayList<>(compactTimeline);
        byObjects.sort((a, b) -> {
            int cmp = Double.compare(toDouble(b.get("objects_total")), toDouble(a.get("objects_total")));
            if (cmp != 0) return cmp;
            return Double.compare(pointTimeValue(a), pointTimeValue(b));
        });
        for (Map<String, Object> p : byObjects) {
            if (toDouble(p.get("objects_total")) <= 0) continue;
            addKeyMoment(keyMomentsMap, p, "objects_peak");
            if (keyMomentsMap.size() >= DETECTION_KEY_MOMENTS_FOR_LLM) {
                break;
            }
        }

        if (keyMomentsMap.size() < DETECTION_KEY_MOMENTS_FOR_LLM) {
            List<Map<String, Object>> byConf = new ArrayList<>(compactTimeline);
            byConf.sort((a, b) -> {
                int cmp = Double.compare(toDouble(b.get("avg_conf")), toDouble(a.get("avg_conf")));
                if (cmp != 0) return cmp;
                return Double.compare(pointTimeValue(a), pointTimeValue(b));
            });
            for (Map<String, Object> p : byConf) {
                if (toDouble(p.get("avg_conf")) <= 0) continue;
                addKeyMoment(keyMomentsMap, p, "confidence_peak");
                if (keyMomentsMap.size() >= DETECTION_KEY_MOMENTS_FOR_LLM) {
                    break;
                }
            }
        }

        Map<String, Object> tail = compactTimeline.get(compactTimeline.size() - 1);
        String tailKey = buildPointKey(tail);
        if (!keyMomentsMap.containsKey(tailKey) && keyMomentsMap.size() >= DETECTION_KEY_MOMENTS_FOR_LLM) {
            String removeKey = null;
            double minTs = Double.POSITIVE_INFINITY;
            for (Map.Entry<String, Map<String, Object>> entry : keyMomentsMap.entrySet()) {
                double ts = pointTimeValue(entry.getValue());
                if (ts < minTs) {
                    minTs = ts;
                    removeKey = entry.getKey();
                }
            }
            if (removeKey != null) {
                keyMomentsMap.remove(removeKey);
            }
        }
        addKeyMoment(keyMomentsMap, tail, "tail_context");

        List<Map<String, Object>> keyMoments = new ArrayList<>(keyMomentsMap.values());
        keyMoments.sort(Comparator.comparingDouble(this::pointTimeValue));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time_unit", "minute");
        out.put("total_points", compactTimeline.size());
        out.put("head_limit", DETECTION_HEAD_FOR_LLM);
        out.put("key_moments_limit", DETECTION_KEY_MOMENTS_FOR_LLM);
        out.put("head", head);
        out.put("key_moments", keyMoments);
        return out;
    }

    private List<Map<String, Object>> aggregateDetectionTimelineByMinute(List<?> timelineRaw) {
        TreeMap<Integer, DetectionMinuteAccumulator> buckets = new TreeMap<>();
        for (Object item : timelineRaw) {
            if (!(item instanceof Map<?, ?> pointRaw)) continue;
            double tsS = firstFinite(pointRaw.get("ts_s"), pointRaw.get("start_s"), pointRaw.get("start"), Double.NaN);
            if (!Double.isFinite(tsS)) {
                double tsMin = firstFinite(pointRaw.get("ts_min"), null, Double.NaN);
                tsS = Double.isFinite(tsMin) ? Math.max(0, (tsMin - 1) * 60.0) : 0;
            }
            tsS = Math.max(0, tsS);
            int minuteIdx = (int) Math.floor(tsS / 60.0);
            DetectionMinuteAccumulator bucket = buckets.computeIfAbsent(minuteIdx, k -> new DetectionMinuteAccumulator());
            bucket.pointCount += 1;
            bucket.sumObjects += toDouble(pointRaw.get("objects_total"));
            bucket.sumTrackedObjects += toDouble(pointRaw.get("tracked_objects"));
            bucket.sumUniqueTrackIds += toDouble(pointRaw.get("unique_track_ids"));
            double avgConf = firstFinite(pointRaw.get("avg_conf"), null, Double.NaN);
             if (Double.isFinite(avgConf)) {
                bucket.sumAvgConf += avgConf;
                bucket.avgConfCount += 1;
            }

            Object countsObj = pointRaw.get("class_counts");
            if (countsObj instanceof Map<?, ?> countsMap) {
                for (Map.Entry<?, ?> entry : countsMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    double val = toDouble(entry.getValue());
                    if (val <= 0) continue;
                    bucket.classCounts.put(key, bucket.classCounts.getOrDefault(key, 0.0) + val);
                }
            }
        }

        if (buckets.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, DetectionMinuteAccumulator> entry : buckets.entrySet()) {
            int minuteIdx = entry.getKey();
            DetectionMinuteAccumulator bucket = entry.getValue();
            if (bucket.pointCount <= 0) continue;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("frame_idx", minuteIdx * 60);
            point.put("ts_min", minuteIdx + 1);
            point.put("window_min", 1);
            point.put("objects_total", bucket.sumObjects / bucket.pointCount);
            point.put("tracked_objects", bucket.sumTrackedObjects / bucket.pointCount);
            point.put("unique_track_ids", bucket.sumUniqueTrackIds / bucket.pointCount);
            if (bucket.avgConfCount > 0) {
                point.put("avg_conf", bucket.sumAvgConf / bucket.avgConfCount);
            }

            Map<String, Object> classCounts = new LinkedHashMap<>();
            double totalClassCount = 0;
            String dominantClass = "none";
            double dominantValue = -1;
            for (Map.Entry<String, Double> classEntry : bucket.classCounts.entrySet()) {
                double value = classEntry.getValue();
                classCounts.put(classEntry.getKey(), value);
                totalClassCount += value;
                if (value > dominantValue) {
                    dominantValue = value;
                    dominantClass = classEntry.getKey();
                }
            }
            point.put("class_counts", classCounts);
            point.put("dominant_class", dominantClass);
            point.put("dominant_ratio", totalClassCount > 0 && dominantValue > 0 ? (dominantValue / totalClassCount) : 0.0);
            out.add(point);
        }
        return out;
    }

    private Map<String, Object> compactDetectionPoint(Map<?, ?> pointRaw) {
        Map<String, Object> compact = new LinkedHashMap<>();
        putIfNonNull(compact, "frame_idx", pointRaw.get("frame_idx"));
        putIfNonNull(compact, "ts_min", pointRaw.get("ts_min"));
        putIfNonNull(compact, "window_min", pointRaw.get("window_min"));
        putIfNonNull(compact, "objects_total", pointRaw.get("objects_total"));
        putIfNonNull(compact, "tracked_objects", pointRaw.get("tracked_objects"));
        putIfNonNull(compact, "unique_track_ids", pointRaw.get("unique_track_ids"));
        putIfNonNull(compact, "dominant_class", pointRaw.get("dominant_class"));
        putIfNonNull(compact, "dominant_ratio", pointRaw.get("dominant_ratio"));
        putIfNonNull(compact, "avg_conf", pointRaw.get("avg_conf"));
        return compact;
    }

    private void addKeyMoment(Map<String, Map<String, Object>> keyMomentsMap,
                              Map<String, Object> point,
                              String reason) {
        String key = buildPointKey(point);
        Map<String, Object> existing = keyMomentsMap.get(key);
        if (existing == null) {
            Map<String, Object> copied = new LinkedHashMap<>(point);
            copied.put("pick_reason", reason);
            keyMomentsMap.put(key, copied);
            return;
        }
        String oldReason = String.valueOf(existing.getOrDefault("pick_reason", ""));
        if (!oldReason.contains(reason)) {
            existing.put("pick_reason", oldReason.isEmpty() ? reason : oldReason + "|" + reason);
        }
    }

    private String buildPointKey(Map<String, Object> point) {
        return String.valueOf(point.get("frame_idx")) + "@" + pointTimeValue(point);
    }

    private double pointTimeValue(Map<String, Object> point) {
        if (point == null) return 0.0;
        double tsMin = toDouble(point.get("ts_min"));
        if (tsMin > 0) {
            return tsMin;
        }
        return toDouble(point.get("ts_s")) / 60.0;
    }

    private double firstFinite(Object v1, Object v2, Object fallback) {
        double n1 = toDoubleOrNaN(v1);
        if (Double.isFinite(n1)) return n1;
        double n2 = toDoubleOrNaN(v2);
        if (Double.isFinite(n2)) return n2;
        return toDoubleOrNaN(fallback);
    }

    private double firstFinite(Object v1, Object v2, Object v3, Object fallback) {
        double n1 = toDoubleOrNaN(v1);
        if (Double.isFinite(n1)) return n1;
        double n2 = toDoubleOrNaN(v2);
        if (Double.isFinite(n2)) return n2;
        double n3 = toDoubleOrNaN(v3);
        if (Double.isFinite(n3)) return n3;
        return toDoubleOrNaN(fallback);
    }

    private static final class MinuteSegmentAccumulator {
        private double startS;
        private double endS;
        private double confSum;
        private int confCount;
        private final Map<String, Double> rateSums = new LinkedHashMap<>();
        private final Map<String, Integer> rateCounts = new LinkedHashMap<>();

        private MinuteSegmentAccumulator(int minuteIdx) {
            this.startS = minuteIdx * 60.0;
            this.endS = (minuteIdx + 1) * 60.0;
        }

        private void absorbWindow(double startS, double endS) {
            this.startS = Math.min(this.startS, startS);
            this.endS = Math.max(this.endS, endS);
        }

        private void addRate(String key, double value) {
            rateSums.put(key, rateSums.getOrDefault(key, 0.0) + value);
            rateCounts.put(key, rateCounts.getOrDefault(key, 0) + 1);
        }

        private double avgRate(String key) {
            double sum = rateSums.getOrDefault(key, 0.0);
            int count = rateCounts.getOrDefault(key, 0);
            return count > 0 ? (sum / count) : 0.0;
        }

        private void addConf(double conf) {
            confSum += conf;
            confCount += 1;
        }
    }

    private static final class DetectionMinuteAccumulator {
        private int pointCount;
        private double sumObjects;
        private double sumTrackedObjects;
        private double sumUniqueTrackIds;
        private double sumAvgConf;
        private int avgConfCount;
        private final Map<String, Double> classCounts = new LinkedHashMap<>();

        private DetectionMinuteAccumulator() {
        }
    }

    private void putIfNonNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private double toDoubleOrNaN(Object value) {
        if (value == null) return Double.NaN;
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private double toDouble(Object value) {
        double n = toDoubleOrNaN(value);
        return Double.isFinite(n) ? n : 0.0;
    }

    /**
     * 从 DeepSeek / OpenAI 风格响应中提取 choices[0].message.content
     */
    private String extractLlmContent(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode msgNode = choices.get(0).path("message");
                String content = msgNode.path("content").asText();
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }
        } catch (Exception e) {
            log.warn("[ChatService] 提取 content 失败，直接返回原始文本", e);
        }
        return raw;
    }
}
