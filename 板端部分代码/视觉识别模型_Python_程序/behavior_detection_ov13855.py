import argparse
import ast
import json
import os
import queue
import socket
import threading
import time

from collections import Counter, deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict
from typing import List, Optional, Sequence, Tuple, Union
from urllib import error as urlerror
from urllib import request as urlrequest

import cv2
import numpy as np

# ==============================================================
# 【核心网络探针配置】(防呆版：确保这段代码在全局最上方！)
# ==============================================================
QT_IP = "127.0.0.1"   # 强制绑定本地环回地址
QT_PORT = 9999        # 必须和 Qt 的监听端口 9999 对齐
try:
    qt_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print(">>> [底层探针]: UDP 发送端初始化成功！目标端口 9999")
except Exception as e:
    print(f">>> [致命错误]: UDP 套接字创建失败！{e}")
# ==============================================================

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    Image = None
    ImageDraw = None
    ImageFont = None


Number = Union[int, float]

DEFAULT_LABELS = [
    "listening",
    "Eating/Drinking",
    "Using Phone",
    "Using Tablet",
    "Using Laptop",
    "Reading",
    "Writing",
]

EN_TO_ZH_LABELS = {
    "listening": "听课",
    "eating/drinking": "吃喝",
    "eating drinking": "吃喝",
    "using phone": "玩手机",
    "using tablet": "用平板",
    "using laptop": "用电脑",
    "reading": "阅读",
    "writing": "书写",
}

SHORT_LABELS = {
    "listening": "listen",
    "eating/drinking": "eat",
    "eating drinking": "eat",
    "using phone": "phone",
    "using tablet": "tablet",
    "using laptop": "laptop",
    "using laptap": "laptop",
    "reading": "read",
    "writing": "write",
    "听课": "listen",
    "听讲": "listen",
    "吃喝": "eat",
    "玩手机": "phone",
    "手机": "phone",
    "用平板": "tablet",
    "平板": "tablet",
    "用电脑": "laptop",
    "电脑": "laptop",
    "阅读": "read",
    "书写": "write",
}

_PIL_FONT_CACHE: Dict[int, object] = {}


def utc_iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class RealtimeReporter:
    """Send realtime behavior windows to backend with retry and local spool fallback."""

    def __init__(
        self,
        report_url: str,
        device_id: str,
        class_id: str,
        device_token: str,
        timeout_s: float,
        retries: int,
        backoff_s: float,
        spool_path: str,
        spool_max_records: int,
    ):
        self.report_url = (report_url or "").strip()
        self.enabled = bool(self.report_url)
        self.device_id = device_id.strip() if device_id else socket.gethostname()
        self.class_id = class_id.strip() if class_id else "demo-001"
        self.device_token = (device_token or "").strip()
        self.timeout_s = max(0.3, float(timeout_s))
        self.retries = max(0, int(retries))
        self.backoff_s = max(0.0, float(backoff_s))
        self.spool_path = Path(spool_path)
        self.spool_max_records = max(100, int(spool_max_records))

        self.sent_ok = 0
        self.sent_fail = 0
        self.spool_written = 0
        self.spool_flushed = 0
        self._spool_lock = threading.RLock()
        self._stop_event = threading.Event()
        self._queue: "queue.Queue[Tuple[str, object]]" = queue.Queue(maxsize=16)
        self._worker: Optional[threading.Thread] = None

        if self.enabled:
            self.spool_path.parent.mkdir(parents=True, exist_ok=True)
            self._worker = threading.Thread(
                target=self._worker_loop,
                name="realtime-report-worker",
                daemon=True,
            )
            self._worker.start()

    def _post_json(self, payload: dict) -> bool:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urlrequest.Request(self.report_url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if self.device_token:
            req.add_header("X-Device-Token", self.device_token)
        req.add_header("X-Device-Id", self.device_id)
        try:
            with urlrequest.urlopen(req, timeout=self.timeout_s) as resp:
                code = getattr(resp, "status", 200)
                return 200 <= int(code) < 300
        except (urlerror.URLError, urlerror.HTTPError, TimeoutError):
            return False

    def send_with_retry(self, payload: dict) -> bool:
        if not self.enabled:
            return False
        for attempt in range(self.retries + 1):
            if self._post_json(payload):
                self.sent_ok += 1
                return True
            if attempt < self.retries and self.backoff_s > 0:
                time.sleep(self.backoff_s * (attempt + 1))
        self.sent_fail += 1
        return False

    def append_spool(self, payload: dict):
        if not self.enabled:
            return
        with self._spool_lock:
            row = {
                "queued_at": utc_iso_now(),
                "payload": payload,
            }
            with self.spool_path.open("a", encoding="utf-8") as fp:
                fp.write(json.dumps(row, ensure_ascii=False) + "\n")
            self.spool_written += 1
            self.trim_spool()

    def trim_spool(self):
        if not self.spool_path.exists():
            return
        with self._spool_lock:
            try:
                lines = self.spool_path.read_text(encoding="utf-8").splitlines()
            except Exception:
                return
            if len(lines) <= self.spool_max_records:
                return
            kept = lines[-self.spool_max_records :]
            self.spool_path.write_text("\n".join(kept) + "\n", encoding="utf-8")

    def flush_spool(self, max_records: int = 20):
        if not self.enabled or not self.spool_path.exists():
            return
        with self._spool_lock:
            try:
                lines = self.spool_path.read_text(encoding="utf-8").splitlines()
            except Exception:
                return
        if not lines:
            return

        keep_lines: List[str] = []
        send_count = 0
        for i, line in enumerate(lines):
            if i >= max_records:
                keep_lines.extend(lines[i:])
                break
            try:
                row = json.loads(line)
                payload = row.get("payload", row)
            except Exception:
                continue
            if self.send_with_retry(payload):
                self.spool_flushed += 1
                send_count += 1
            else:
                keep_lines.append(line)
                keep_lines.extend(lines[i + 1 :])
                break

        with self._spool_lock:
            appended_lines: List[str] = []
            try:
                current_lines = self.spool_path.read_text(encoding="utf-8").splitlines()
                if len(current_lines) > len(lines):
                    appended_lines = current_lines[len(lines) :]
            except Exception:
                appended_lines = []
            keep_lines.extend(appended_lines)
            if keep_lines:
                self.spool_path.write_text("\n".join(keep_lines) + "\n", encoding="utf-8")
            else:
                try:
                    os.remove(self.spool_path)
                except OSError:
                    pass

        if send_count > 0:
            print(f"[REPORT] flushed {send_count} queued payload(s)")

    def _send_or_spool_sync(self, payload: dict):
        if not self.enabled:
            return
        if not self.send_with_retry(payload):
            self.append_spool(payload)

    def _worker_loop(self):
        while not self._stop_event.is_set():
            try:
                task, payload = self._queue.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                if task == "send" and isinstance(payload, dict):
                    self._send_or_spool_sync(payload)
                elif task == "flush":
                    self.flush_spool(max_records=int(payload))
            finally:
                self._queue.task_done()

    def send_or_spool(self, payload: dict):
        if not self.enabled:
            return
        try:
            self._queue.put_nowait(("send", payload))
        except queue.Full:
            self.append_spool(payload)

    def request_flush(self, max_records: int = 20):
        if not self.enabled:
            return
        try:
            self._queue.put_nowait(("flush", int(max_records)))
        except queue.Full:
            pass

    def queued_count(self) -> int:
        if not self.spool_path.exists():
            return 0
        with self._spool_lock:
            try:
                return sum(1 for _ in self.spool_path.open("r", encoding="utf-8"))
            except Exception:
                return 0

    def close(self, timeout_s: float = 0.2):
        self._stop_event.set()
        if self._worker and self._worker.is_alive():
            self._worker.join(timeout=timeout_s)

def letterbox(image: np.ndarray, new_shape: int = 640, color: Tuple[int, int, int] = (114, 114, 114)):
    h, w = image.shape[:2]
    if isinstance(new_shape, int):
        new_shape = (new_shape, new_shape)

    scale = min(new_shape[0] / h, new_shape[1] / w)
    new_unpad = (int(round(w * scale)), int(round(h * scale)))
    dw = new_shape[1] - new_unpad[0]
    dh = new_shape[0] - new_unpad[1]
    dw /= 2
    dh /= 2

    if (w, h) != new_unpad:
        image = cv2.resize(image, new_unpad, interpolation=cv2.INTER_LINEAR)

    top = int(round(dh - 0.1))
    bottom = int(round(dh + 0.1))
    left = int(round(dw - 0.1))
    right = int(round(dw + 0.1))
    image = cv2.copyMakeBorder(image, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)
    return image, scale, left, top


def scale_boxes_back(boxes_xyxy: np.ndarray, scale: float, pad_left: int, pad_top: int, shape_hw: Tuple[int, int]):
    if boxes_xyxy.size == 0:
        return boxes_xyxy

    boxes = boxes_xyxy.copy()
    boxes[:, [0, 2]] -= pad_left
    boxes[:, [1, 3]] -= pad_top
    boxes /= max(scale, 1e-8)

    h, w = shape_hw
    boxes[:, [0, 2]] = boxes[:, [0, 2]].clip(0, w - 1)
    boxes[:, [1, 3]] = boxes[:, [1, 3]].clip(0, h - 1)
    return boxes


def load_labels(label_file: Optional[str], expected_classes: Optional[int] = None) -> List[str]:
    if label_file:
        p = Path(label_file)
        if not p.exists():
            raise FileNotFoundError(f"Label file not found: {p}")
        labels = [line.strip() for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]
        if labels:
            if expected_classes is not None and len(labels) < expected_classes:
                labels.extend([f"behavior_{i}" for i in range(len(labels), expected_classes)])
            return labels

    if expected_classes is None:
        return DEFAULT_LABELS.copy()
    return [f"behavior_{i}" for i in range(expected_classes)]


def _to_bilingual_label(label: str) -> str:
    raw = (label or "").strip()
    if not raw or "--" in raw:
        return raw

    key_direct = raw.lower()
    key_normalized = " ".join(raw.replace("_", " ").split()).lower()
    zh = EN_TO_ZH_LABELS.get(key_direct) or EN_TO_ZH_LABELS.get(key_normalized)
    if zh:
        return f"{raw}--{zh}"
    return raw


def _to_short_label(label: str) -> str:
    raw = (label or "").strip()
    if not raw:
        return raw

    key_direct = raw.lower()
    key_normalized = " ".join(raw.replace("_", " ").split()).lower()
    return SHORT_LABELS.get(key_direct) or SHORT_LABELS.get(key_normalized) or raw[:12]


def _contains_non_ascii(text: str) -> bool:
    return any(ord(ch) > 127 for ch in text)


def _get_pil_font(font_px: int):
    if ImageFont is None:
        return None

    size = max(12, int(font_px))
    cached = _PIL_FONT_CACHE.get(size)
    if cached is not None:
        return cached

    font_candidates = [
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    ]
    for font_path in font_candidates:
        if Path(font_path).exists():
            try:
                font = ImageFont.truetype(font_path, size=size)
                _PIL_FONT_CACHE[size] = font
                return font
            except Exception:
                continue

    try:
        font = ImageFont.load_default()
    except Exception:
        font = None
    _PIL_FONT_CACHE[size] = font
    return font


def _measure_text(text: str, font_scale: float, font_thickness: int) -> Tuple[int, int, int]:
    if Image is not None and ImageDraw is not None and _contains_non_ascii(text):
        font = _get_pil_font(16 + int(font_scale * 14))
        if font is not None:
            dummy = Image.new("RGB", (1, 1), (0, 0, 0))
            draw = ImageDraw.Draw(dummy)
            left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
            text_w = max(1, int(right - left))
            text_h = max(1, int(bottom - top))
            baseline = max(2, int(text_h * 0.2))
            return text_w, text_h, baseline

    (text_w, text_h), baseline = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, font_scale, font_thickness)
    return int(text_w), int(text_h), int(baseline)


