import tkinter as tk
from tkinter import filedialog, messagebox
import cv2
from PIL import Image, ImageTk
#from ultralytics import RTDETR
from ultralytics import YOLO
import os
import sys
import torch
import time
import numpy as np
from pathlib import Path

# --- BoxMOT 路径注入 ---
_BOXMOT_ROOT = Path(__file__).resolve().parent.parent / "boxmot"
if _BOXMOT_ROOT.exists() and str(_BOXMOT_ROOT) not in sys.path:
    sys.path.insert(0, str(_BOXMOT_ROOT))

# 注意：_create_tracker / _parse_boxes_to_array 延迟导入，避免与 detect_to_json 循环引用
_tracker_utils_loaded = False
_create_tracker = None
_parse_boxes_to_array = None


def _ensure_tracker_utils():
    """延迟加载 detect_to_json 中的追踪工具函数，避免循环导入。"""
    global _tracker_utils_loaded, _create_tracker, _parse_boxes_to_array
    if _tracker_utils_loaded:
        return
    from detect_to_json import _create_tracker as _ct, _parse_boxes_to_array as _pb
    _create_tracker = _ct
    _parse_boxes_to_array = _pb
    _tracker_utils_loaded = True


class App:
    def __init__(self, window, window_title):
        self.window = window
        self.window.title(window_title)

        self.video_source = 0  # 默认摄像头
        self.vid = None
        self.out = None  # 视频保存对象
        self.model = None
        self.is_running = False
        self.photo = None
        self.device = '0' if torch.cuda.is_available() else 'cpu'
        print(f"使用设备: {self.device}")

        # FPS 计算相关
        self.prev_time = 0
        self.curr_time = 0
        self.frame_count = 0
        self.skip_frames = 0

        # Tracker 相关
        self.tracker = None
        self.tracking_enabled = False
        self.last_raw_dets = np.empty((0, 6), dtype=np.float32)

        # 默认模型路径
        self.default_model_path = 'best.pt'
        if os.path.exists(self.default_model_path):
            self.model_path = self.default_model_path
        else:
            self.model_path = None

        # UI 布局
        # 顶部控制栏
        self.control_frame = tk.Frame(window)
        self.control_frame.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)

        self.btn_select_model = tk.Button(self.control_frame, text="选择模型权重 (.pt)", command=self.select_model)
        self.btn_select_model.pack(side=tk.LEFT, padx=5)

        self.lbl_model = tk.Label(
            self.control_frame,
            text=f"当前模型: {os.path.basename(self.model_path) if self.model_path else '未选择'}"
        )
        self.lbl_model.pack(side=tk.LEFT, padx=5)

        self.btn_select_video = tk.Button(self.control_frame, text="选择视频文件", command=self.select_video)
        self.btn_select_video.pack(side=tk.LEFT, padx=5)

        self.btn_use_cam = tk.Button(self.control_frame, text="使用摄像头", command=self.use_camera)
        self.btn_use_cam.pack(side=tk.LEFT, padx=5)

        # 增加保存开关
        self.save_var = tk.BooleanVar(value=False)
        self.chk_save = tk.Checkbutton(self.control_frame, text="保存结果视频", variable=self.save_var)
        self.chk_save.pack(side=tk.LEFT, padx=5)

        # 追踪开关
        self.track_var = tk.BooleanVar(value=False)
        self.chk_track = tk.Checkbutton(self.control_frame, text="启用追踪", variable=self.track_var)
        self.chk_track.pack(side=tk.LEFT, padx=5)

        self.lbl_skip = tk.Label(self.control_frame, text="跳帧:")
        self.lbl_skip.pack(side=tk.LEFT, padx=2)
        self.spin_skip = tk.Spinbox(self.control_frame, from_=0, to=10, width=3, command=self.update_skip)
        self.spin_skip.pack(side=tk.LEFT, padx=2)

        self.btn_start = tk.Button(
            self.control_frame, text="开始推理", bg="green", fg="white", command=self.start_inference
        )
        self.btn_start.pack(side=tk.LEFT, padx=20)

        self.btn_stop = tk.Button(
            self.control_frame, text="停止", bg="red", fg="white", command=self.stop_inference
        )
        self.btn_stop.pack(side=tk.LEFT, padx=5)

        # 视频显示区域
        self.canvas_width = 800
        self.canvas_height = 600
        self.canvas = tk.Canvas(window, width=self.canvas_width, height=self.canvas_height, bg="black")
        self.canvas.pack(padx=10, pady=10)

        # 自动加载默认模型
        if self.model_path:
            self.load_model(self.model_path)
            print(f"默认模型已加载: {self.model_path}")

        self.delay = 1  # 刷新间隔 ms (尽可能快)
        self.update()

        self.window.mainloop()

    def update_skip(self):
        try:
            self.skip_frames = int(self.spin_skip.get())
        except ValueError:
            self.skip_frames = 0

    def select_model(self):
        path = filedialog.askopenfilename(filetypes=[("Model files", "*.pt")])
        if path:
            self.load_model(path)

    def load_model(self, path):
        try:
            print(f"正在加载模型: {path} ...")
            self.model = YOLO(path)
            self.model_path = path
            self.lbl_model.config(text=f"当前模型: {os.path.basename(path)}")
            print("模型加载成功")
        except Exception as e:
            messagebox.showerror("错误", f"加载模型失败: {e}")

    def select_video(self):
        path = filedialog.askopenfilename(filetypes=[("Video files", "*.mp4;*.avi;*.mkv;*.mov")])
        if path:
            self.video_source = path
            print(f"已选择视频源: {path}")
            # 如果正在运行，重启
            if self.is_running:
                self.stop_inference()
                self.start_inference()

    def use_camera(self):
        self.video_source = 0
        print("已切换至摄像头模式")
        if self.is_running:
            self.stop_inference()
            self.start_inference()

    def start_inference(self):
        if not self.model:
            messagebox.showwarning("提示", "请先选择模型权重文件！")
            return

        if self.vid is None:
            try:
                self.vid = MyVideoCapture(self.video_source)
            except ValueError:
                messagebox.showerror("错误", "无法打开视频源")
                return

        # 初始化视频保存
        if self.save_var.get():
            save_dir = Path("runs/detect")
            save_dir.mkdir(parents=True, exist_ok=True)
            save_path = str(save_dir / "gui_record.mp4")

            width = int(self.vid.vid.get(cv2.CAP_PROP_FRAME_WIDTH))
            height = int(self.vid.vid.get(cv2.CAP_PROP_FRAME_HEIGHT))
            fps = self.vid.vid.get(cv2.CAP_PROP_FPS)
            if fps == 0 or fps is None:
                fps = 25.0  # 摄像头可能获取不到fps

            fourcc = cv2.VideoWriter_fourcc(*'mp4v')
            self.out = cv2.VideoWriter(save_path, fourcc, fps, (width, height))
            print(f"开始录制，文件将保存至: {save_path}")

        # 初始化 tracker（如果勾选了追踪）
        self.tracking_enabled = self.track_var.get()
        self.tracker = None
        self.last_raw_dets = np.empty((0, 6), dtype=np.float32)
        if self.tracking_enabled:
            _ensure_tracker_utils()
            self.tracker = _create_tracker(
                tracker_type="bytetrack",
                device=self.device,
                per_class=True,
            )
            if self.tracker is None:
                self.tracking_enabled = False
                print("[WARN] Tracker 创建失败，退化为纯检测模式")
            else:
                print("ByteTrack 追踪已启动")

        self.is_running = True
        self.prev_time = time.time()
        print("开始推理...")

    def stop_inference(self):
        self.is_running = False
        if self.vid:
            self.vid.__del__()
            self.vid = None
        if self.out:
            self.out.release()
            self.out = None
            print("录制结束")
        self.canvas.delete("all")
        print("推理停止")

    def update(self):
        if self.is_running and self.vid:
            ret, frame = self.vid.get_frame()
            if ret:
                self.frame_count += 1

                # 计算 FPS
                self.curr_time = time.time()
                fps_val = 1 / (self.curr_time - self.prev_time) if (self.curr_time - self.prev_time) > 0 else 0
                self.prev_time = self.curr_time

                # 跳帧逻辑
                if self.frame_count % (self.skip_frames + 1) == 0:
                    # 推理
                    results = self.model.predict(frame, conf=0.5, verbose=False, device=self.device)
                    res = results[0] if results else None

                    if self.tracking_enabled and self.tracker is not None:
                        _ensure_tracker_utils()
                        names = getattr(self.model, "names", None)
                        raw_dets = _parse_boxes_to_array(res, names)
                        self.last_raw_dets = raw_dets
                        try:
                            tracked = self.tracker.update(raw_dets, frame)
                        except Exception:
                            tracked = None

                        annotated_frame = res.plot() if res else frame.copy()
                        # 绘制 track_id 标注
                        if tracked is not None and len(tracked) > 0:
                            for row in tracked:
                                if len(row) >= 7:
                                    x1, y1, x2, y2 = int(row[0]), int(row[1]), int(row[2]), int(row[3])
                                    tid = int(row[4])
                                    cv2.rectangle(annotated_frame, (x1, y1), (x2, y2), (0, 255, 255), 2)
                                    cv2.putText(
                                        annotated_frame, f"ID:{tid}",
                                        (x1, y1 - 8), cv2.FONT_HERSHEY_SIMPLEX,
                                        0.6, (0, 255, 255), 2,
                                    )
                    else:
                        annotated_frame = res.plot() if res else frame.copy()
                else:
                    # 跳帧但仍 tracker.update（保持跟踪连续性）
                    if self.tracking_enabled and self.tracker is not None:
                        try:
                            self.tracker.update(self.last_raw_dets, frame)
                        except Exception:
                            pass
                    annotated_frame = frame

                # 写 FPS
                cv2.putText(
                    annotated_frame,
                    f"FPS: {fps_val:.1f}",
                    (20, 40),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    1,
                    (0, 255, 0),
                    2,
                )

                # 保存视频
                if self.out:
                    self.out.write(annotated_frame)

                # 调整大小以适应 Canvas
                img_h, img_w = annotated_frame.shape[:2]
                scale = min(self.canvas_width / img_w, self.canvas_height / img_h)
                new_w = int(img_w * scale)
                new_h = int(img_h * scale)
                resized_frame = cv2.resize(annotated_frame, (new_w, new_h))

                # 转换为 tkinter 格式
                frame_rgb = cv2.cvtColor(resized_frame, cv2.COLOR_BGR2RGB)
                self.photo = ImageTk.PhotoImage(image=Image.fromarray(frame_rgb))
                self.canvas.create_image(
                    self.canvas_width // 2, self.canvas_height // 2, image=self.photo, anchor=tk.CENTER
                )
            else:
                # 视频播放结束或读取失败
                if isinstance(self.video_source, str):  # 如果是文件
                    print("视频播放结束")
                    self.stop_inference()

        self.window.after(self.delay, self.update)


