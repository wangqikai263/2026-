# evaluate_tracker.py (最终权威版 - 保持原逻辑；新增 --save_per_frame_json 开关)
import os
import cv2
import numpy as np
import pandas as pd
from tqdm import tqdm
import argparse
import json
from pathlib import Path
import subprocess
import sys
import re
import time  # === FPS 统计新增：用于计时 ===

from src.tracker.boxmot_tracker import BoxMOTTracker
from src.utils.plots import MetricsPlotter
from boxmot import TRACKERS

# === FPS 统计新增：可选的 CUDA 同步，保证计时准确（不影响结果） ===
try:
    import torch
    _HAS_TORCH = torch.cuda.is_available()
except Exception:
    _HAS_TORCH = False

def _cuda_sync():
    if _HAS_TORCH:
        torch.cuda.synchronize()

# 确保TrackEval存在
TRACKEVAL_DIR = Path(__file__).resolve().parent / 'TrackEval'
if not TRACKEVAL_DIR.exists():
    print("Downloading TrackEval repository...")
    subprocess.run(["git", "clone", "https://github.com/JonathonLuiten/TrackEval.git", str(TRACKEVAL_DIR)], check=True)


def parse_mot_results(results_text: str) -> dict:
    """从TrackEval的输出中解析核心指标。"""
    metric_specs = {
        'HOTA': ('HOTA', {'HOTA': 0}), 'MOTA': ('CLEAR', {'MOTA': 0}),
        'IDF1': ('Identity', {'IDF1': 0}), 'AssA': ('HOTA', {'AssA': 2}),
        'IDSW': ('CLEAR', {'IDSW': 12})
    }
    float_fields = {'HOTA', 'MOTA', 'IDF1', 'AssA'}
    metrics = {}
    for key, (section, fields_map) in metric_specs.items():
        match = re.search(fr'{re.escape(section)}.*COMBINED\s+(.*?)\n', results_text, re.DOTALL)
        if match:
            fields = match.group(1).split()
            for field_key, idx in fields_map.items():
                if idx < len(fields):
                    value = fields[idx]
                    metrics[field_key] = float(value) if field_key in float_fields else int(value)
    return metrics