def _normalize_split_outputs(outputs: Sequence[np.ndarray]) -> Tuple[np.ndarray, np.ndarray]:
    if len(outputs) < 2:
        raise ValueError("Model outputs must contain bbox and class tensors.")

    a = np.array(outputs[0])
    b = np.array(outputs[1])

    def score_axis_size(x: np.ndarray) -> int:
        s = x.shape
        return max(s) if s else 0

    # Heuristic: bbox output should include axis size 4.
    if 4 in a.shape and 4 not in b.shape:
        bbox_raw, cls_raw = a, b
    elif 4 in b.shape and 4 not in a.shape:
        bbox_raw, cls_raw = b, a
    else:
        bbox_raw, cls_raw = (a, b) if score_axis_size(a) < score_axis_size(b) else (b, a)

    def to_2d(x: np.ndarray) -> np.ndarray:
        if x.ndim == 3 and x.shape[0] == 1:
            x = x[0]
        if x.ndim == 1:
            x = x.reshape(1, -1)
        if x.ndim != 2:
            raise ValueError(f"Unexpected tensor shape: {x.shape}")
        return x

    bbox = to_2d(bbox_raw)
    cls = to_2d(cls_raw)

    # bbox expected as (4, N)
    if bbox.shape[0] == 4:
        pass
    elif bbox.shape[1] == 4:
        bbox = bbox.T
    else:
        raise ValueError(f"Cannot parse bbox tensor shape: {bbox.shape}")

    # cls expected as (C, N)
    if cls.shape[1] == bbox.shape[1]:
        pass
    elif cls.shape[0] == bbox.shape[1]:
        cls = cls.T
    else:
        raise ValueError(f"Cannot align cls tensor shape {cls.shape} with bbox shape {bbox.shape}")

    return bbox, cls


def _maybe_denormalize_boxes(raw_boxes: np.ndarray, model_input_hw: Optional[Tuple[int, int]], box_format: str) -> np.ndarray:
    boxes = raw_boxes.astype(np.float32).copy()
    if boxes.size == 0 or model_input_hw is None:
        return boxes

    in_h, in_w = model_input_hw
    max_abs = float(np.max(np.abs(boxes)))
    min_val = float(np.min(boxes))

    # Some exports output normalized coords in [0, 1].
    if max_abs <= 2.5 and min_val >= -0.5:
        boxes[:, [0, 2]] *= float(in_w)
        boxes[:, [1, 3]] *= float(in_h)
    return boxes


def _to_xyxy(raw_boxes: np.ndarray, box_format: str, model_input_hw: Optional[Tuple[int, int]]) -> np.ndarray:
    if box_format not in {"xyxy", "xywh"}:
        raise ValueError(f"Unsupported box_format: {box_format}")

    boxes = _maybe_denormalize_boxes(raw_boxes, model_input_hw, box_format)
    if box_format == "xyxy":
        return boxes

    out = np.empty_like(boxes)
    out[:, 0] = boxes[:, 0] - boxes[:, 2] / 2.0
    out[:, 1] = boxes[:, 1] - boxes[:, 3] / 2.0
    out[:, 2] = boxes[:, 0] + boxes[:, 2] / 2.0
    out[:, 3] = boxes[:, 1] + boxes[:, 3] / 2.0
    return out


