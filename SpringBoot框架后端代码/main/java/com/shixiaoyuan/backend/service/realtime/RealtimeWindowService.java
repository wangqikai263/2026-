package com.shixiaoyuan.backend.service.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixiaoyuan.backend.dto.request.RealtimeWindowReportRequest;
import com.shixiaoyuan.backend.entity.RealtimeWindowEventEntity;
import com.shixiaoyuan.backend.repository.RealtimeWindowEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RealtimeWindowService {

    private final RealtimeWindowEventRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.realtime.query.max-lookback-seconds:86400}")
    private int maxLookbackSeconds;

    @Value("${app.realtime.query.max-trend-minutes:1440}")
    private int maxTrendMinutes;

    public RealtimeWindowService(RealtimeWindowEventRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public RealtimeWindowEventEntity ingest(
            RealtimeWindowReportRequest req,
            String clientIp,
            String userAgent,
            String rawPayloadJson
    ) {
        validateRequest(req);

        BigDecimal startTs = normalizeDecimal(req.window.start_ts, 3);
        BigDecimal endTs = normalizeDecimal(req.window.end_ts, 3);
        Integer windowIndex = req.window.index == null ? 0 : req.window.index;

        Optional<RealtimeWindowEventEntity> existing = repo.findFirstByDeviceIdAndClassIdAndWindowIndexAndWindowStartTsAndWindowEndTs(
                clean(req.device_id),
                clean(req.class_id),
                windowIndex,
                startTs,
                endTs
        );

        RealtimeWindowEventEntity entity = existing.orElseGet(RealtimeWindowEventEntity::new);
        entity.setSchemaVersion(clean(req.schema_version));
        entity.setEventType(clean(req.event_type));
        entity.setGeneratedAt(parseIsoInstant(req.generated_at));
        entity.setDeviceId(clean(req.device_id));
        entity.setClassId(clean(req.class_id));

        entity.setWindowIndex(windowIndex);
        entity.setWindowStartTs(startTs);
        entity.setWindowEndTs(endTs);
        entity.setWindowStartAt(epochSecondsToInstant(req.window.start_ts));
        entity.setWindowEndAt(epochSecondsToInstant(req.window.end_ts));
        entity.setElapsedS(normalizeDecimal(req.window.elapsed_s, 3));

        entity.setFrameCount(nonNegative(req.window.frame_count));
        entity.setDetFrameCount(nonNegative(req.window.det_frame_count));
        entity.setAvgConf(normalizeDecimal(req.window.avg_conf, 6));
        entity.setDominantBehavior(clean(req.window.dominant_behavior));

        entity.setBehaviorCountsJson(toJsonOrNull(req.window.behavior_counts));
        entity.setBehaviorRatesJson(toJsonOrNull(req.window.behavior_rates));
        entity.setDetectionCountsJson(toJsonOrNull(req.window.detection_counts));

        if (req.runtime != null) {
            entity.setBackendName(clean(req.runtime.backend));
            entity.setInputMode(clean(req.runtime.input_mode));
            if (req.runtime.camera != null) {
                entity.setCameraSource(clean(req.runtime.camera.source));
                entity.setCameraWidth(req.runtime.camera.width);
                entity.setCameraHeight(req.runtime.camera.height);
                entity.setCameraFps(normalizeDecimal(req.runtime.camera.fps, 3));
            }
        }

        entity.setClientIp(clean(clientIp));
        entity.setUserAgent(trimTo(clean(userAgent), 255));
        entity.setPayloadJson(rawPayloadJson);

        return repo.save(entity);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLive(String classId, String deviceId, Integer lookbackSec, Integer limit) {
        int boundedLookback = Math.max(30, Math.min(lookbackSec == null ? 300 : lookbackSec, maxLookbackSeconds));
        int boundedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 5000));

        Instant from = Instant.now().minusSeconds(boundedLookback);
        List<RealtimeWindowEventEntity> rows = repo.findRecent(cleanNullable(classId), cleanNullable(deviceId), from, PageRequest.of(0, boundedLimit));
        rows.sort(Comparator.comparing(RealtimeWindowEventEntity::getWindowEndAt));

        Map<String, Integer> behaviorCounts = new LinkedHashMap<>();
        Map<String, Integer> detectionCounts = new LinkedHashMap<>();
        int totalFrames = 0;
        int detFrames = 0;
        double confWeighted = 0.0;
        double confWeight = 0.0;

        List<Map<String, Object>> windows = new ArrayList<>();
        for (RealtimeWindowEventEntity e : rows) {
            Map<String, Integer> counts = parseIntMap(e.getBehaviorCountsJson());
            mergeCounts(behaviorCounts, counts);
            mergeCounts(detectionCounts, parseIntMap(e.getDetectionCountsJson()));

            int frameCount = safeInt(e.getFrameCount());
            int detCount = safeInt(e.getDetFrameCount());
            totalFrames += frameCount;
            detFrames += detCount;
            if (e.getAvgConf() != null && frameCount > 0) {
                confWeighted += e.getAvgConf().doubleValue() * frameCount;
                confWeight += frameCount;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("deviceId", e.getDeviceId());
            item.put("classId", e.getClassId());
            item.put("windowIndex", e.getWindowIndex());
            item.put("startTs", decimalToDouble(e.getWindowStartTs()));
            item.put("endTs", decimalToDouble(e.getWindowEndTs()));
            item.put("elapsedS", decimalToDouble(e.getElapsedS()));
            item.put("frameCount", frameCount);
            item.put("detFrameCount", detCount);
            item.put("avgConf", decimalToDouble(e.getAvgConf()));
            item.put("dominantBehavior", e.getDominantBehavior());
            item.put("behaviorCounts", counts);
            item.put("behaviorRates", parseDoubleMap(e.getBehaviorRatesJson()));
            item.put("detectionCounts", parseIntMap(e.getDetectionCountsJson()));
            item.put("windowEndAt", e.getWindowEndAt() == null ? null : e.getWindowEndAt().toString());
            windows.add(item);
        }

        String dominantBehavior = null;
        int best = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> en : behaviorCounts.entrySet()) {
            if (en.getValue() > best) {
                best = en.getValue();
                dominantBehavior = en.getKey();
            }
        }

        Map<String, Double> behaviorRates = new LinkedHashMap<>();
        if (totalFrames > 0) {
            for (Map.Entry<String, Integer> en : behaviorCounts.entrySet()) {
                behaviorRates.put(en.getKey(), en.getValue() / (double) totalFrames);
            }
        }

        int totalDetections = 0;
        for (Integer v : detectionCounts.values()) {
            totalDetections += safeInt(v);
        }
        Map<String, Double> detectionRates = new LinkedHashMap<>();
        if (totalDetections > 0) {
            for (Map.Entry<String, Integer> en : detectionCounts.entrySet()) {
                detectionRates.put(en.getKey(), safeInt(en.getValue()) / (double) totalDetections);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("windowCount", rows.size());
        summary.put("totalFrames", totalFrames);
        summary.put("detFrameCount", detFrames);
        summary.put("avgConf", confWeight > 0 ? confWeighted / confWeight : 0.0);
        summary.put("dominantBehavior", dominantBehavior);
        summary.put("behaviorCounts", behaviorCounts);
        summary.put("behaviorRates", behaviorRates);
        summary.put("detectionCounts", detectionCounts);
        summary.put("totalDetections", totalDetections);
        summary.put("detectionRates", detectionRates);
        summary.put("latestWindowEndAt", rows.isEmpty() ? null : rows.get(rows.size() - 1).getWindowEndAt().toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("classId", cleanNullable(classId));
        query.put("deviceId", cleanNullable(deviceId));
        query.put("lookbackSec", boundedLookback);
        query.put("limit", boundedLimit);
        body.put("query", query);
        body.put("summary", summary);
        body.put("windows", windows);
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTrend(String classId, String deviceId, Integer minutes, Integer bucketSec) {
        int boundedMinutes = Math.max(1, Math.min(minutes == null ? 30 : minutes, maxTrendMinutes));
        int boundedBucketSec = Math.max(1, Math.min(bucketSec == null ? 3 : bucketSec, 3600));

        Instant from = Instant.now().minusSeconds((long) boundedMinutes * 60L);
        int maxRows = Math.max(200, boundedMinutes * 120);
        List<RealtimeWindowEventEntity> rows = repo.findRecent(cleanNullable(classId), cleanNullable(deviceId), from, PageRequest.of(0, maxRows));

        Map<Long, TrendBucket> buckets = new HashMap<>();
        for (RealtimeWindowEventEntity e : rows) {
            if (e.getWindowEndTs() == null) {
                continue;
            }
            long endSec = e.getWindowEndTs().setScale(0, RoundingMode.DOWN).longValue();
            long bucketStart = (endSec / boundedBucketSec) * boundedBucketSec;
            TrendBucket bucket = buckets.computeIfAbsent(bucketStart, key -> new TrendBucket(key.longValue()));
            bucket.windowCount += 1;
            int fc = safeInt(e.getFrameCount());
            int dc = safeInt(e.getDetFrameCount());
            bucket.frameCount += fc;
            bucket.detFrameCount += dc;
            if (e.getAvgConf() != null && fc > 0) {
                bucket.confWeighted += e.getAvgConf().doubleValue() * fc;
                bucket.confWeight += fc;
            }
            mergeCounts(bucket.behaviorCounts, parseIntMap(e.getBehaviorCountsJson()));
            mergeCounts(bucket.detectionCounts, parseIntMap(e.getDetectionCountsJson()));
        }

        List<TrendBucket> sorted = new ArrayList<>(buckets.values());
        sorted.sort(Comparator.comparingLong(b -> b.bucketStartTs));

        List<Map<String, Object>> bucketRows = new ArrayList<>();
        for (TrendBucket b : sorted) {
            Map<String, Double> rates = new LinkedHashMap<>();
            if (b.frameCount > 0) {
                for (Map.Entry<String, Integer> en : b.behaviorCounts.entrySet()) {
                    rates.put(en.getKey(), en.getValue() / (double) b.frameCount);
                }
            }
            String dominant = null;
            int best = Integer.MIN_VALUE;
            for (Map.Entry<String, Integer> en : b.behaviorCounts.entrySet()) {
                if (en.getValue() > best) {
                    best = en.getValue();
                    dominant = en.getKey();
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            int totalDetections = 0;
            for (Integer v : b.detectionCounts.values()) {
                totalDetections += safeInt(v);
            }
            Map<String, Double> detectionRates = new LinkedHashMap<>();
            if (totalDetections > 0) {
                for (Map.Entry<String, Integer> en : b.detectionCounts.entrySet()) {
                    detectionRates.put(en.getKey(), safeInt(en.getValue()) / (double) totalDetections);
                }
            }
            row.put("bucketStartTs", b.bucketStartTs);
            row.put("bucketEndTs", b.bucketStartTs + b.bucketSec);
            row.put("windowCount", b.windowCount);
            row.put("frameCount", b.frameCount);
            row.put("detFrameCount", b.detFrameCount);
            row.put("avgConf", b.confWeight > 0 ? b.confWeighted / b.confWeight : 0.0);
            row.put("dominantBehavior", dominant);
            row.put("behaviorCounts", b.behaviorCounts);
            row.put("behaviorRates", rates);
            row.put("detectionCounts", b.detectionCounts);
            row.put("totalDetections", totalDetections);
            row.put("detectionRates", detectionRates);
            bucketRows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("classId", cleanNullable(classId));
        query.put("deviceId", cleanNullable(deviceId));
        query.put("minutes", boundedMinutes);
        query.put("bucketSec", boundedBucketSec);
        body.put("query", query);
        body.put("buckets", bucketRows);
        return body;
    }

    private void validateRequest(RealtimeWindowReportRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is null");
        }
        if (isBlank(req.schema_version)) {
            throw new IllegalArgumentException("schema_version is required");
        }
        if (isBlank(req.event_type)) {
            throw new IllegalArgumentException("event_type is required");
        }
        if (isBlank(req.device_id)) {
            throw new IllegalArgumentException("device_id is required");
        }
        if (isBlank(req.class_id)) {
            throw new IllegalArgumentException("class_id is required");
        }
        if (req.window == null) {
            throw new IllegalArgumentException("window is required");
        }
        if (req.window.start_ts == null || req.window.end_ts == null) {
            throw new IllegalArgumentException("window.start_ts and window.end_ts are required");
        }
        if (req.window.end_ts < req.window.start_ts) {
            throw new IllegalArgumentException("window.end_ts must be >= window.start_ts");
        }
        if (req.window.frame_count == null || req.window.frame_count < 0) {
            throw new IllegalArgumentException("window.frame_count must be >= 0");
        }
        if (req.window.det_frame_count == null || req.window.det_frame_count < 0) {
            throw new IllegalArgumentException("window.det_frame_count must be >= 0");
        }
    }

    private static int nonNegative(Integer value) {
        return Math.max(0, value == null ? 0 : value);
    }

    private static String clean(String v) {
        return v == null ? null : v.trim();
    }

    private static String cleanNullable(String v) {
        String x = clean(v);
        return x == null || x.isEmpty() ? null : x;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static String trimTo(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    private BigDecimal normalizeDecimal(Double value, int scale) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private Instant epochSecondsToInstant(Double value) {
        if (value == null) {
            return Instant.now();
        }
        long millis = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(1000L))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return Instant.ofEpochMilli(millis);
    }

    private Instant parseIsoInstant(String v) {
        try {
            if (isBlank(v)) {
                return null;
            }
            return OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int safeInt(Integer v) {
        return v == null ? 0 : Math.max(0, v);
    }

    private Double decimalToDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private Map<String, Integer> parseIntMap(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Integer> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Double> parseDoubleMap(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Double> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private void mergeCounts(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> en : source.entrySet()) {
            int add = en.getValue() == null ? 0 : Math.max(0, en.getValue());
            target.merge(en.getKey(), Integer.valueOf(add), (left, right) -> Integer.valueOf(left.intValue() + right.intValue()));
        }
    }

    private static class TrendBucket {
        final long bucketStartTs;
        int bucketSec = 60;
        int windowCount = 0;
        int frameCount = 0;
        int detFrameCount = 0;
        double confWeighted = 0.0;
        double confWeight = 0.0;
        Map<String, Integer> behaviorCounts = new LinkedHashMap<>();
        Map<String, Integer> detectionCounts = new LinkedHashMap<>();

        TrendBucket(long bucketStartTs) {
            this.bucketStartTs = bucketStartTs;
        }
    }
}
