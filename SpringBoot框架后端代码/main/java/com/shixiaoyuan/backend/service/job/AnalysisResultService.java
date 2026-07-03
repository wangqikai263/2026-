package com.shixiaoyuan.backend.service.job;

import com.shixiaoyuan.backend.entity.AnalysisSegmentEntity;
import com.shixiaoyuan.backend.entity.TrackSummaryEntity;
import com.shixiaoyuan.backend.repository.AnalysisSegmentRepository;
import com.shixiaoyuan.backend.repository.TrackSummaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisResultService {

    private final AnalysisSegmentRepository analysisSegmentRepository;
    private final TrackSummaryRepository trackSummaryRepository;

    public AnalysisResultService(
            AnalysisSegmentRepository analysisSegmentRepository,
            TrackSummaryRepository trackSummaryRepository
    ) {
        this.analysisSegmentRepository = analysisSegmentRepository;
        this.trackSummaryRepository = trackSummaryRepository;
    }

    public void persistSegments(Long sessionId, Map<String, Object> analysis) {
        Object segmentsObj = analysis.get("segments");
        if (!(segmentsObj instanceof List<?> segments)) {
            Object deviceUsageObj = analysis.get("device_usage_segments");
            if (deviceUsageObj instanceof List<?> deviceUsageSegments) {
                persistDeviceUsageSegments(sessionId, deviceUsageSegments);
                return;
            }
            Object timelineObj = analysis.get("timeline");
            if (timelineObj instanceof List<?> timeline) {
                persistTimelineSegments(sessionId, timeline);
            }
            return;
        }
        List<AnalysisSegmentEntity> entities = new ArrayList<>();
        for (Object segmentObj : segments) {
            if (!(segmentObj instanceof Map<?, ?> segmentMap)) {
                continue;
            }
            Integer startS = readInt(segmentMap, "start_s", "start");
            Integer endS = readInt(segmentMap, "end_s", "end");
            if (startS == null || endS == null) {
                continue;
            }
            AnalysisSegmentEntity entity = new AnalysisSegmentEntity();
            entity.setSessionId(sessionId);
            entity.setStartS(startS);
            entity.setEndS(endS);
            entity.setFocusRate(readDecimal(segmentMap, "focus_rate", "focus"));
            entity.setPhoneRate(readDecimal(segmentMap, "phone_rate", "phone"));
            entity.setHandRaiseCnt(readInt(segmentMap, "hand_raise_cnt", "hand_raise"));
            entity.setTeacherMoveFreqPerMin(readDecimal(segmentMap, "teacher_move_freq_per_min", "teacher_move_freq"));
            entity.setStabilityStd(readDecimal(segmentMap, "stability_std", "stability"));
            entity.setConf(readDecimal(segmentMap, "conf", "confidence"));
            entity.setClipUrl(readString(segmentMap, "clip_url", "clip"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            analysisSegmentRepository.saveAll(entities);
        }
    }

    private void persistDeviceUsageSegments(Long sessionId, List<?> deviceUsageSegments) {
        List<AnalysisSegmentEntity> entities = new ArrayList<>();
        for (Object itemObj : deviceUsageSegments) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) {
                continue;
            }
            Integer startS = readInt(itemMap, "start_s", "start");
            Integer endS = readInt(itemMap, "end_s", "end");
            if (startS == null || endS == null) {
                continue;
            }
            AnalysisSegmentEntity entity = new AnalysisSegmentEntity();
            entity.setSessionId(sessionId);
            entity.setStartS(startS);
            entity.setEndS(endS);
            entity.setFocusRate(readDecimal(itemMap, "listening_rate", "focus_rate", "focus"));
            entity.setPhoneRate(readDecimal(itemMap, "Using_Phone_rate", "phone_rate", "phone"));
            entity.setConf(readDecimal(itemMap, "conf", "confidence"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            analysisSegmentRepository.saveAll(entities);
        }
    }

    private void persistTimelineSegments(Long sessionId, List<?> timeline) {
        List<AnalysisSegmentEntity> entities = new ArrayList<>();
        for (Object itemObj : timeline) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) {
                continue;
            }
            Integer startS = readInt(itemMap, "ts_s", "start_s", "start");
            BigDecimal windowS = readDecimal(itemMap, "window_s", "window");
            Integer endS = null;
            if (startS != null && windowS != null) {
                endS = startS + windowS.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
            }
            if (startS == null || endS == null) {
                continue;
            }
            AnalysisSegmentEntity entity = new AnalysisSegmentEntity();
            entity.setSessionId(sessionId);
            entity.setStartS(startS);
            entity.setEndS(endS);

            Object ratesObj = itemMap.get("rates");
            if (ratesObj instanceof Map<?, ?> ratesMap) {
                entity.setFocusRate(readDecimal(ratesMap, "listening_rate", "focus_rate", "focus"));
                entity.setPhoneRate(readDecimal(ratesMap, "Using_Phone_rate", "phone_rate", "phone"));
            }

            entity.setConf(readDecimal(itemMap, "avg_conf", "conf", "confidence"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            analysisSegmentRepository.saveAll(entities);
        }
    }

    public void persistTracks(Long sessionId, Map<String, Object> analysis) {
        Object tracksObj = analysis.get("tracks");
        if (!(tracksObj instanceof List<?> tracks) || tracks.isEmpty()) {
            return;
        }
        List<TrackSummaryEntity> entities = new ArrayList<>();
        for (Object trackObj : tracks) {
            if (!(trackObj instanceof Map<?, ?> trackMap)) {
                continue;
            }
            Integer tid = readInt(trackMap, "track_id");
            String clsName = readString(trackMap, "cls_name");
            if (tid == null || clsName == null) {
                continue;
            }

            TrackSummaryEntity entity = new TrackSummaryEntity();
            entity.setSessionId(sessionId);
            entity.setTrackId(tid);
            entity.setClsName(clsName);
            entity.setClsId(readInt(trackMap, "cls_id"));
            entity.setFirstFrame(readInt(trackMap, "first_frame"));
            entity.setLastFrame(readInt(trackMap, "last_frame"));
            entity.setFirstTsS(readDecimal(trackMap, "first_ts_s"));
            entity.setLastTsS(readDecimal(trackMap, "last_ts_s"));
            entity.setDurationS(readDecimal(trackMap, "duration_s"));
            entity.setTotalFrames(readInt(trackMap, "total_frames"));
            entity.setAvgConf(readDecimal(trackMap, "avg_conf"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            trackSummaryRepository.saveAll(entities);
        }
    }

    private Integer readInt(Map<?, ?> map, String... keys) {
        Object value = readValue(map, keys);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal readDecimal(Map<?, ?> map, String... keys) {
        Object value = readValue(map, keys);
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String readString(Map<?, ?> map, String... keys) {
        Object value = readValue(map, keys);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return StringUtils.hasText(text) ? text : null;
    }

    private Object readValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }
}