def _candidate_box_score(boxes_xyxy: np.ndarray, model_input_hw: Optional[Tuple[int, int]]) -> float:
    if boxes_xyxy.size == 0:
        return 0.0

    x1 = boxes_xyxy[:, 0]
    y1 = boxes_xyxy[:, 1]
    x2 = boxes_xyxy[:, 2]
    y2 = boxes_xyxy[:, 3]
    ww = x2 - x1
    hh = y2 - y1
    valid = (ww > 2.0) & (hh > 2.0)
    valid_ratio = float(np.mean(valid)) if valid.size else 0.0

    if model_input_hw is not None:
        in_h, in_w = model_input_hw
        cx = (x1 + x2) * 0.5
        cy = (y1 + y2) * 0.5
        center_inside = (cx >= 0.0) & (cx <= float(in_w)) & (cy >= 0.0) & (cy <= float(in_h))
        center_ratio = float(np.mean(center_inside)) if center_inside.size else 0.0
        area_ratio = (np.clip(ww, 0.0, None) * np.clip(hh, 0.0, None)) / max(float(in_w * in_h), 1e-6)
        reasonable_area = (area_ratio > 1e-5) & (area_ratio < 0.85)
        huge_area = area_ratio > 0.95
        area_ratio_score = float(np.mean(reasonable_area)) if reasonable_area.size else 0.0
        huge_penalty = float(np.mean(huge_area)) if huge_area.size else 0.0
    else:
        center_ratio = 1.0
        area_ratio_score = 1.0
        huge_penalty = 0.0

    return valid_ratio * 0.70 + center_ratio * 0.20 + area_ratio_score * 0.10 - huge_penalty * 0.20


def _sanitize_xyxy_boxes(
    boxes_xyxy: np.ndarray,
    shape_hw: Tuple[int, int],
    min_box_size: float,
    box_tighten: float,
    max_area_ratio: float = 0.95,
) -> Tuple[np.ndarray, np.ndarray]:
    if boxes_xyxy.size == 0:
        return boxes_xyxy, np.zeros((0,), dtype=bool)

    boxes = boxes_xyxy.astype(np.float32).copy()
    h, w = shape_hw

    x1 = np.minimum(boxes[:, 0], boxes[:, 2])
    y1 = np.minimum(boxes[:, 1], boxes[:, 3])
    x2 = np.maximum(boxes[:, 0], boxes[:, 2])
    y2 = np.maximum(boxes[:, 1], boxes[:, 3])

    x1 = np.clip(x1, 0, w - 1)
    y1 = np.clip(y1, 0, h - 1)
    x2 = np.clip(x2, 0, w - 1)
    y2 = np.clip(y2, 0, h - 1)

    ww = x2 - x1
    hh = y2 - y1
    area = ww * hh
    max_area = float(w * h) * max(0.05, min(1.0, float(max_area_ratio)))

    keep = (ww >= max(1.0, min_box_size)) & (hh >= max(1.0, min_box_size)) & (area <= max_area)
    if not np.any(keep):
        return np.empty((0, 4), dtype=np.float32), keep

    x1 = x1[keep]
    y1 = y1[keep]
    x2 = x2[keep]
    y2 = y2[keep]

    tighten = float(np.clip(box_tighten, 0.0, 0.45))
    if tighten > 0.0:
        ww = x2 - x1
        hh = y2 - y1
        shrink_x = ww * tighten * 0.5
        shrink_y = hh * tighten * 0.5
        x1 = np.clip(x1 + shrink_x, 0, w - 1)
        y1 = np.clip(y1 + shrink_y, 0, h - 1)
        x2 = np.clip(x2 - shrink_x, 0, w - 1)
        y2 = np.clip(y2 - shrink_y, 0, h - 1)

    out = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)
    return out, keep


def _nms_per_class_indices(boxes: np.ndarray, confs: np.ndarray, class_ids: np.ndarray, conf_thres: float, iou_thres: float) -> np.ndarray:
    if boxes.size == 0:
        return np.empty((0,), dtype=np.int32)

    selected: List[int] = []
    for cls in np.unique(class_ids):
        cls_idx = np.where(class_ids == cls)[0]
        if cls_idx.size == 0:
            continue
        cls_boxes = boxes[cls_idx]
        cls_confs = confs[cls_idx]
        nms_boxes_xywh = []
        for box in cls_boxes:
            x1, y1, x2, y2 = [float(v) for v in box.tolist()]
            nms_boxes_xywh.append([x1, y1, max(0.0, x2 - x1), max(0.0, y2 - y1)])

        indices = cv2.dnn.NMSBoxes(nms_boxes_xywh, cls_confs.tolist(), conf_thres, iou_thres)
        if indices is None or len(indices) == 0:
            continue
        if isinstance(indices, tuple):
            indices = np.array(indices)
        indices = np.array(indices).reshape(-1)
        selected.extend(cls_idx[indices].tolist())

    if not selected:
        return np.empty((0,), dtype=np.int32)

    selected_idx = np.array(selected, dtype=np.int32)
    order = np.argsort(confs[selected_idx])[::-1]
    return selected_idx[order]


def postprocess_split_yolov8(
    outputs: Sequence[np.ndarray],
    conf_thres: float,
    iou_thres: float,
    scale: float,
    pad_left: int,
    pad_top: int,
    original_shape_hw: Tuple[int, int],
    bbox_format: str = "xywh",
    box_tighten: float = 0.06,
    min_box_size: float = 3.0,
    model_input_hw: Optional[Tuple[int, int]] = None,
    debug_info: Optional[dict] = None,
):
    bbox, cls = _normalize_split_outputs(outputs)

    raw_boxes = bbox.T.astype(np.float32)  # (N, 4)

    fmt = str(bbox_format or "auto").strip().lower()
    if fmt not in {"auto", "xywh", "xyxy"}:
        fmt = "auto"

    boxes_xyxy_direct = _to_xyxy(raw_boxes, "xyxy", model_input_hw)
    boxes_xyxy_from_xywh = _to_xyxy(raw_boxes, "xywh", model_input_hw)

    if fmt == "xyxy":
        boxes = boxes_xyxy_direct
        chosen_format = "xyxy"
        score_direct = _candidate_box_score(boxes_xyxy_direct, model_input_hw)
        score_xywh = _candidate_box_score(boxes_xyxy_from_xywh, model_input_hw)
    elif fmt == "xywh":
        boxes = boxes_xyxy_from_xywh
        chosen_format = "xywh"
        score_direct = _candidate_box_score(boxes_xyxy_direct, model_input_hw)
        score_xywh = _candidate_box_score(boxes_xyxy_from_xywh, model_input_hw)
    else:
        score_direct = _candidate_box_score(boxes_xyxy_direct, model_input_hw)
        score_xywh = _candidate_box_score(boxes_xyxy_from_xywh, model_input_hw)
        if score_xywh >= score_direct:
            boxes = boxes_xyxy_from_xywh
            chosen_format = "xywh"
        else:
            boxes = boxes_xyxy_direct
            chosen_format = "xyxy"

    cls_scores_raw = cls.T.astype(np.float32)  # (N, C)
    raw_min = float(np.min(cls_scores_raw)) if cls_scores_raw.size else 0.0
    raw_max = float(np.max(cls_scores_raw)) if cls_scores_raw.size else 0.0

    # Handle different export styles:
    # 1) probabilities in [0, 1]
    # 2) logits (can be negative/greater than 1)
    # 3) quantized positive scores (e.g. int8/uint8 mapped values)
    if raw_min >= 0.0 and raw_max > 1.0:
        cls_scores = cls_scores_raw / max(raw_max, 1e-6)
    elif raw_min < 0.0 or raw_max > 1.0:
        cls_scores = 1.0 / (1.0 + np.exp(-np.clip(cls_scores_raw, -50.0, 50.0)))
    else:
        cls_scores = np.clip(cls_scores_raw, 0.0, 1.0)

    class_ids = np.argmax(cls_scores, axis=1)
    confs = cls_scores[np.arange(cls_scores.shape[0]), class_ids]

    raw_max_conf = float(np.max(confs)) if confs.size else 0.0
    if debug_info is not None:
        debug_info["raw_min"] = raw_min
        debug_info["raw_max"] = raw_max
        debug_info["raw_max_conf"] = raw_max_conf
        debug_info["num_candidates"] = int(confs.shape[0])
        debug_info["bbox_format_selected"] = chosen_format
        debug_info["bbox_score_xyxy"] = float(score_direct)
        debug_info["bbox_score_xywh"] = float(score_xywh)

    keep = confs >= conf_thres
    boxes = boxes[keep]
    confs = confs[keep]
    class_ids = class_ids[keep]

    if debug_info is not None:
        debug_info["num_after_conf"] = int(confs.shape[0])

    if boxes.size == 0:
        return np.empty((0, 4), dtype=np.float32), np.empty((0,), dtype=np.float32), np.empty((0,), dtype=np.int32)

    boxes = scale_boxes_back(boxes, scale, pad_left, pad_top, original_shape_hw)

    boxes, keep_valid = _sanitize_xyxy_boxes(
        boxes,
        shape_hw=original_shape_hw,
        min_box_size=float(min_box_size),
        box_tighten=float(box_tighten),
    )
    if keep_valid.size > 0:
        confs = confs[keep_valid]
        class_ids = class_ids[keep_valid]

    if boxes.size == 0 or confs.size == 0:
        return np.empty((0, 4), dtype=np.float32), np.empty((0,), dtype=np.float32), np.empty((0,), dtype=np.int32)

    indices = _nms_per_class_indices(boxes, confs, class_ids, conf_thres, iou_thres)
    if indices.size == 0:
        return np.empty((0, 4), dtype=np.float32), np.empty((0,), dtype=np.float32), np.empty((0,), dtype=np.int32)

    return boxes[indices], confs[indices], class_ids[indices].astype(np.int32)


