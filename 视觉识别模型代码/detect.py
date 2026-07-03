import warnings
import os
from pathlib import Path
warnings.filterwarnings('ignore')
from ultralytics import YOLO


if __name__ == '__main__':
    # 模型权重路径
    weight_path = 'best.pt'
    model = YOLO(weight_path)  # select your model.pt path

    # 视频文件路径 (请修改为您实际的视频路径，例如 'video.mp4' 或 '0' 调用摄像头)
    video_path = '1.mp4'

    # 视频推理
    results = model.predict(source=video_path,
                            conf=0.5,
                            project='runs/detect/video_predict',
                            name='exp',
                            save=True,        # 保存预测结果视频
                            visualize=False,
                            line_width=2,
                            show_conf=True,
                            show_labels=True,
                            save_txt=False,   # 视频预测通常不需要保存大量txt
                            save_crop=False,
                            device='0'        # 使用GPU推理（无GPU时可改为 'cpu'）
                            )

    print(f"视频推理完成，结果已保存至 runs/detect/video_predict")
    