class MyVideoCapture:
    def __init__(self, video_source=0):
        self.vid = cv2.VideoCapture(video_source)
        if not self.vid.isOpened():
            raise ValueError("Unable to open video source", video_source)

    def get_frame(self):
        if self.vid.isOpened():
            ret, frame = self.vid.read()
            if ret:
                return ret, frame
            else:
                return ret, None
        else:
            return False, None

    def __del__(self):
        if self.vid.isOpened():
            self.vid.release()




# ---------------- 可复用推理 API（供其他脚本调用） ----------------
def load_model(weights: str):
    """加载并返回 YOLO 模型实例。"""
    return YOLO(weights)


def stream_predict_video_from_model(model, video_path: str, conf: float = 0.35, device: str = "0"):
    """
    使用已经加载好的模型，按帧读取视频并逐帧预测。
    返回一个生成器，yield 每帧的 Results（或空占位对象），供别的模块复用。
    """
    import cv2

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video {video_path}")

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                break
            try:
                res_list = model.predict(frame, conf=conf, device=device, verbose=False)
                if res_list:
                    yield res_list[0]
                else:
                    class _Empty:
                        pass

                    e = _Empty()
                    e.boxes = None
                    yield e
            except Exception:
                class _Empty:
                    pass

                e = _Empty()
                e.boxes = None
                yield e
    finally:
        cap.release()