class OnnxBackend:
    def __init__(
        self,
        model_path: str,
        imgsz: int,
        input_mode: str = "auto",
        bbox_format: str = "xywh",
        box_tighten: float = 0.06,
    ):
        try:
            import onnxruntime as ort
        except ImportError as e:
            raise RuntimeError("onnxruntime is required for ONNX backend. Install with: pip install onnxruntime") from e

        providers = ["CPUExecutionProvider"]
        self.session = ort.InferenceSession(model_path, providers=providers)
        self.input_name = self.session.get_inputs()[0].name
        self.imgsz = imgsz
        self.model_labels = self._load_model_labels()
        self.input_mode = input_mode
        self.bbox_format = str(bbox_format or "auto").lower()
        self.box_tighten = float(box_tighten)
        self._calibration_frames = 60  # Check more frames
        self._frame_seen = 0
        self._candidate_modes = ["rgb_norm", "bgr_norm", "rgb_255", "bgr_255"]
        self._best_mode = "rgb_norm"
        self._best_mode_conf = -1.0
        self.last_input_mode = "auto_calibrating..."

    def _load_model_labels(self) -> Optional[List[str]]:
        try:
            meta = self.session.get_modelmeta()
            names_raw = meta.custom_metadata_map.get("names", "")
            if not names_raw:
                return None

            # Ultralytics ONNX usually stores names like: {0: 'class_a', 1: 'class_b'}
            parsed = ast.literal_eval(names_raw)
            if isinstance(parsed, dict):
                max_id = max(int(k) for k in parsed.keys()) if parsed else -1
                labels = ["" for _ in range(max_id + 1)]
                for k, v in parsed.items():
                    labels[int(k)] = str(v)
                return [x if x else f"behavior_{i}" for i, x in enumerate(labels)]
        except Exception:
            return None
        return None

    def infer(self, frame_bgr: np.ndarray):
        frame_bgr = self._ensure_bgr(frame_bgr)

        if self.input_mode == "auto":
            # If we haven't seen enough frames OR we haven't found a confident detection yet, keep calibrating
            if self._frame_seen < self._calibration_frames or self._best_mode_conf < 0.35:
                best = None
                for mode in self._candidate_modes:
                    inp, scale, pl, pt = self._preprocess(frame_bgr, mode)
                    outputs = self.session.run(None, {self.input_name: inp})
                    dbg = {}
                    boxes, confs, class_ids = postprocess_split_yolov8(
                        outputs,
                        conf_thres=self.conf_thres,
                        iou_thres=self.iou_thres,
                        scale=scale,
                        pad_left=pl,
                        pad_top=pt,
                        original_shape_hw=frame_bgr.shape[:2],
                        bbox_format=self.bbox_format,
                        box_tighten=self.box_tighten,
                        model_input_hw=(self.imgsz, self.imgsz),
                        debug_info=dbg,
                    )
                    raw_score = float(dbg.get("raw_max_conf", 0.0))
                    n_after = int(dbg.get("num_after_conf", 0))
                    # Penalize unstable modes that create too many candidates.
                    overload = max(0, n_after - 120)
                    quality = 1.0 / (1.0 + overload / 120.0)
                    score = raw_score * quality
                    if best is None or score > best[0]:
                        best = (score, mode, boxes, confs, class_ids, dbg)

                assert best is not None
                score, mode, boxes, confs, class_ids, debug_info = best
                # Only update if the penalized score is better
                if score > self._best_mode_conf:
                    self._best_mode_conf = score
                    self._best_mode = mode
                self.last_input_mode = f"calib:{mode} (best:{self._best_mode_conf:.2f})"
                # if we found a super good match, we can stop calibrating quickly
                if self._best_mode_conf > 0.8:
                    self._frame_seen = self._calibration_frames
            else:
                inp, scale, pl, pt = self._preprocess(frame_bgr, self._best_mode)
                outputs = self.session.run(None, {self.input_name: inp})
                debug_info = {}
                boxes, confs, class_ids = postprocess_split_yolov8(
                    outputs,
                    conf_thres=self.conf_thres,
                    iou_thres=self.iou_thres,
                    scale=scale,
                    pad_left=pl,
                    pad_top=pt,
                    original_shape_hw=frame_bgr.shape[:2],
                    bbox_format=self.bbox_format,
                    box_tighten=self.box_tighten,
                    model_input_hw=(self.imgsz, self.imgsz),
                    debug_info=debug_info,
                )
                self.last_input_mode = self._best_mode
        else:
            inp, scale, pl, pt = self._preprocess(frame_bgr, self.input_mode)
            outputs = self.session.run(None, {self.input_name: inp})
            debug_info = {}
            boxes, confs, class_ids = postprocess_split_yolov8(
                outputs,
                conf_thres=self.conf_thres,
                iou_thres=self.iou_thres,
                scale=scale,
                pad_left=pl,
                pad_top=pt,
                original_shape_hw=frame_bgr.shape[:2],
                bbox_format=self.bbox_format,
                box_tighten=self.box_tighten,
                model_input_hw=(self.imgsz, self.imgsz),
                debug_info=debug_info,
            )
            self.last_input_mode = self.input_mode

        self._frame_seen += 1
        self.last_raw_max_conf = float(debug_info.get("raw_max_conf", 0.0))
        self.last_raw_min = float(debug_info.get("raw_min", 0.0))
        self.last_raw_max = float(debug_info.get("raw_max", 0.0))
        return boxes, confs, class_ids

    def _ensure_bgr(self, frame: np.ndarray) -> np.ndarray:
        if frame is None:
            return frame
        if frame.ndim == 2:
            return cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR)
        if frame.ndim == 3 and frame.shape[2] == 4:
            return cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)
        if frame.ndim == 3 and frame.shape[2] == 3:
            return frame
        return frame

    def _preprocess(self, frame_bgr: np.ndarray, mode: str = "rgb_norm"):
        image, scale, pad_left, pad_top = letterbox(frame_bgr, self.imgsz)
        if mode.startswith("rgb"):
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        chw = image.transpose(2, 0, 1).astype(np.float32)
        if mode.endswith("norm"):
            chw = chw / 255.0
        return np.expand_dims(chw, axis=0), scale, pad_left, pad_top

    def set_thresholds(self, conf_thres: float, iou_thres: float):
        self.conf_thres = conf_thres
        self.iou_thres = iou_thres


