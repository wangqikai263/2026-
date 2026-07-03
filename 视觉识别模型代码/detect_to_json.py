"""
功能（v0.2.1 — 多目标追踪版）：
1. 调用 `detect.get_class_names` 获取模型定义的类别名（优先），回退到模型内部 names。
2. 使用 RTDETR 对视频做检测，接入 BoxMOT tracker 实现多目标追踪。
3. 输出 schema v0.2.1 JSON：在原有结构基础上扩展 track_id 与 tracks 汇总。
4. 新增 detection_stats_timeline：每个采样时刻的检测统计摘要（便于 LLM 调用）。
5. 暴露 FastAPI /analyze 接口供 Spring Boot 后端调用。

兼容：当 tracking_mode="none" 时退化为 v0.1 纯检测模式。
"""

import json
import os
import sys
import time
import threading
import asyncio
from pathlib import Path
from datetime import datetime
from typing import List, Dict, Any, Optional

import numpy as np
from collections import Counter
from ultralytics import RTDETR
import detect

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# --- 将 boxmot 项目根目录加入 sys.path，使 boxmot 包可被导入 ---
_BOXMOT_ROOT = Path(__file__).resolve().parent.parent / "boxmot"
if _BOXMOT_ROOT.exists() and str(_BOXMOT_ROOT) not in sys.path:
    sys.path.insert(0, str(_BOXMOT_ROOT))

# ---------------- FastAPI 应用 ----------------
app = FastAPI(title="Device Usage Detector", version="0.2.1")

cors_origins = os.getenv(
    "CORS_ORIGINS",
    "http://localhost:8080,http://127.0.0.1:8080"
).split(",")
cors_origins = [o.strip() for o in cors_origins if o.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)

_PROGRESS_STORE: Dict[str, Dict[str, Any]] = {}
_PROGRESS_LOCK = threading.Lock()

_DEFAULT_MODEL_WEIGHTS = Path(__file__).resolve().parent / "oursModel.pt"


def _set_progress(
    job_id: Optional[str],
    progress: int,
    status: str = "RUNNING",
    current_frame: Optional[int] = None,
    total_frames: Optional[int] = None,
    message: Optional[str] = None,
) -> None:
    if not job_id:
        return

    payload: Dict[str, Any] = {
        "job_id": str(job_id),
        "status": status,
        "progress": max(0, min(100, int(progress))),
        "updated_at": datetime.utcnow().isoformat() + "Z",
    }
    if current_frame is not None:
        payload["current_frame"] = int(current_frame)
    if total_frames is not None:
        payload["total_frames"] = int(total_frames)
    if message:
        payload["message"] = message
    with _PROGRESS_LOCK:
        _PROGRESS_STORE[str(job_id)] = payload
# ============================================================
# Tracker 初始化工具
# ============================================================
# 快速/精准模式到 tracker_type 的映射
TRACKING_MODE_MAP = {
    "fast": "bytetrack",
    "precise": "botsort",
}

# 需要 ReID 权重的 tracker 集合
REID_TRACKERS = {"strongsort", "botsort", "deepocsort", "hybridsort", "boosttrack", "deepscsort"}

# 默认 ReID 权重（精准模式使用）
# 说明：你在命令行使用的是 `--reid-model weights/osnet_x0_25_msmt17.pt`
# 因此这里优先指向 boxmot 仓库根目录下的 weights/...
DEFAULT_REID_WEIGHTS = _BOXMOT_ROOT / "weights" / "osnet_x0_25_msmt17.pt"


def _resolve_reid_weights(user_path: Optional[Path] = None) -> Optional[Path]:
    """解析 ReID 权重路径。

    优先级：
    1) 显式传入 user_path
    2) 环境变量 BOXMOT_REID_WEIGHTS
    3) boxmot/weights/osnet_x0_25_msmt17.pt（与 `boxmot generate --reid-model weights/...` 一致）
    4) 兼容旧默认：boxmot/boxmot/engine/weights/osnet_x0_25_msmt17.pt
    """
    candidates: List[Path] = []

    if user_path is not None:
        candidates.append(Path(user_path))

    env_path = os.getenv("BOXMOT_REID_WEIGHTS")
    if env_path:
        candidates.append(Path(env_path))

    candidates.append(DEFAULT_REID_WEIGHTS)
    candidates.append(_BOXMOT_ROOT / "boxmot" / "engine" / "weights" / "osnet_x0_25_msmt17.pt")

    for p in candidates:
        try:
            if p and p.exists():
                return p
        except Exception:
            continue
    return candidates[0] if candidates else None