def run_evaluation(args):
    dataset_root = Path(args.dataset_dir)
    split_name = 'train'
    seq_name = args.seq_name

    # --- 关键修正：使用正确的文件夹名 MOT17 ---
    benchmark_split_name = f'MOT17-{split_name}'
    seq_dir = dataset_root / benchmark_split_name / seq_name
    det_file = seq_dir / 'det' / 'det.txt'
    img_folder = seq_dir / 'img1'

    # 1. 运行跟踪器
    tuning_params = {}
    if args.params_json:
        with open(args.params_json, 'r') as f: tuning_params = json.load(f)
    print(f"Initializing tracker '{args.tracker_type}' with params: {tuning_params or 'default'}")
    tracker = BoxMOTTracker(tracker_type=args.tracker_type, per_class=True, override_args=tuning_params)
    all_dets = pd.read_csv(det_file, header=None)
    grouped_dets = all_dets.groupby(0)  # Group by frame_id (column 0)
    total_frames = int(all_dets[0].max())

    all_tracks_for_eval = []

    # === NEW: 逐帧 JSON 输出目录（仅在开关开启时创建，不影响原逻辑） ===
    per_frame_json_dir = None
    if args.save_per_frame_json:  # NEW
        output_root = Path(args.output_dir)
        exp_name = args.tracker_type
        if args.params_json:
            exp_name += f"_{Path(args.params_json).stem}"
        per_frame_json_dir = output_root / exp_name / benchmark_split_name / "per_frame_json" / seq_name
        per_frame_json_dir.mkdir(parents=True, exist_ok=True)

    # === FPS 统计新增：仅跟踪阶段总耗时（秒） ===
    _trk_time_sec = 0.0
    _frames_cnt = 0

    for frame_id in tqdm(range(1, total_frames + 1), desc=f"Running {args.tracker_type}"):
        frame_path = img_folder / f"{frame_id:06d}.jpg"
        current_frame = cv2.imread(str(frame_path))

        try:
            frame_dets_pd = grouped_dets.get_group(frame_id)
            # det.txt format: frame, id, bb_left, bb_top, bb_width, bb_height, conf, x, y, z
            # Columns:         0,   1,    2,      3,        4,          5,        6,  7, 8, 9
            x1 = frame_dets_pd[2].values
            y1 = frame_dets_pd[3].values
            w = frame_dets_pd[4].values
            h = frame_dets_pd[5].values
            conf = frame_dets_pd[6].values
            detections_np = np.stack([x1, y1, x1 + w, y1 + h, conf, np.zeros_like(conf)], axis=1)
        except KeyError:
            detections_np = np.empty((0, 6))

        # === FPS 统计新增：仅包裹 tracker.update 的计时（逐帧 CUDA 同步保证准确） ===
        _t0 = time.perf_counter()
        tracker.update(detections_np, current_frame)
        _cuda_sync()
        _trk_time_sec += (time.perf_counter() - _t0)
        _frames_cnt += 1

        for track in tracker.raw_results:
            x1, y1, x2, y2, track_id, conf, cls_id = track[:7]
            bb_left, bb_top, bb_width, bb_height = x1, y1, x2 - x1, y2 - y1
            all_tracks_for_eval.append(
                [frame_id, int(track_id), bb_left, bb_top, bb_width, bb_height, conf, -1, -1, -1])

        # === NEW: 若开启，则写出当前帧 JSON（即使为空也写空列表，便于对齐） ===
        if args.save_per_frame_json:  # NEW
            per_frame_records = []
            for track in getattr(tracker, 'raw_results', []):
                x1, y1, x2, y2, track_id, conf, cls_id = track[:7]
                per_frame_records.append({
                    "frame": int(frame_id),
                    "track_id": int(track_id),
                    "bbox": [float(x1), float(y1), float(x2), float(y2)],
                    "bbox_xywh": [float(x1), float(y1), float(x2 - x1), float(y2 - y1)],
                    "conf": float(conf),
                    "cls": int(cls_id) if isinstance(cls_id, (int, np.integer)) else float(cls_id),
                    "image_path": str(frame_path)
                })
            json_path = per_frame_json_dir / f"{frame_id:06d}.json"
            with open(json_path, "w", encoding="utf-8") as jf:
                json.dump(per_frame_records, jf, ensure_ascii=False, indent=2)

    # === FPS 统计新增：打印仅跟踪阶段 FPS（不产生任何新文件，不改其它流程） ===
    if _trk_time_sec > 0 and _frames_cnt > 0:
        _fps_trk = _frames_cnt / _trk_time_sec
        _lat_ms = 1000.0 / _fps_trk
        print("\n[Speed] ===== TRACKER-ONLY THROUGHPUT =====")
        print(f"[Speed] Frames={_frames_cnt} | Track-only time={_trk_time_sec:.2f}s")
        print(f"[Speed] FPS_trk = {_fps_trk:.2f}  |  Latency ≈ {_lat_ms:.2f} ms/frame")
        print("[Speed] ====================================\n")

    # 2. 保存跟踪结果（整序列 MOT 格式）
    output_root = Path(args.output_dir)
    exp_name = args.tracker_type
    if args.params_json:
        exp_name += f"_{Path(args.params_json).stem}"
    tracker_exp_dir = output_root / exp_name
    tracker_results_dir = tracker_exp_dir / benchmark_split_name / "data"
    tracker_results_dir.mkdir(parents=True, exist_ok=True)
    ts_path = tracker_results_dir / f"{seq_name}.txt"
    np.savetxt(ts_path, np.array(all_tracks_for_eval), delimiter=',',
               fmt=['%d', '%d', '%.2f', '%.2f', '%.2f', '%.2f', '%.2f', '%d', '%d', '%d'])
    print(f"\nTracking results saved to: {ts_path}")
    if args.save_per_frame_json:  # NEW
        print(f"Per-frame JSON saved under: {per_frame_json_dir}")

    # 3. 调用TrackEval
    command = [
        sys.executable, str(TRACKEVAL_DIR / 'scripts/run_mot_challenge.py'),
        '--GT_FOLDER', str(dataset_root),
        '--TRACKERS_FOLDER', str(tracker_exp_dir),  # 指向包含MOT17-train的文件夹
        '--BENCHMARK', 'MOT17',
        '--SPLIT_TO_EVAL', split_name,
        '--TRACKERS_TO_EVAL', '',  # 留空，让它自动发现
        '--METRICS', 'HOTA', 'CLEAR', 'Identity'
    ]
    print("\nRunning TrackEval...")
    result = subprocess.run(command, capture_output=True, text=True)

    # 4. 查找、解析和显示结果
    summary_files = list(tracker_exp_dir.glob('**/summary.txt'))

    if summary_files:
        summary_file = summary_files[0]
        print("TrackEval finished successfully.")
        with open(summary_file, 'r') as f:
            summary_text = f.read()
        print("\n--- TrackEval Official Summary ---")
        print(summary_text)
        metrics_dict = parse_mot_results(summary_text)
        if metrics_dict:
            plot_categories = ['HOTA', 'MOTA', 'IDF1', 'AssA', 'IDSW']
            plot_values = [metrics_dict.get(cat, 0) for cat in plot_categories]
            if 'IDSW' in metrics_dict and metrics_dict['IDSW'] > 0:
                plot_values[plot_categories.index('IDSW')] = max(0, 100 - metrics_dict['IDSW'] * 2)
            else:
                plot_values[plot_categories.index('IDSW')] = 100
            plotter = MetricsPlotter(save_dir=tracker_exp_dir)
            plotter.plot_radar_chart(data={exp_name: plot_values}, categories=plot_categories,
                                     title=f"{exp_name} Performance")
    else:
        print("--- TrackEval Error ---")
        print("Could not find summary file. Full output below:")
        print("Stdout:", result.stdout)
        print("Stderr:", result.stderr)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Run and evaluate a tracker on a MOT formatted dataset.")
    parser.add_argument('--dataset_dir', type=str, required=True, help="Path to the root of the MOT formatted dataset.")
    parser.add_argument('--output_dir', type=str, default='runs/evaluation',
                        help="Root directory to save all experiment outputs.")
    parser.add_argument('--tracker_type', type=str, default='botsort', choices=TRACKERS)
    parser.add_argument('--params_json', type=str, default=None, help="Path to a JSON file with tracker parameters.")
    parser.add_argument('--seq_name', type=str, default='Classroom-01',
                        help="Sequence name to evaluate inside the dataset directory.")
    parser.add_argument('--save_per_frame_json', action='store_true', default=False,   # NEW
                        help='开启后将每帧的跟踪结果保存为 JSON（默认关闭）。')  # NEW
    args = parser.parse_args()
    args.tracker_type = args.tracker_type.lower()
    run_evaluation(args)