class RKNNBackend:
    def __init__(
        self,
        model_path: str,
        imgsz: int,
        npu_core: int = 0,
        input_mode: str = "auto",
        input_layout: str = "nhwc",
        bbox_format: str = "xywh",
        box_tighten: float = 0.06,
    ):
        try:
            from rknnlite.api import RKNNLite
        except ImportError as e:
            raise RuntimeError("rknnlite is required for RKNN backend. Install with: pip install rknnlite") from e

        self.rknn = RKNNLite()
        ret = self.rknn.load_rknn(model_path)
        if ret != 0:
            raise RuntimeError(f"load_rknn failed with code: {ret}")

        core_masks = {
            -1: RKNNLite.NPU_CORE_AUTO,
            0: RKNNLite.NPU_CORE_0,
            1: RKNNLite.NPU_CORE_1,
            2: RKNNLite.NPU_CORE_2,
        }
        core_mask = core_masks.get(npu_core, RKNNLite.NPU_CORE_AUTO)
        ret = self.rknn.init_runtime(core_mask=core_mask)
        if ret != 0:
            raise RuntimeError(f"init_runtime failed with code: {ret}")

        self.imgsz = imgsz
        self.model_labels = None
        self.input_mode = input_mode
        self.input_layout = input_layout
        self.bbox_format = str(bbox_format or "auto").lower()
        self.box_tighten = float(box_tighten)
        
        self._calibration_frames = 60
        self._frame_seen = 0
        self._candidate_modes = ["rgb_255", "bgr_255", "rgb_norm", "bgr_norm"] # RKNN usually prefers 255
        self._best_mode = "rgb_255"
        self._best_mode_conf = -1.0
        self.last_input_mode = "auto_calibrating..."

    def _ensure_bgr(self, frame: np.ndarray) -> np.ndarray:
        if frame is None:
            return frame
        if frame.ndim == 2:
            return cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR)
        if frame.ndim == 3 and frame.shape[2] == 4:
            return cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)
        return frame

    def infer(self, frame_bgr: np.ndarray):
        frame_bgr = self._ensure_bgr(frame_bgr)
        
        if self.input_mode == "auto":
            if self._frame_seen < self._calibration_frames or self._best_mode_conf < 0.35:
                best = None
                for mode in self._candidate_modes:
                    inp, scale, pl, pt = self._preprocess(frame_bgr, mode)
                    if self.input_layout == "nhwc":
                        inp = inp.transpose(0, 2, 3, 1) # NCHW to NHWC
                    outputs = self.rknn.inference(inputs=[inp])
                    dbg = {}
                    boxes, confs, class_ids = postprocess_split_yolov8(
                        outputs,
                        conf_thres=self.conf_thres,
                        iou_thres=self.iou_thres,
                        scale=scale,
                        pad_left=pl,
                        pad_top=pt,
                        original_shape_hw=frame_bgr.shape[:2],
                        bbox_format=self.bbox_format,
                        box_tighten=self.box_tighten,
                        model_input_hw=(self.imgsz, self.imgsz),
                        debug_info=dbg,
                    )
                    raw_score = float(dbg.get("raw_max_conf", 0.0))
                    n_after = int(dbg.get("num_after_conf", 0))
                    overload = max(0, n_after - 120)
                    quality = 1.0 / (1.0 + overload / 120.0)
                    score = raw_score * quality
                    if best is None or score > best[0]:
                        best = (score, mode, boxes, confs, class_ids, dbg)

                assert best is not None
                score, mode, boxes, confs, class_ids, debug_info = best
                if score > self._best_mode_conf:
                    self._best_mode_conf = score
                    self._best_mode = mode
                self.last_input_mode = f"calib:{mode} (best:{self._best_mode_conf:.2f})"
                if self._best_mode_conf > 0.8:
                    self._frame_seen = self._calibration_frames
            else:
                inp, scale, pl, pt = self._preprocess(frame_bgr, self._best_mode)
                if self.input_layout == "nhwc":
                    inp = inp.transpose(0, 2, 3, 1)
                outputs = self.rknn.inference(inputs=[inp])
                debug_info = {}
                boxes, confs, class_ids = postprocess_split_yolov8(
                    outputs,
                    conf_thres=self.conf_thres,
                    iou_thres=self.iou_thres,
                    scale=scale,
                    pad_left=pl,
                    pad_top=pt,
                    original_shape_hw=frame_bgr.shape[:2],
                    bbox_format=self.bbox_format,
                    box_tighten=self.box_tighten,
                    model_input_hw=(self.imgsz, self.imgsz),
                    debug_info=debug_info,
                )
                self.last_input_mode = self._best_mode
        else:
            inp, scale, pl, pt = self._preprocess(frame_bgr, self.input_mode)
            if self.input_layout == "nhwc":
                inp = inp.transpose(0, 2, 3, 1)
            outputs = self.rknn.inference(inputs=[inp])
            debug_info = {}
            boxes, confs, class_ids = postprocess_split_yolov8(
                outputs,
                conf_thres=self.conf_thres,
                iou_thres=self.iou_thres,
                scale=scale,
                pad_left=pl,
                pad_top=pt,
                original_shape_hw=frame_bgr.shape[:2],
                bbox_format=self.bbox_format,
                box_tighten=self.box_tighten,
                model_input_hw=(self.imgsz, self.imgsz),
                debug_info=debug_info,
            )
            self.last_input_mode = self.input_mode

        self._frame_seen += 1
        self.last_raw_max_conf = float(debug_info.get("raw_max_conf", 0.0))
        self.last_raw_min = float(debug_info.get("raw_min", 0.0))
        self.last_raw_max = float(debug_info.get("raw_max", 0.0))
        return boxes, confs, class_ids

    def _preprocess(self, frame_bgr: np.ndarray, mode: str = "rgb_255"):
        image, scale, pad_left, pad_top = letterbox(frame_bgr, self.imgsz)
        if mode.startswith("rgb"):
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        chw = image.transpose(2, 0, 1).astype(np.float32)
        if mode.endswith("norm"):
            chw = chw / 255.0
        return np.expand_dims(chw, axis=0), scale, pad_left, pad_top

    def set_thresholds(self, conf_thres: float, iou_thres: float):
        self.conf_thres = conf_thres
        self.iou_thres = iou_thres

    def release(self):
        self.rknn.release()


class PTBackend:
    def __init__(self, model_path: str, imgsz: int, device: str = "cpu"):
        try:
            from ultralytics import YOLO
        except ImportError as e:
            raise RuntimeError("ultralytics is required for PT backend. Install with: pip install ultralytics") from e

        self.model = YOLO(model_path)
        self.imgsz = imgsz
        self.device = device
        self.model_labels = self._load_model_labels()

    def _load_model_labels(self) -> Optional[List[str]]:
        names = getattr(self.model, "names", None)
        if names is None:
            return None
        if isinstance(names, dict):
            max_id = max(int(k) for k in names.keys()) if names else -1
            labels = ["" for _ in range(max_id + 1)]
            for k, v in names.items():
                labels[int(k)] = str(v)
            return [x if x else f"behavior_{i}" for i, x in enumerate(labels)]
        if isinstance(names, (list, tuple)):
            return [str(x) for x in names]
        return None

    def infer(self, frame_bgr: np.ndarray):
        results = self.model.predict(
            source=frame_bgr,
            imgsz=self.imgsz,
            conf=self.conf_thres,
            iou=self.iou_thres,
            device=self.device,
            verbose=False,
        )
        if not results:
            self.last_raw_max_conf = 0.0
            return np.empty((0, 4), dtype=np.float32), np.empty((0,), dtype=np.float32), np.empty((0,), dtype=np.int32)

        r = results[0]
        boxes = r.boxes.xyxy.cpu().numpy() if r.boxes is not None else np.empty((0, 4), dtype=np.float32)
        confs = r.boxes.conf.cpu().numpy() if r.boxes is not None else np.empty((0,), dtype=np.float32)
        class_ids = r.boxes.cls.cpu().numpy().astype(np.int32) if r.boxes is not None else np.empty((0,), dtype=np.int32)
        
        # NOTE: PT backend filters boxes BEFORE returning them via r.boxes.
        # Thus `raw_max_conf` will be 0.0 if nothing passed `conf_thres`.
        self.last_raw_max_conf = float(np.max(confs)) if confs.size > 0 else 0.0
        self.last_raw_min = 0.0
        self.last_raw_max = 1.0
        return boxes, confs, class_ids

    def set_thresholds(self, conf_thres: float, iou_thres: float):
        self.conf_thres = conf_thres
        self.iou_thres = iou_thres