def _maybe_download_reid_weights(target: Path) -> bool:
    """如果 ReID 权重文件缺失，则尝试自动下载到 target。

    依赖：boxmot.appearance.reid.config.TRAINED_URLS + gdown
    说明：下载源来自 BoxMOT 内置的 ReID model zoo（Google Drive / GitHub release）。
    """
    try:
        from boxmot.appearance.reid.config import TRAINED_URLS
    except Exception as e:
        print(f"[WARN] 无法导入 BoxMOT ReID 模型 URL 列表，无法自动下载：{e}")
        return False

    url = TRAINED_URLS.get(Path(target).name)
    if not url:
        return False

    try:
        import gdown
    except Exception as e:
        print(f"[WARN] 未安装 gdown，无法自动下载 ReID 权重：{e}")
        return False

    try:
        target = Path(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        print(f"[INFO] 自动下载 ReID 权重：{url} -> {target}")
        # gdown 对 Google Drive 的 `uc?id=...` 链接可直接下载
        gdown.download(url, str(target), quiet=False)
        return target.exists()
    except Exception as e:
        print(f"[WARN] 自动下载 ReID 权重失败（{target}）：{e}")
        return False


def _create_tracker(
    tracker_type: str = "bytetrack",
    device: str = "0",
    per_class: bool = True,
    reid_weights: Optional[Path] = None,
    half: bool = False,
):
    """
    创建 BoxMOT tracker 实例。
    如果 boxmot 不可用则返回 None（退化为纯检测）。
    """
    try:
        from boxmot.tracker_zoo import create_tracker, get_tracker_config
    except (ImportError, ModuleNotFoundError) as e:
        print(f"[WARN] boxmot 未安装或不在 PYTHONPATH，追踪功能不可用，退化为纯检测模式。错误：{e}")
        return None

    tracker_config = get_tracker_config(tracker_type)

    # 判断是否需要 ReID 权重
    rw = None
    if tracker_type in REID_TRACKERS:
        rw = _resolve_reid_weights(reid_weights)
        if rw is None or not Path(rw).exists():
            # 尝试自动下载（若网络可用）
            if rw is not None and _maybe_download_reid_weights(Path(rw)):
                pass
            else:
                print(
                    f"[WARN] ReID 权重 {rw} 不存在，回退到 bytetrack；"
                    f"如需启用 precise + ReID，请将权重放到 {DEFAULT_REID_WEIGHTS} "
                    f"或设置环境变量 BOXMOT_REID_WEIGHTS"
                )
                tracker_type = "bytetrack"
                tracker_config = get_tracker_config(tracker_type)
                rw = None

    tracker = create_tracker(
        tracker_type=tracker_type,
        tracker_config=str(tracker_config),
        reid_weights=rw,
        device=device,
        half=half,
        per_class=per_class,
    )
    return tracker, tracker_type


# ---------------- 工具函数 ----------------
def clsidx_to_name(names, idx):
    """将类别索引转成类别名（兼容 list/dict/None）。"""
    try:
        if names is None:
            return str(idx)
        # dict: may map int->name or str->name
        if isinstance(names, dict):
            return names.get(int(idx)) or names.get(str(idx)) or str(idx)
        # list/tuple
        if isinstance(names, (list, tuple)):
            return names[int(idx)]
        # fallback
        return str(names)
    except Exception:
        return str(idx)



# ---------------- 检测结果 -> numpy 数组 ----------------
def _parse_boxes_to_array(res, names) -> "np.ndarray":
    """
    将 ultralytics Results 对象的 boxes 解析为 (M, 6) numpy 数组。
    列: [x1, y1, x2, y2, conf, cls]
    """
    if res is None:
        return np.empty((0, 6), dtype=np.float32)

    boxes = getattr(res, "boxes", None)
    if boxes is None:
        return np.empty((0, 6), dtype=np.float32)

    try:
        xyxy = None
        if hasattr(boxes, "xyxy"):
            xyxy = boxes.xyxy
        elif hasattr(boxes, "data"):
            xyxy = boxes.data

        cls_attr = getattr(boxes, "cls", None)
        conf_attr = getattr(boxes, "conf", None)

        if xyxy is None or cls_attr is None:
            return np.empty((0, 6), dtype=np.float32)

        if hasattr(xyxy, "cpu"):
            xyxy_np = xyxy.cpu().numpy()
        else:
            xyxy_np = np.array(xyxy)

        if hasattr(conf_attr, "cpu"):
            conf_np = conf_attr.cpu().numpy().reshape(-1, 1)
        else:
            conf_np = np.array(conf_attr).reshape(-1, 1)

        if hasattr(cls_attr, "cpu"):
            cls_np = cls_attr.cpu().numpy().reshape(-1, 1)
        else:
            cls_np = np.array(cls_attr).reshape(-1, 1)

        dets = np.hstack([xyxy_np[:, :4], conf_np, cls_np]).astype(np.float32)
        return dets
    except Exception:
        return np.empty((0, 6), dtype=np.float32)


# ---------------- 核心分析函数：run_analysis ----------------
def run_analysis(
    weights: str,
    video_path: str,
    output_json: str,
    sample_interval: Optional[int] = None,
    conf_thres: float = 0.35,
    device: str = "0",
    external_names: Optional[List[str]] = None,
    tracking_mode: str = "fast",
    detection_interval: int = 5,
    per_class: bool = True,
    job_id: Optional[str] = None,
) -> Path:
    """
    核心分析函数（v0.2.1）。
    - tracking_mode: "fast" / "precise" / "none"
    - detection_interval: 降频检测间隔（帧），tracker 每帧都 predict
    - per_class: 是否按类别独立追踪
    """
    # 1. 初始化模型
    model = RTDETR(weights)
    # 优先使用外部传入的 names（例如由 detect.get_class_names 提供），否则从模型对象中获取
    names = (
        external_names
        if external_names is not None
        else getattr(model, "names", None) or getattr(getattr(model, "model", None), "names", None)
    )

    # 规范化 class_names：保证为字符串列表，并包含 fallback 标签 'listening'
    def _sanitize_key(x: Any) -> str:
        try:
            s = str(x)
        except Exception:
            s = ""
        s = s.strip()
        # 简单清理，生成 JSON 键友好的形式
        s = s.replace(" ", "_").replace("/", "_")
        return s

    if names is None:
        class_names: List[str] = ["listening"]
    else:
        if isinstance(names, dict):
            try:
                items = sorted(names.items(), key=lambda kv: int(kv[0]))
                class_names = [str(v) for k, v in items]
            except Exception:
                class_names = [str(v) for v in names.values()]
        elif isinstance(names, (list, tuple)):
            class_names = [str(v) for v in names]
        else:
            class_names = [str(names)]

    # Ensure 'listening' exists because frames with no detections use this label
    if "listening" not in class_names:
        class_names.append("listening")

    def _build_detection_snapshot_stats(
        frame_idx_val: int,
        ts_s_val: float,
        boxes: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """构建单个采样时刻的检测摘要，供 LLM 轻量使用。"""
        class_counts: Counter = Counter()
        class_conf_sum: Dict[str, float] = {}
        class_conf_num: Counter = Counter()
        confidences: List[float] = []
        track_ids = set()
        tracked_objects = 0

        for box in boxes:
            cname = str(box.get("cls_name", "unknown"))
            class_counts[cname] += 1

            conf = box.get("conf", None)
            if conf is not None:
                try:
                    cf = float(conf)
                    confidences.append(cf)
                    class_conf_sum[cname] = class_conf_sum.get(cname, 0.0) + cf
                    class_conf_num[cname] += 1
                except Exception:
                    pass

            tid = box.get("track_id", None)
            if tid is not None:
                tracked_objects += 1
                try:
                    track_ids.add(int(tid))
                except Exception:
                    track_ids.add(str(tid))

        total_boxes = len(boxes)
        sorted_class_items = sorted(class_counts.items(), key=lambda kv: (-kv[1], str(kv[0])))
        class_counts_out = {str(k): int(v) for k, v in sorted_class_items}
        class_rates_out = {
            str(k): round((int(v) / total_boxes), 4) for k, v in sorted_class_items
        } if total_boxes > 0 else {}
        class_avg_conf_out = {
            str(k): round(class_conf_sum[str(k)] / max(int(class_conf_num[str(k)]), 1), 4)
            for k, _ in sorted_class_items
            if class_conf_num.get(str(k), 0) > 0
        }

        if sorted_class_items:
            dominant_class = str(sorted_class_items[0][0])
            dominant_ratio = round((int(sorted_class_items[0][1]) / total_boxes), 4) if total_boxes > 0 else 0.0
        else:
            dominant_class = "none"
            dominant_ratio = 0.0

        avg_conf = round(sum(confidences) / len(confidences), 4) if confidences else None
        max_conf = round(max(confidences), 4) if confidences else None

        return {
            "frame_idx": int(frame_idx_val),
            "ts_s": round(float(ts_s_val), 3),
            "objects_total": int(total_boxes),
            "tracked_objects": int(tracked_objects),
            "unique_track_ids": int(len(track_ids)),
            "dominant_class": dominant_class,
            "dominant_ratio": dominant_ratio,
            "avg_conf": avg_conf,
            "max_conf": max_conf,
            "class_counts": class_counts_out,
            "class_rates": class_rates_out,
            "class_avg_conf": class_avg_conf_out,
        }

    # 2. 获取视频信息
    import cv2

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video {video_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    cap.release()

    total_seconds = int(frame_count / fps) if fps and frame_count else None

    # 默认约每 1 秒采样一次（单位：帧）
    if sample_interval is None:
        sample_interval = max(1, int(round(fps))) if fps and fps > 0 else 30

    # Update progress several times per second for a smoother progress bar.
    progress_stride = max(1, int(round(fps / 4))) if fps and fps > 0 else 10
    if job_id:
        _set_progress(job_id, 0, status="RUNNING", current_frame=0, total_frames=frame_count)

    # --- 初始化 tracker ---
    tracking_enabled = tracking_mode in ("fast", "precise")
    tracker_type = TRACKING_MODE_MAP.get(tracking_mode, "bytetrack")
    tracker = None
    if tracking_enabled:
        tracker_result = _create_tracker(
            tracker_type=tracker_type,
            device=device,
            per_class=per_class,
        )
        if not tracker_result or tracker_result[0] is None:
            tracking_enabled = False
        else:
            tracker, tracker_type = tracker_result

    metadata: Dict[str, Any] = {
        "video_path": str(video_path),
        "analyzed_at": datetime.utcnow().isoformat() + "Z",
        "model": {"name": model.__class__.__name__, "weights": str(weights)},
        "total_seconds": total_seconds,
        "fps": fps,
        "frames": frame_count,
        "sample_interval_frames": sample_interval,
        "tracking": {
            "enabled": tracking_enabled,
            "tracker_type": tracker_type if tracking_enabled else "none",
            "mode": tracking_mode,
            "per_class": per_class,
            "detection_interval": detection_interval,
        },
    }

    timeline: List[Dict[str, Any]] = []
    timeline_window_frames: List[int] = []
    detections: List[Dict[str, Any]] = []
    detection_stats_timeline: List[Dict[str, Any]] = []
    track_registry: Dict[int, Dict[str, Any]] = {}

    # --------- 全局 summary 统计 ---------
    tot_frames = 0
    tot_conf_sum = 0.0
    tot_det_count = 0
    # 每帧的“标签出现次数”统计（按帧中是否出现该类），用于计算 overall_*_rate
    tot_frame_label_counts: Counter = Counter()

    # --------- 当前窗口统计 ---------
    cur_sample_frames = 0
    cur_frame_label_counts: Counter = Counter()
    cur_conf_sum = 0.0
    cur_det_count = 0
    cur_class_counter: Counter = Counter()
    tot_class_counter: Counter = Counter()

    # 3. 流式推理（逐帧读取 -> 降频检测 + 每帧追踪）
    cap2 = cv2.VideoCapture(video_path)
    if not cap2.isOpened():
        raise RuntimeError(f"Cannot open video {video_path}")

    last_raw_dets = np.empty((0, 6), dtype=np.float32)
    frame_idx = 0

    try:
        while True:
            ret, frame = cap2.read()
            if not ret:
                break

            # --- 降频检测：每 detection_interval 帧做一次检测 ---
            run_det = (frame_idx % detection_interval == 0) if tracking_enabled else True
            raw_dets = last_raw_dets

            if run_det:
                try:
                    res_list = model.predict(frame, conf=conf_thres, device=device, verbose=False)
                    res = res_list[0] if res_list else None
                except Exception:
                    res = None
                raw_dets = _parse_boxes_to_array(res, names)
                last_raw_dets = raw_dets

            # --- 追踪 ---
            tracked_dets = raw_dets
            if tracking_enabled and tracker is not None:
                try:
                    tracked_result = tracker.update(raw_dets, frame)
                    if tracked_result is not None and len(tracked_result) > 0:
                        tracked_dets = np.array(tracked_result, dtype=np.float64)
                    else:
                        tracked_dets = np.empty((0, 8), dtype=np.float64)
                except Exception as e:
                    print(f"[WARN] tracker.update error: {e}")
                    tracked_dets = raw_dets

            # --- 构建本帧的 bboxes_out ---
            frame_class_counter: Counter = Counter()
            confidences: List[float] = []
            bboxes_out: List[Dict[str, Any]] = []
            ts_s = (frame_idx / fps) if fps and fps > 0 else float(frame_idx)

            has_track_id = (tracking_enabled and tracked_dets.ndim == 2
                           and tracked_dets.shape[1] >= 8)

            for row in tracked_dets:
                if has_track_id:
                    x1, y1, x2, y2 = float(row[0]), float(row[1]), float(row[2]), float(row[3])
                    tid = int(row[4])
                    cf = float(row[5])
                    ci = int(row[6])
                else:
                    if len(row) < 6:
                        continue
                    x1, y1, x2, y2 = float(row[0]), float(row[1]), float(row[2]), float(row[3])
                    cf = float(row[4])
                    ci = int(row[5])
                    tid = None

                cname = clsidx_to_name(names, ci) if names is not None else str(ci)
                confidences.append(cf)

                box_entry: Dict[str, Any] = {
                    "bbox": [x1, y1, x2, y2],
                    "cls_id": ci,
                    "cls_name": cname,
                    "conf": round(cf, 4),
                }
                if tid is not None:
                    box_entry["track_id"] = tid
                    if tid not in track_registry:
                        track_registry[tid] = {
                            "cls_counts": Counter(),
                            "cls_id_counts": Counter(),
                            "first_frame": frame_idx,
                            "last_frame": frame_idx,
                            "first_ts_s": ts_s,
                            "last_ts_s": ts_s,
                            "total_frames": 0,
                            "conf_sum": 0.0,
                        }
                    treg = track_registry[tid]
                    treg["cls_counts"][cname] += 1
                    treg["cls_id_counts"][ci] += 1
                    treg["last_frame"] = frame_idx
                    treg["last_ts_s"] = ts_s
                    treg["total_frames"] += 1
                    treg["conf_sum"] += cf

                bboxes_out.append(box_entry)
                cur_class_counter[str(cname)] += 1
                tot_class_counter[str(cname)] += 1
                frame_class_counter[str(cname)] += 1

            # --------- 统计本帧 ---------
            if confidences:
                cur_conf_sum += sum(confidences)
                cur_det_count += len(confidences)
                tot_conf_sum += sum(confidences)
                tot_det_count += len(confidences)

            if frame_idx % sample_interval == 0:
                detections.append({
                    "frame_idx": int(frame_idx),
                    "ts_s": float(ts_s),
                    "boxes": bboxes_out,
                })
                detection_stats_timeline.append(
                    _build_detection_snapshot_stats(
                        frame_idx_val=frame_idx,
                        ts_s_val=ts_s,
                        boxes=bboxes_out,
                    )
                )

            frame_classes_present = set(frame_class_counter.keys())
            if not frame_classes_present:
                frame_classes_present = {"listening"}

            cur_sample_frames += 1
            tot_frames += 1
            for cname in frame_classes_present:
                cname_str = str(cname)
                cur_frame_label_counts[cname_str] += 1
                tot_frame_label_counts[cname_str] += 1

            # --------- 窗口结束：写入一条 timeline ---------
            if cur_sample_frames >= sample_interval:
                ts_s_w = (
                    (frame_idx - cur_sample_frames + 1) / fps
                    if fps and fps > 0
                    else (frame_idx - cur_sample_frames + 1)
                )
                window_s = cur_sample_frames / fps if fps and fps > 0 else cur_sample_frames
                avg_conf = (cur_conf_sum / cur_det_count) if cur_det_count > 0 else None

                timeline.append({
                    "ts_s": float(ts_s_w),
                    "window_s": float(window_s),
                    "avg_conf": round(float(avg_conf), 4) if avg_conf is not None else None,
                    "class_counts": dict(cur_class_counter),
                })
                timeline_window_frames.append(int(cur_sample_frames))

                # 窗口计数清零
                cur_sample_frames = 0
                cur_frame_label_counts = Counter()
                cur_conf_sum = 0.0
                cur_det_count = 0
                cur_class_counter = Counter()

            if job_id and frame_count > 0 and (frame_idx % progress_stride == 0):
                progress = int(min(99, ((frame_idx + 1) / frame_count) * 100))
                _set_progress(
                    job_id,
                    progress,
                    status="RUNNING",
                    current_frame=frame_idx + 1,
                    total_frames=frame_count,
                )

            frame_idx += 1
    finally:
        cap2.release()

    # --------- 最后一个不完整窗口 ---------
    if cur_sample_frames > 0:
        ts_s = (
            (frame_idx - cur_sample_frames) / fps
            if fps and fps > 0
            else (frame_idx - cur_sample_frames)
        )
        window_s = cur_sample_frames / fps if fps and fps > 0 else cur_sample_frames

        avg_conf = (cur_conf_sum / cur_det_count) if cur_det_count > 0 else None

        timeline.append(
            {
                "ts_s": float(ts_s),
                "window_s": float(window_s),
                "avg_conf": round(float(avg_conf), 4)
                if avg_conf is not None
                else None,
                "class_counts": dict(cur_class_counter),
            }
        )
        timeline_window_frames.append(int(cur_sample_frames))

    # --------- 全局 summary（基于“帧中是否出现该类”的统计） ---------
    summary: Dict[str, Any] = {
        "total_frames": int(tot_frames),
        "total_segments": len(timeline),
        **{
            f"overall_{_sanitize_key(cname)}_rate": round(
                (tot_frame_label_counts.get(cname, 0) / tot_frames), 4
            )
            if tot_frames > 0
            else 0.0
            for cname in class_names
        },
        "class_counts_total": dict(tot_class_counter),
        "avg_confidence": round(tot_conf_sum / tot_det_count, 4)
        if tot_det_count > 0
        else None,
    }

    # --------- 最终时间序列：device_usage_segments ---------
    segments_final: List[Dict[str, Any]] = []
    global_default_conf = (
        summary["avg_confidence"] if summary["avg_confidence"] is not None else 0.0
    )

    for idx, seg in enumerate(timeline):
        start_s = float(seg.get("ts_s", 0.0))
        window_s = float(seg.get("window_s", 0.0))
        end_s = start_s + window_s

        seg_conf = seg.get("avg_conf", None)
        if seg_conf is None:
            seg_conf = global_default_conf
        seg_conf = 0.0 if seg_conf is None else float(seg_conf)

        frames = max(
            int(timeline_window_frames[idx]) if idx < len(timeline_window_frames) else 1,
            1,
        )
        class_counts = seg.get("class_counts", {}) or {}

        seg_entry: Dict[str, Any] = {
            "index": idx,
            "start_s": round(start_s, 3),
            "end_s": round(end_s, 3),
        }

        # 为每个类别添加 *_rate 字段（这里仍然是“检测框数量 / 帧数”的含义）
        for cname in class_names:
            # 匹配 class_counts 中的键（大小写不敏感）
            cnt = 0
            for k, v in class_counts.items():
                try:
                    if str(k).lower() == str(cname).lower():
                        cnt = int(v)
                        break
                except Exception:
                    continue
            rate = float(cnt) / frames if frames > 0 else 0.0
            key = f"{_sanitize_key(cname)}_rate"
            seg_entry[key] = round(rate, 4)

        seg_entry["conf"] = round(float(seg_conf), 4)
        segments_final.append(seg_entry)

    # --------- 构建 tracks 汇总（v0.2 新增） ---------
    tracks_list: List[Dict[str, Any]] = []
    for tid, treg in track_registry.items():
        if treg["cls_counts"]:
            main_cls_name = treg["cls_counts"].most_common(1)[0][0]
        else:
            main_cls_name = "unknown"
        if treg["cls_id_counts"]:
            main_cls_id = treg["cls_id_counts"].most_common(1)[0][0]
        else:
            main_cls_id = -1
        dur = treg["last_ts_s"] - treg["first_ts_s"] if treg["first_ts_s"] is not None else 0.0
        avg_c = (treg["conf_sum"] / treg["total_frames"]) if treg["total_frames"] > 0 else 0.0
        tracks_list.append({
            "track_id": tid,
            "cls_name": main_cls_name,
            "cls_id": main_cls_id,
            "first_frame": treg["first_frame"],
            "last_frame": treg["last_frame"],
            "first_ts_s": round(treg["first_ts_s"], 3) if treg["first_ts_s"] is not None else 0.0,
            "last_ts_s": round(treg["last_ts_s"], 3) if treg["last_ts_s"] is not None else 0.0,
            "duration_s": round(dur, 3),
            "total_frames": treg["total_frames"],
            "avg_conf": round(avg_c, 4),
        })
    tracks_list.sort(key=lambda t: t["track_id"])

    summary["total_tracks"] = len(tracks_list)
    non_empty_detection_stats = [
        item for item in detection_stats_timeline if int(item.get("objects_total", 0)) > 0
    ]
    dominant_counter: Counter = Counter(
        str(item.get("dominant_class"))
        for item in non_empty_detection_stats
        if str(item.get("dominant_class", "none")) != "none"
    )
    avg_objects_per_snapshot = (
        sum(int(item.get("objects_total", 0)) for item in detection_stats_timeline)
        / len(detection_stats_timeline)
    ) if detection_stats_timeline else 0.0
    avg_objects_per_non_empty_snapshot = (
        sum(int(item.get("objects_total", 0)) for item in non_empty_detection_stats)
        / len(non_empty_detection_stats)
    ) if non_empty_detection_stats else 0.0
    summary["detection_stats"] = {
        "total_snapshots": len(detection_stats_timeline),
        "empty_snapshots": len(detection_stats_timeline) - len(non_empty_detection_stats),
        "non_empty_snapshots": len(non_empty_detection_stats),
        "avg_objects_per_snapshot": round(float(avg_objects_per_snapshot), 4),
        "avg_objects_per_non_empty_snapshot": round(float(avg_objects_per_non_empty_snapshot), 4),
        "top_dominant_class": dominant_counter.most_common(1)[0][0] if dominant_counter else "none",
        "dominant_class_distribution": dict(dominant_counter),
    }

    # --------- 汇总输出 JSON ---------
    out: Dict[str, Any] = {
        "schema_version": "0.2.1",
        "module_name": "behavior_device_usage",
        "metadata": metadata,
        "summary": summary,
        "timeline": timeline,
        "detections": detections,
        "detection_stats_timeline": detection_stats_timeline,
        "tracks": tracks_list,
        "device_usage_segments": segments_final,
    }

    out_path = Path(output_json)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    if job_id:
        _set_progress(job_id, 100, status="SUCCESS", current_frame=frame_count, total_frames=frame_count)

    return out_path


# ---------------- FastAPI 请求模型 ----------------
class AnalyzeRequest(BaseModel):
    video_path: str                    # Spring Boot 传来的本地视频绝对路径
    weights: str = str(_DEFAULT_MODEL_WEIGHTS)  # 模型权重文件（可配置）
    sample_interval: Optional[int] = None
    conf_thres: float = 0.35
    device: str = "0"                  # '0' 或 'cpu'
    tracking_mode: str = "fast"        # fast / precise / none
    detection_interval: int = 5        # 降频检测间隔帧数
    per_class: bool = True             # 是否按类别独立追踪
    job_id: Optional[str] = None       # 异步任务ID（用于进度查询）
    response_mode: str = "inline"      # inline: 返回完整JSON; path: 只返回结果文件路径


# ---------------- 健康检查 ----------------
@app.get("/health")
def health():
    return {"ok": True, "module": "device_usage_detector"}


@app.get("/progress")
def get_progress(job_id: str):
    if not job_id:
        return {"status": "UNKNOWN", "progress": 0}
    with _PROGRESS_LOCK:
        data = _PROGRESS_STORE.get(str(job_id))
    if not data:
        return {"job_id": str(job_id), "status": "UNKNOWN", "progress": 0}
    return data


# ---------------- FastAPI 主接口 ----------------
@app.post("/analyze")
async def analyze(req: AnalyzeRequest) -> Dict[str, Any]:
    """
    供 Spring Boot 调用的接口：

    - 决定 JSON 输出目录（小模型本地）
    - 调用 run_analysis 生成 JSON 文件
    - 读取 JSON 内容并返回，同时在 metadata.json_path 中附带文件绝对路径
    """

    # 1. 输出目录：环境变量 RESULT_DIR 优先，否则用 ./runs/json
    base_dir = os.getenv("RESULT_DIR", "./runs/json")
    base_path = Path(base_dir)
    base_path.mkdir(parents=True, exist_ok=True)

    # 2. 基于视频文件名 + 时间戳生成唯一 JSON 文件名
    video_stem = Path(req.video_path).stem
    ts = int(time.time())
    output_json = base_path / f"{video_stem}_behavior_{ts}.json"

    # 3. 先尝试通过 `detect`   模块获取完整类别名（确保能拿到模型定义的类别）
    weights_path = Path(req.weights)
    if not weights_path.is_absolute():
        weights_path = (Path(__file__).resolve().parent / req.weights).resolve()

    try:
        ext_names = detect.get_class_names(str(weights_path))
    except Exception:
        ext_names = None

    # 4. 调用核心分析函数：在小模型目录下生成 JSON 文件
    try:
        out_path = await asyncio.to_thread(
            run_analysis,
            str(weights_path),
            req.video_path,
            str(output_json),
            req.sample_interval,
            req.conf_thres,
            req.device,
            ext_names,
            req.tracking_mode,
            req.detection_interval,
            req.per_class,
            req.job_id,
        )
    except Exception as e:
        _set_progress(req.job_id, 100, status="FAILED", message=str(e))
        return {
            "schema_version": "0.2.1",
            "module_name": "behavior_device_usage",
            "error": f"analyze_failed: {e}",
        }

    # 5. 按响应模式返回
    result_json_path = str(out_path.resolve())
    response_mode = str(req.response_mode or "inline").strip().lower()
    if response_mode in ("path", "lite"):
        return {
            "schema_version": "0.2.1",
            "module_name": "behavior_device_usage",
            "metadata": {
                "json_path": result_json_path
            }
        }

    # 默认返回完整 JSON（兼容旧逻辑）
    try:
        with out_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        _set_progress(req.job_id, 100, status="FAILED", message=f"result_read_failed: {e}")
        return {
            "schema_version": "0.2.1",
            "module_name": "behavior_device_usage",
            "error": f"result_read_failed: {e}",
            "metadata": {"json_path": result_json_path},
        }

    data.setdefault("metadata", {})
    data["metadata"]["json_path"] = result_json_path

    return data


# ---------------- 脚本调试模式 ----------------
if __name__ == "__main__":
    """
    1）脚本单独调试：
        python detect_to_json.py

    2）作为服务运行（供 Spring Boot 调用）：
        uvicorn detect_to_json:app --host 0.0.0.0 --port 8001 --reload
    """

    VIDEO_PATH = "2.mp4"              # 本地测试视频
    WEIGHTS = "oursModel.pt"
    OUTPUT_JSON = "runs/detect/analysis_result.json"
    SAMPLE_INTERVAL = None            # None -> 约每秒采样一帧

    # 当脚本单独运行时，也从 detect.py 获取类别名并传入
    try:
        main_ext_names = detect.get_class_names(WEIGHTS)
    except Exception:
        main_ext_names = None

    out_file = run_analysis(
        WEIGHTS,
        VIDEO_PATH,
        OUTPUT_JSON,
        sample_interval=SAMPLE_INTERVAL,
        conf_thres=0.35,
        device="0",
        external_names=main_ext_names,
        tracking_mode="fast",
        detection_interval=5,
        per_class=True,
    )
    print(f"Analysis JSON saved to: {out_file}")
