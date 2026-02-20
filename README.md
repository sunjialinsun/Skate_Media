Skating Pose – Figure Skating Pose & Jump Analyzer
==================================================

English
-------

### Overview

Skating Pose is an Android demo app built on **Google AI Edge / MediaPipe Tasks (Pose Landmarker)** to support **figure skating pose visualization and jump analysis**.

It uses the device camera to detect human body keypoints, draws a colorized skeleton overlay, and records pose keypoints while you capture. After you stop, it runs jump analysis (height, airtime, rotation, type) and shows a result screen. The app is intended as an experimental tool, *not* as an official ISU judging system.

The current implementation focuses on:

- Real‑time pose keypoint detection on device
- Skeleton visualization with iOS‑style colors
- Recording pose keypoints during capture and **offline jump analysis** once you stop
- A jump result screen with ISU‑style jump codes (e.g. `2A`, `3T`, `3Lz`)
- A scrollable information panel showing recent jump events

> Note: **Spin (rotation) detection code has been removed** in order to optimize per‑frame inference time. The app currently only analyzes jumps.

---

### Main Features

- **Camera-based live capture**
  - Uses **CameraX** to stream frames from the back camera.
  - Runs **MediaPipe Pose Landmarker** in `LIVE_STREAM` mode for low-latency inference.

- **Pose keypoint overlay**
  - Draws a full-body skeleton on top of the camera preview using a custom `OverlayView`.
  - Different body parts are rendered with different colors (head, torso, arms, legs).
  - Line width and style are tuned for a clear but not too thick display.

- **iOS-style UI, modes and FPS display**
  - Main preview card with rounded corners and light iOS-like color palette.
  - Top-right corner shows smoothed **FPS** using Gemini-style yellow text.
  - Bottom center has an iOS-style record button that toggles **Start / Stop** capture.
  - Top tabs let you switch between **Jump** and **Spin** modes.

- **Keypoint information panel**
  - Middle area is a scrollable info panel showing per-frame and per-jump statistics.
  - For each frame, the app displays:
    - Inference time per frame (ms)
    - Number of detected pose landmarks
    - Total accumulated rotation turns (from hip orientation)
    - Current rotation speed (turns/second)
    - Three keypoint coordinates in one line:
      - **H** – head (nose)
      - **T** – torso center (average of left/right hip)
      - **A** – ankle center (average of left/right ankle)

- **Jump detection and metrics (offline after capture)**
  - Detects airtime based on ankle height relative to an estimated ground level.
  - Estimates jump height (cm) by:
    - Using the normalized distance between nose and ankle as a proxy for body height.
    - Assuming a fixed real-world body height (e.g. 160 cm) to convert to centimeters.
  - Tracks hip rotation angle over the air phase to estimate:
    - Rotation angle (degrees)
    - Rotation turns (e.g. 1.0, 2.5)
  - Applies additional filters to reduce false positives:
    - Minimum jump height and airtime thresholds.
    - Rotation threshold: requires a minimum fraction of a full turn.
    - Horizontal movement constraint during airtime (to distinguish jumps vs. large gliding steps).

- **ISU-style jump type classification**
  - Uses a small heuristic classifier (`classifyJumpType`) over a short frame window to guess jump type:
    - **Axel (A)**
    - **Toe loop (T)**
    - **Flip (F)**
    - **Lutz (Lz)**
    - **Salchow (S)**
    - **Loop (Lo)**
    - `Jump?` for unknown/uncertain patterns
  - Looks at:
    - Entry direction (forward / backward)
    - Edge usage and torso tilt
    - Toe-pick behavior (ankle vertical motion)
    - Knee bending and extension symmetry

- **Recent jump list (English)**
  - After analysis, the result screen and info panel show recent jumps in reverse chronological order:
    - Example line:
      - `t=123456ms 3Lz height=35.2cm airtime=620ms rotation=1040.0deg (2.89 turns)`
  - This makes it easier to review the latest attempts without pausing the camera.

---

### Implementation Notes

- **Language & stack**
  - Kotlin-based Android app.
  - Uses official **MediaPipe Tasks for Android – Vision / Pose Landmarker** dependency.
  - Uses **CameraX** `Preview` + `ImageAnalysis` pipeline.

- **Core classes (simplified)**
  - `SkateActivity`
    - Hosts the camera preview and overlay.
    - Receives pose results from `PoseLandmarkerHelper`.
    - Maintains running state, FPS smoothing, jump detection state, and the info text.
  - `PoseLandmarkerHelper`
    - Thin wrapper around MediaPipe Pose Landmarker.
    - Configured for `LIVE_STREAM` mode and RGBA input.
  - `OverlayView`
    - Custom view to draw pose landmarks and skeleton lines on top of the preview.

