# 智眸课堂行为智能分析系统 - 提交文件说明

## 1. 项目整体结构

本项目由两部分组成：

- Qt/C++ 前端程序：负责开屏动画、主界面、多页面数据可视化、主题切换、视频显示、全屏查看、暂停/复位等交互。
- Python 视觉识别引擎：负责打开摄像头或本地视频，加载 RKNN/ONNX/PT 模型进行行为识别，并通过 UDP 把视频帧和识别统计结果发送给 Qt 前端。


## 2. 文件说明
### 2.1 Qt界面_C++_程序：

| 文件/文件夹 | 作用说明 |
|---|---|
| `main.cpp`  | Qt 程序入口。创建 `SplashScreen` 与 `MainWindow`，控制全屏启动、开屏动画结束后的淡入过渡和主界面入场动画。 |
| `mainwindow.cpp`  | Qt 主界面核心实现。包含总览、洞察、热力、预警、设置等页面；负责视频显示、按钮控制、页面切换动画、夜间模式、多主题、图表更新、事件流、全屏视频、启动/暂停/停止 Python 引擎等功能。 |
| `mainwindow.h` | `MainWindow` 类声明文件。声明界面控件、图表对象、UDP 接收、Python 进程、页面切换、数据更新、全屏视频等成员与槽函数。 |
| `splashscreen.cpp`  | 开屏动画实现。绘制粒子、光影、书法风格“智眸”文字、动态光环和过渡动画，并在结束时发出 `finished()` 信号进入主界面。 |
| `splashscreen.h`  | `SplashScreen` 类声明文件。定义动画进度属性、粒子数据、绘制接口和结束信号。 |
| `ZhiMouSystem.pro`| Qt qmake 工程文件。声明工程名、Qt 模块依赖 `core/gui/widgets/charts/network`，以及参与编译的 `.cpp`、`.h` 文件。 |
| `ZhiMouSystem` | 编译后的可执行文件。如果比赛要求提交可运行演示包，需要加入对应平台编译出的可执行文件。 |

### 2.2 视觉识别模型_Python_程序：

| 文件/文件夹 | 作用说明 |
|---|---|
| `behavior_detection_ov13855.py`  | Python 端行为识别主程序。负责摄像头/本地视频读取、RKNN/ONNX/PT 推理、检测框绘制、中文标签显示、识别统计、UDP 推送视频帧与数据。当前版本还加入了后台上报队列，避免网络上报导致视频卡顿。 |
| `yolov8_split_int8_rk3588.rknn` | RK3588 NPU 使用的量化 RKNN 模型文件，是行为识别的核心模型权重。Qt 启动 Python 时默认从同目录加载该文件 |


## 3. 本次界面和功能主要涉及：

- `mainwindow.cpp`：主界面布局、页面、主题、图表、视频、动画和交互逻辑。
- `mainwindow.h`：主窗口相关控件、图表和函数声明。
- `main.cpp`：开屏到主界面的衔接逻辑。
- `splashscreen.cpp`：开屏动画绘制和结束节奏。
- `splashscreen.h`：开屏类接口。
- `behavior_detection_ov13855.py`：识别引擎、视频推送、标签显示、异步上报和帧率优化。

- `yolov8_split_int8_rk3588.rknn`：模型权重文件，原样保留。
- `ZhiMouSystem.pro`：工程配置文件，当前无需改动。
- `ZhiMouSystem.pro.user`：Qt Creator 本地配置，非程序逻辑。
- `ZhiMouSystem`：编译产物