def choose_backend(
    model_path: str,
    backend: str,
    imgsz: int,
    device: str,
    npu_core: int,
    input_mode: str,
    input_layout: str = "nhwc",
    bbox_format: str = "xywh",
    box_tighten: float = 0.06,
):
    model_suffix = Path(model_path).suffix.lower()

    if backend == "auto":
        if model_suffix == ".rknn":
            backend = "rknn"
        elif model_suffix == ".onnx":
            backend = "onnx"
        elif model_suffix in {".pt", ".pth"}:
            backend = "pt"
        else:
            raise ValueError(f"Cannot infer backend from model extension: {model_suffix}")

    if backend == "onnx":
        return OnnxBackend(model_path, imgsz, input_mode=input_mode, bbox_format=bbox_format, box_tighten=box_tighten), "onnx"
    if backend == "rknn":
        return RKNNBackend(
            model_path,
            imgsz,
            npu_core=npu_core,
            input_mode=input_mode,
            input_layout=input_layout,
            bbox_format=bbox_format,
            box_tighten=box_tighten,
        ), "rknn"
    if backend == "pt":
        return PTBackend(model_path, imgsz, device=device), "pt"

    raise ValueError(f"Unsupported backend: {backend}")


def open_ov13855_camera(source: str, width: int = 1920, height: int = 1080, fps: int = 30):
    """
    适配ELF2开发板OV13855 MIPI摄像头的专用打开函数
    :param source: 摄像头节点（默认11）
    :param width: 摄像头采集宽度（课堂端默认640）
    :param height: 摄像头高度
    :param fps: 帧率（建议15-30）
    :return: cv2.VideoCapture对象
    """
    if not source.isdigit():
        return cv2.VideoCapture(source)

    # OV13855默认节点是/dev/video11，强制使用V4L2接口
    cam_id = int(source)
    cap = cv2.VideoCapture(cam_id, cv2.CAP_V4L2)
    
    # 关键：设置OV13855支持的像素格式（NV12）
    cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'NV12'))
    
    # 设置分辨率（OV13855推荐1920x1080，过高会卡顿）
    if width > 0:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    if height > 0:
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    
    # 设置帧率（OV13855最高支持30fps，建议15fps更稳定）
    if fps > 0:
        cap.set(cv2.CAP_PROP_FPS, fps)
    
    # 设置缓冲区大小（解决帧延迟/卡顿问题）
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    
    # 验证摄像头是否打开
    if not cap.isOpened():
        # 按课堂端默认画面规格重试。
        print(f"[WARN] 无法打开{width}x{height}分辨率，尝试640x480...")
        cap = cv2.VideoCapture(cam_id, cv2.CAP_V4L2)
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'NV12'))
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_FPS, fps)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    
    return cap


def _class_color_bgr(class_id: int) -> Tuple[int, int, int]:
    # Inspired by GUI plotting style: class-based stable colors.
    palette = [
        (56, 56, 255),    # red-ish
        (151, 157, 255),
        (31, 112, 255),
        (29, 178, 255),
        (10, 249, 72),
        (23, 204, 146),
        (134, 219, 61),
        (52, 147, 26),
        (187, 212, 0),
        (168, 153, 44),
        (255, 194, 0),
        (147, 69, 52),
        (255, 115, 100),
        (236, 24, 0),
        (255, 56, 132),
        (133, 0, 82),
        (255, 56, 203),
        (200, 149, 255),
        (199, 55, 255),
        (255, 0, 255),
    ]
    return palette[int(class_id) % len(palette)]


def _text_color_for_bg(color_bgr: Tuple[int, int, int]) -> Tuple[int, int, int]:
    b, g, r = color_bgr
    brightness = 0.114 * b + 0.587 * g + 0.299 * r
    return (0, 0, 0) if brightness > 160 else (255, 255, 255)


def draw_detections(
    frame: np.ndarray,
    boxes: np.ndarray,
    confs: np.ndarray,
    class_ids: np.ndarray,
    labels: List[str],
    behavior_label: str,
    fps_text: str,
    raw_max_conf: float = 0.0,
    input_mode_text: str = "",
):
    frame_h, frame_w = frame.shape[:2]
    line_thickness = 1
    font_scale = max(0.24, min(0.34, max(frame_h, frame_w) / 2400.0))
    font_thickness = 1
    text_ops: List[Tuple[str, Tuple[int, int], Tuple[int, int, int], float, int]] = []

    for box, conf, cls_id in zip(boxes, confs, class_ids):
        x1, y1, x2, y2 = [int(v) for v in box]
        x1 = int(np.clip(x1, 0, frame_w - 1))
        y1 = int(np.clip(y1, 0, frame_h - 1))
        x2 = int(np.clip(x2, 0, frame_w - 1))
        y2 = int(np.clip(y2, 0, frame_h - 1))
        if x2 <= x1 or y2 <= y1:
            continue

        cls_idx = int(cls_id)
        cls_name = labels[cls_idx] if 0 <= cls_idx < len(labels) else f"cls_{cls_idx}"
        cls_name_display = _to_short_label(cls_name)
        color = _class_color_bgr(cls_idx)
        text_color = _text_color_for_bg(color)
        text = cls_name_display

        cv2.rectangle(frame, (x1, y1), (x2, y2), color, line_thickness)

        text_w, text_h, baseline = _measure_text(text, font_scale, font_thickness)
        if (y2 - y1) < text_h + baseline + 4 or (x2 - x1) < text_w + 5:
            continue
        # Keep labels compact and inside the detection box so no global text blocks the video.
        text_y = min(y2 - 2, y1 + text_h + 3)
        bg_x1 = x1
        bg_y1 = max(y1, text_y - text_h - baseline - 2)
        bg_x2 = min(frame_w - 1, x1 + text_w + 5)
        bg_y2 = min(frame_h - 1, text_y + baseline + 1)
        cv2.rectangle(frame, (bg_x1, bg_y1), (bg_x2, bg_y2), color, -1)
        text_ops.append((text, (bg_x1 + 2, text_y), text_color, font_scale, font_thickness))

    use_pil = Image is not None and ImageDraw is not None and any(_contains_non_ascii(item[0]) for item in text_ops)
    if use_pil:
        pil_image = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(pil_image)
        fallback_ops: List[Tuple[str, Tuple[int, int], Tuple[int, int, int], float, int]] = []
        for text, org, color, scale, thickness in text_ops:
            font = _get_pil_font(16 + int(scale * 14))
            if font is None:
                fallback_ops.append((text, org, color, scale, thickness))
                continue
            left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
            text_h = max(1, int(bottom - top))
            draw.text(
                (int(org[0]), int(org[1] - text_h)),
                text,
                font=font,
                fill=(int(color[2]), int(color[1]), int(color[0])),
            )
        frame[:] = cv2.cvtColor(np.array(pil_image), cv2.COLOR_RGB2BGR)
        for text, org, color, scale, thickness in fallback_ops:
            cv2.putText(frame, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)
    else:
        for text, org, color, scale, thickness in text_ops:
            cv2.putText(frame, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)