- **Jump detection pipeline**
  - Smoothly tracks an estimated ground ankle height when the skater is not in the air.
  - Detects entry into air when ankle height rises above a threshold.
  - During airtime:
    - Tracks minimum ankle height.
    - Integrates hip rotation angle (unwrapped) to compute rotation turns.
  - On landing:
    - Computes airtime (ms).
    - Estimates jump height (cm) and rotation.
    - Applies thresholds and movement filters.
    - Classifies jump type via `classifyJumpType`.
    - Appends a `JumpEvent` to the `jumpEvents` list.

---

### Current Limitations and Future Work

- **Jump recognition is still heuristic and needs further optimization**:
  - Height / airtime thresholds are hand-tuned and may not generalize to all skaters.
  - Edge / entry detection is approximate and may misclassify certain jumps.
  - The assumed body height (for cm conversion) is fixed and not athlete-specific.

- **Not a judging tool**
  - This app is for experimentation, coaching support, and visualization only.
  - It should **not** be used as an official scoring or judging system.

Possible future improvements:

- Learn thresholds and jump patterns from labeled training data.
- Calibrate body height per skater to improve absolute height estimation.
- Re-introduce spin detection with a more lightweight algorithm, once jump detection is stable.
- Re-enable on-device video recording for all devices; currently some devices only support skeleton preview and offline analysis without saving MP4 files.

---

### How to Build and Run

1. Open the project in **Android Studio** (Giraffe or newer recommended).
2. Make sure you have:
   - Android SDK and build tools installed.
   - A device or emulator with camera access (physical device strongly recommended).
3. Sync Gradle and build the project.
4. Run the `app` module on an Android device.
5. Grant camera permission and tap the **record button** to begin live pose capture. After you stop, the app runs jump analysis and shows a result screen.

---

中文说明
--------

### 概述

**Skating Pose** 是一个基于 **Google AI Edge / MediaPipe Tasks（姿势地标 Pose Landmarker）** 的 Android 演示应用，用于 **花样滑冰动作检测与关键点可视化**。

应用通过手机摄像头实时识别人类身体关键点，在画面上叠加彩色骨架，并在录制过程中持续记录关键点与时间戳。在你停止录制后，应用会离线计算 **跳跃动作** 的高度、腾空时间、旋转圈数和大致类型，并展示结果页面。  
本项目主要作为 **技术实验与教练辅助工具**，并非任何官方裁判系统。

当前实现的重点包括：

- 基于 MediaPipe 的实时姿态关键点检测
- iOS 风格配色的人体骨架可视化
- 录制过程中记录关键点数据，录制结束后进行 **离线跳跃分析**
- 结果页面展示 ISU 风格的跳跃缩写（如 `2A`、`3T`、`3Lz`）
- 可滚动的信息栏，展示最近几次跳跃的详细数据

> 说明：为了优化每帧推理时间，当前版本已 **移除旋转（旋转步/旋转姿势）的检测代码**，只保留跳跃分析。

---

### 主要功能

- **摄像头实时捕捉**
  - 使用 **CameraX** 从后置摄像头获取视频流。
  - 调用 **MediaPipe Pose Landmarker**，以 `LIVE_STREAM` 模式低延迟运行。

- **人体关键点骨架叠加**
  - 通过自定义 `OverlayView` 在预览画面上绘制人体骨架。
  - 不同身体部位（头部、躯干、手臂、腿部）使用不同颜色显示。
  - 线宽做了适中调整，既突出骨架又不会遮挡太多画面。

- **iOS 风格 UI + 模式切换 + FPS 显示**
  - 顶部为带圆角的预览卡片，整体颜色偏向 iOS 风格的浅色系。
  - 右上角显示平滑后的 **FPS**，采用类似 Gemini 风格的黄色字体。
  - 底部中间为 iOS 风格录制按钮，用于 **开始 / 停止** 捕捉。
  - 顶部标签可在 **Jump** 与 **Spin** 模式之间切换。

- **关键点信息栏**
  - 中部信息区域为可滚动的文本，展示帧级与跳跃级的数据。
  - 对每一帧，显示：
    - 当前帧推理时间（毫秒）
    - 检测到的关键点数量
    - 总旋转圈数（基于髋部朝向累计角度）
    - 当前旋转速度（圈/秒）
    - 三个代表性关键点坐标（同一行）：
      - **H**：头部（鼻尖）
      - **T**：躯干中心（左右髋关节的平均）
      - **A**：脚踝中心（左右脚踝的平均）