def stream_predict_video(weights: str, video_path: str, conf: float = 0.35, device: str = "0"):
    """
    保留原有 API：给定权重路径 + 视频路径，内部自己加载模型再逐帧推理。
    detect_to_json 现在不再用这个函数，而是用上面的 stream_predict_video_from_model。
    """
    model = YOLO(weights)
    yield from stream_predict_video_from_model(model, video_path, conf=conf, device=device)


def stream_predict_video_with_tracking(
    weights: str,
    video_path: str,
    conf: float = 0.35,
    device: str = "0",
    tracker_type: str = "bytetrack",
    per_class: bool = True,
):
    """
    带追踪的流式推理 API（v0.2 新增）。
    yield (frame, tracked_dets)：
      - frame: BGR numpy 原始帧
      - tracked_dets: (N, 8) array [x1,y1,x2,y2,id,conf,cls,ind]  或  (0, 8) 空数组
    """
    _ensure_tracker_utils()
    model = YOLO(weights)
    names = getattr(model, "names", None)
    tracker = _create_tracker(
        tracker_type=tracker_type,
        device=device,
        per_class=per_class,
    )

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video {video_path}")

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                break
            try:
                res_list = model.predict(frame, conf=conf, device=device, verbose=False)
                res = res_list[0] if res_list else None
            except Exception:
                res = None
            raw_dets = _parse_boxes_to_array(res, names)

            tracked_dets = np.empty((0, 8), dtype=np.float64)
            if tracker is not None:
                try:
                    result = tracker.update(raw_dets, frame)
                    if result is not None and len(result) > 0:
                        tracked_dets = np.array(result, dtype=np.float64)
                except Exception:
                    pass
            yield frame, tracked_dets
    finally:
        cap.release()


if __name__ == "__main__":
    root = tk.Tk()
    # 居中窗口
    screen_width = root.winfo_screenwidth()
    screen_height = root.winfo_screenheight()
    x = (screen_width - 850) // 2
    y = (screen_height - 700) // 2
    root.geometry(f"850x700+{x}+{y}")

    App(root, "YOLOv8 实时推理可视化系统")