def parse_args():
    parser = argparse.ArgumentParser(description="ELF2 + OV13855 MIPI Camera Behavior Detection")
    parser.add_argument("--model", type=str, default="yolov8_sim_split.onnx", help="Model path: .onnx/.rknn/.pt")
    parser.add_argument("--backend", type=str, default="auto", choices=["auto", "onnx", "rknn", "pt"], help="Inference backend")
    # 默认摄像头节点改为11（OV13855的正确节点）
    parser.add_argument("--source", type=str, default="11", help="Camera index (OV13855=11) or video path")
    parser.add_argument("--imgsz", type=int, default=640, help="Inference image size")
    parser.add_argument("--conf", type=float, default=0.20, help="Confidence threshold")
    parser.add_argument("--iou", type=float, default=0.45, help="NMS IoU threshold")
    parser.add_argument("--labels", type=str, default="", help="Optional label txt path, one class name per line")
    parser.add_argument("--device", type=str, default="cpu", help="Device for PT backend, e.g. cpu or 0")
    parser.add_argument("--npu-core", type=int, default=-1, choices=[-1, 0, 1, 2], help="RKNN NPU core, -1 auto")
    parser.add_argument("--input-mode", type=str, default="auto", choices=["auto", "rgb_norm", "bgr_norm", "rgb_255", "bgr_255"], help="Input preprocess mode for ONNX")
    parser.add_argument("--input-layout", type=str, default="nhwc", choices=["nhwc", "nchw"], help="RKNN input layout (nhwc/nchw)")
    parser.add_argument(
        "--bbox-format",
        type=str,
        default="xywh",
        choices=["auto", "xywh", "xyxy"],
        help="Split head bbox format. OV13855 默认建议 xywh。",
    )
    parser.add_argument(
        "--box-tighten",
        type=float,
        default=0.06,
        help="Shrink box ratio for tighter visualization (0~0.45).",
    )
    # OV13855默认分辨率改为1920x1080
    parser.add_argument("--camera-width", type=int, default=1920, help="Camera width (OV13855: 1920/1280/640)")
    parser.add_argument("--camera-height", type=int, default=1080, help="Camera height (OV13855: 1080/720/480)")
    # OV13855默认帧率提升到30，Qt端会实时显示实际收到的帧率
    parser.add_argument("--camera-fps", type=int, default=30, help="Camera FPS (OV13855: 15-30)")
    parser.add_argument("--window", type=int, default=12, help="Majority-vote window for behavior smoothing")
    parser.add_argument("--save-video", type=str, default="", help="Optional output video path")
    parser.add_argument("--save-json", type=str, default="", help="Optional output json lines path")
    parser.add_argument("--report-url", type=str, default="", help="Realtime report endpoint, e.g. http://ip:8080/api/realtime/report")
    parser.add_argument("--report-interval", type=float, default=3.0, help="Report window interval in seconds")
    parser.add_argument("--device-id", type=str, default="elf2-ov13855-01", help="Unique device id for realtime reporting")
    parser.add_argument("--class-id", type=str, default="demo-001", help="Classroom id for realtime reporting")
    parser.add_argument("--device-token", type=str, default="", help="Optional device token sent via X-Device-Token")
    parser.add_argument("--report-timeout", type=float, default=2.5, help="HTTP timeout in seconds for realtime reporting")
    parser.add_argument("--report-retries", type=int, default=2, help="Retry count when realtime upload fails")
    parser.add_argument("--report-backoff", type=float, default=0.3, help="Retry backoff base seconds")
    parser.add_argument("--report-spool", type=str, default="runs/realtime/spool.ndjson", help="Local spool file for failed realtime payloads")
    parser.add_argument("--report-spool-max", type=int, default=5000, help="Max records to keep in local spool")
    parser.add_argument("--report-flush-every", type=int, default=3, help="Try flushing queued payload every N windows")
    parser.add_argument("--no-show", action="store_true", help="Disable OpenCV window")
    parser.add_argument("--max-frames", type=int, default=0, help="Stop after N frames, 0 means unlimited")
    return parser.parse_args()


def main():
    args = parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        raise FileNotFoundError(f"Model not found: {model_path.resolve()}")

    # 加载推理后端
    backend, backend_name = choose_backend(
        model_path=str(model_path),
        backend=args.backend,
        imgsz=args.imgsz,
        device=args.device,
        npu_core=args.npu_core,
        input_mode=args.input_mode,
        input_layout=args.input_layout,
        bbox_format=args.bbox_format,
        box_tighten=args.box_tighten,
    )
    backend.set_thresholds(conf_thres=args.conf, iou_thres=args.iou)

    # 打开Qt传入的视频源：数字为摄像头节点，非数字为本地视频路径。
    print(">>> [底层探针]: 正在打开视频源...")
    cap = open_ov13855_camera(
        source=args.source,
        width=args.camera_width,
        height=args.camera_height,
        fps=args.camera_fps,
    )

    if not cap.isOpened():
        raise RuntimeError(f"无法打开视频源: {args.source}")
    print(
        "[INFO] 视频源开启成功: %dx%d @ %.1f FPS，准备进入推理循环..."
        % (
            int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
            int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
            float(cap.get(cv2.CAP_PROP_FPS) or args.camera_fps or 0),
        )
    )

    # 初始化视频保存（如果需要）
    out_writer = None
    if args.save_video:
        out_path = Path(args.save_video)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fps = float(cap.get(cv2.CAP_PROP_FPS) or args.camera_fps or 15.0)
        out_writer = cv2.VideoWriter(str(out_path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))

    # 初始化JSON保存（如果需要）
    json_fp = None
    if args.save_json:
        json_path = Path(args.save_json)
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_fp = json_path.open("w", encoding="utf-8")

    reporter = RealtimeReporter(
        report_url=args.report_url,
        device_id=args.device_id,
        class_id=args.class_id,
        device_token=args.device_token,
        timeout_s=args.report_timeout,
        retries=args.report_retries,
        backoff_s=args.report_backoff,
        spool_path=args.report_spool,
        spool_max_records=args.report_spool_max,
    )

    # 加载标签：优先使用用户传入，其次尝试模型内置类别名，最后回退默认behavior_x
    if args.labels:
        labels = load_labels(args.labels)
    else:
        model_labels = getattr(backend, "model_labels", None)
        labels = model_labels if model_labels else load_labels(None)
    behavior_buffer = deque(maxlen=max(1, args.window))

    frame_idx = 0
    t0 = time.time()
    t_last = t0
    window_idx = 0
    window_start = t0
    window_frame_count = 0
    window_det_frames = 0
    window_conf_sum = 0.0
    window_conf_count = 0
    window_behavior_counter: Dict[str, int] = Counter()
    window_detection_counter: Dict[str, int] = Counter()
    last_data_time = t0
    ui_data_interval = 0.35

    print(f"[INFO] Backend: {backend_name}")
    print(f"[INFO] Model: {model_path}")
    source_desc = f"OV13855 MIPI Camera (dev/video{args.source})" if str(args.source).isdigit() else args.source
    print(f"[INFO] Source: {source_desc}")
    print("[INFO] Press 'q' to quit, 's' to save a debug frame.")
    if reporter.enabled:
        print(f"[INFO] Realtime report enabled: {reporter.report_url}")
        print(f"[INFO] Realtime device_id={reporter.device_id}, class_id={reporter.class_id}, interval={max(1.0, args.report_interval):.1f}s")


    try:
        while True:
            ok, frame = cap.read()
            if not ok or frame is None:
                print("[WARN] Failed to read frame from camera")
                time.sleep(0.01)
                continue
            # 推理预测
            boxes, confs, class_ids = backend.infer(frame)

            # 行为判断（多数投票）
            if class_ids.size > 0:
                winner = int(np.bincount(class_ids).argmax())
                behavior_name = labels[winner] if 0 <= winner < len(labels) else f"cls_{winner}"
            else:
                behavior_name = "listening"
            current_detection_counter: Dict[str, int] = Counter()
            if class_ids.size > 0:
                for cls_id in class_ids:
                    cls_name = labels[int(cls_id)] if 0 <= int(cls_id) < len(labels) else f"cls_{int(cls_id)}"
                    current_detection_counter[cls_name] += 1

            behavior_buffer.append(behavior_name)
            behavior_stable = Counter(behavior_buffer).most_common(1)[0][0] if behavior_buffer else behavior_name

            # 实时窗口聚合
            window_frame_count += 1
            window_behavior_counter[behavior_stable] += 1
            if class_ids.size > 0:
                window_det_frames += 1
                for cls_id in class_ids:
                    cls_name = labels[int(cls_id)] if 0 <= int(cls_id) < len(labels) else f"cls_{int(cls_id)}"
                    window_detection_counter[cls_name] += 1
            if confs.size > 0:
                window_conf_sum += float(np.sum(confs))
                window_conf_count += int(confs.size)

            now = time.time()
            t_last = now

            # 绘制检测结果
            draw_detections(
                frame=frame,
                boxes=boxes,
                confs=confs,
                class_ids=class_ids,
                labels=labels,
                behavior_label=behavior_stable,
                fps_text="",
                raw_max_conf=float(getattr(backend, "last_raw_max_conf", 0.0)),
                input_mode_text=str(getattr(backend, "last_input_mode", "")),
            )
 # === 【恢复无敌稳的 内存盘 + UDP 触发 模式】 ===