- **跳跃检测与指标（录制结束后的离线计算）**
  - 根据脚踝相对“地面”的高度变化来判断是否腾空。
  - 使用鼻子到脚踝的归一化距离估计身体高度，假定固定身高（例如 160 cm），换算出跳跃高度（厘米）。
  - 在腾空期间，跟踪髋部的旋转角度，计算：
    - 旋转角度（度）
    - 旋转圈数（如 1.0、2.5）
  - 为减少误判，对跳跃加入以下约束：
    - 最小高度与最小腾空时间阈值
    - 最小旋转圈数要求
    - 腾空期间水平位移限制（用于区分“跳跃”与“大幅滑行”）

- **ISU 风格跳跃类型判定**
  - 通过一个简单的启发式分类函数 `classifyJumpType`，在一个短时间窗口内根据步法与姿势特征推测跳跃类型：
    - **Axel (A) 阿克塞尔前外跳**
    - **Toe loop (T) 后外点冰跳**
    - **Flip (F) 后内点冰跳**
    - **Lutz (Lz) 勾手跳**
    - **Salchow (S) 后内结环跳**
    - **Loop (Lo) 后外结环跳**
    - 无法确定时标记为 `Jump?`
  - 考察的特征包括：
    - 起跳方向（前进 / 后退）
    - 刃的使用与躯干倾斜方向
    - Toe-pick 动作（脚尖插冰）在脚踝轨迹上的表现
    - 双膝弯曲与伸直的时序关系等

- **最近跳跃列表（英文显示）**
  - 信息栏中按时间倒序显示最近 **3 次跳跃** 的英文描述，例如：
    - `t=123456ms 3Lz height=35.2cm airtime=620ms rotation=1040.0deg (2.89 turns)`
  - 便于教练或运动员快速回看最近的尝试。

---

### 实现细节概览

- **技术栈**
  - Kotlin 编写的 Android 应用。
  - 使用官方 **MediaPipe Tasks for Android – Vision / Pose Landmarker** 依赖。
  - 使用 **CameraX** 的 `Preview` + `ImageAnalysis` 工作流。

- **核心类（简要）**
  - `SkateActivity`
    - 管理摄像头预览与叠加视图。
    - 通过 `PoseLandmarkerHelper` 获取姿态检测结果。
    - 维护运行状态、FPS 平滑、跳跃检测状态，以及信息栏文字。
  - `PoseLandmarkerHelper`
    - 对 MediaPipe Pose Landmarker 的封装。
    - 配置为 `LIVE_STREAM` 模式，输入格式为 RGBA。
  - `OverlayView`
    - 自定义绘制视图，根据关键点连线绘制骨架和关节。

- **跳跃检测流程**
  1. 在未腾空时，平滑更新“地面脚踝高度”估计值。
  2. 当脚踝高于地面一定阈值时，判定为开始腾空，记录起跳时刻和髋部角度。
  3. 腾空过程中，持续更新最低脚踝高度，并累计髋部旋转角度。
  4. 回到地面时：
     - 计算腾空时间（毫秒）
     - 估算跳跃高度（厘米）与旋转圈数
     - 应用高度/时间/旋转/位移等过滤条件
     - 调用 `classifyJumpType` 推断跳跃类型
     - 生成 `JumpEvent`，加入跳跃事件列表

---

### 目前局限与后续改进方向

- **跳跃识别仍然比较粗糙，需要进一步优化：**
  - 高度与腾空时间阈值是手工调整的，未针对不同水平、不同身高运动员进行自适应。
  - 起跳刃与入刃方向的判定较为近似，某些复杂动作可能会被误分类。
  - 身高转换采用固定值，跳跃高度的绝对数值存在系统误差。

- **非裁判级应用**
  - 本应用仅用于技术验证、训练与教学辅助。
  - 不应作为任何正式比赛的评分或裁判依据。

潜在的改进方向：

- 基于标注数据，使用机器学习或统计方法学习跳跃模式与阈值。
- 为每位运动员进行身高与相机视角的标定，提升高度估计精度。
- 在跳跃识别稳定之后，重新引入更轻量的旋转（旋转步/旋转姿势）检测算法。

---

### 构建与运行

1. 使用 **Android Studio** 打开项目（建议 Giraffe 或更新版本）。
2. 确保已安装：
   - Android SDK 和构建工具
   - 可用的 Android 设备或模拟器（推荐真机，带摄像头）
3. 同步 Gradle 并编译项目。
4. 运行 `app` 模块到 Android 设备上。
5. 授予摄像头权限，点击底部的 **录制按钮** 开始捕捉；停止后，应用会自动进行跳跃分析并展示结果页面。