# ==================================================
            try:
                # 1. 画面写入内存盘，并通知 Qt
                ui_frame = cv2.resize(frame, (640, 480))
                tmp_file = "/dev/shm/frame_tmp.bmp"
                target_file = "/dev/shm/frame.bmp"
                cv2.imwrite(tmp_file, ui_frame)
                os.replace(tmp_file, target_file)  # 原子替换，防止画面撕裂
                qt_sock.sendto(b"FILE:" + target_file.encode('utf-8'), (QT_IP, QT_PORT))
                
                # 2. 发送统计数据：使用当前模型识别帧的真实类别结果，驱动Qt实时变化
                now_t = time.time()
                if now_t - last_data_time >= ui_data_interval:
                    packet_counter = current_detection_counter if current_detection_counter else Counter({behavior_stable: 0})
                    data_parts = [f"total:{int(class_ids.size)}"]
                    data_parts.extend([f"{k}:{v}" for k, v in packet_counter.items()])
                    data_str = ",".join(data_parts)
                    qt_sock.sendto(b"DATA:" + data_str.encode('utf-8'), (QT_IP, QT_PORT))
                    last_data_time = now_t
            except Exception as e:
                pass 
            # ==================================================
        # ==================================================
            # 保存JSON
            if json_fp is not None:
                items = []
                for box, conf, cls_id in zip(boxes, confs, class_ids):
                    cls_id_int = int(cls_id)
                    items.append(
                        {
                            "bbox": [float(v) for v in box.tolist()],
                            "conf": float(conf),
                            "cls_id": cls_id_int,
                            "cls_name": labels[cls_id_int] if 0 <= cls_id_int < len(labels) else f"cls_{cls_id_int}",
                        }
                    )
                row = {
                    "frame_idx": frame_idx,
                    "ts": now,
                    "behavior": behavior_stable,
                    "detections": items,
                }
                json_fp.write(json.dumps(row, ensure_ascii=False) + "\n")

            # 每个窗口结束后上报一次，失败自动落地到 spool
            report_interval = max(1.0, float(args.report_interval))
            if reporter.enabled and (now - window_start) >= report_interval:
                elapsed_s = max(now - window_start, 1e-6)
                dominant_behavior = max(window_behavior_counter, key=window_behavior_counter.get) if window_behavior_counter else "unknown"
                behavior_rates = {
                    k: (v / max(window_frame_count, 1)) for k, v in window_behavior_counter.items()
                }
                payload = {
                    "schema_version": "1.0.0",
                    "event_type": "realtime_behavior_window",
                    "generated_at": utc_iso_now(),
                    "device_id": reporter.device_id,
                    "class_id": reporter.class_id,
                    "window": {
                        "index": window_idx,
                        "start_ts": window_start,
                        "end_ts": now,
                        "elapsed_s": elapsed_s,
                        "frame_count": window_frame_count,
                        "det_frame_count": window_det_frames,
                        "avg_conf": (window_conf_sum / max(window_conf_count, 1)),
                        "dominant_behavior": dominant_behavior,
                        "behavior_counts": dict(window_behavior_counter),
                        "behavior_rates": behavior_rates,
                        "detection_counts": dict(window_detection_counter),
                    },
                    "runtime": {
                        "backend": backend_name,
                        "input_mode": str(getattr(backend, "last_input_mode", "")),
                        "camera": {
                            "source": args.source,
                            "width": int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
                            "height": int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
                            "fps": float(cap.get(cv2.CAP_PROP_FPS) or args.camera_fps or 0),
                        },
                    },
                }

                reporter.send_or_spool(payload)
                window_idx += 1

                flush_every = max(1, int(args.report_flush_every))
                if window_idx % flush_every == 0:
                    reporter.request_flush(max_records=20)

                if window_idx % 5 == 0:
                    print(
                        "[REPORT] window=%d ok=%d fail=%d queued=%d flushed=%d"
                        % (
                            window_idx,
                            reporter.sent_ok,
                            reporter.sent_fail,
                            reporter.queued_count(),
                            reporter.spool_flushed,
                        )
                    )

                window_start = now
                window_frame_count = 0
                window_det_frames = 0
                window_conf_sum = 0.0
                window_conf_count = 0
                window_behavior_counter.clear()
                window_detection_counter.clear()

            # 保存视频
            if out_writer is not None:
                out_writer.write(frame)

            # 显示画面
            if not args.no_show:
                cv2.imshow("ELF2 + OV13855 Behavior Detection", frame)
                key = cv2.waitKey(1) & 0xFF

            frame_idx += 1


    except KeyboardInterrupt:
        print("[INFO] Interrupted by user")
    finally:
        if reporter.enabled:
            reporter.close(timeout_s=0.2)
        # 释放资源
        cap.release()
        if out_writer is not None:
            out_writer.release()
        if json_fp is not None:
            json_fp.close()
        if hasattr(backend, "release"):
            backend.release()
        if not args.no_show:
            cv2.destroyAllWindows()

    # 输出统计信息
    elapsed = time.time() - t0
    fps_final = frame_idx / max(elapsed, 1e-6)
    print(f"\n[INFO] ==== Detection Summary ====")
    print(f"[INFO] Total frames: {frame_idx}")
    print(f"[INFO] Elapsed time: {elapsed:.2f}s")
    print(f"[INFO] Average FPS: {fps_final:.2f}")
    if reporter.enabled:
        print("[INFO] ==== Realtime Report Summary ====")
        print(f"[INFO] Report OK: {reporter.sent_ok}")
        print(f"[INFO] Report Failed: {reporter.sent_fail}")
        print(f"[INFO] Spool Written: {reporter.spool_written}")
        print(f"[INFO] Spool Flushed: {reporter.spool_flushed}")
        print(f"[INFO] Spool Pending: {reporter.queued_count()}")


if __name__ == "__main__":
    main()
