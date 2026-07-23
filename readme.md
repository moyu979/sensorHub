# sensorHub — 环境监控盒子

本项目的目标是实现一个简单的环境监控盒子，利用旧手机和一个 RP2040 尽可能的收集环境数据。

## 项目结构

```
sensorHub/
├── readme.md              # 本文件
├── 3D/                    # 外壳 3D 模型文件
├── 材料/                  # 元器件购买推荐（非广告）
├── rp2040/                # RP2040 微控制器固件
│   ├── readme.md
│   └── main.py            # 伪数据模式（USB CDC 发送 JSON）
└── phone/
    └── android/           # Android 端 App（Kotlin + Jetpack Compose）
        └── app/src/main/java/com/example/rp2040monitor/
            ├── data/                  # ── 数据采集模块 ──
            │   ├── model/
            │   │   └── SensorData.kt  # 传感器数据模型
            │   ├── source/
            │   │   ├── DataSource.kt  # 数据源接口（所有数据源统一实现）
            │   │   └── FakeDataSource.kt  # 模拟数据源（开发/测试用）
            │   │                     # 后续新增: serial/, video/, audio/
            │   └── storage/
            │       ├── DataBuffer.kt  # 内存环形缓冲区（供图表快速读取）
            │       └── DataLogger.kt  # CSV 文件落盘
            ├── display/              # ── 展示模块 ──
            │   ├── local/            #   本地展示（Jetpack Compose）
            │   │   ├── MainActivity.kt
            │   │   ├── components/
            │   │   │   ├── FieldSelector.kt
            │   │   │   └── ScrollingChart.kt
            │   │   └── screen/
            │   │       └── MonitorScreen.kt
            │   └── web/              #   网页展示（嵌入式 HTTP 服务）
            │       ├── WebServer.kt  # NanoHTTPD 服务器
            │       └── api/
            │           └── SensorApiHandler.kt  # REST API 处理器
            └── theme/                # ── 共享主题 ──
                ├── Color.kt
                ├── Theme.kt
                └── Type.kt
```

> `assets/web/` 目录下存放网页静态文件（index.html, app.js, styles.css）。

## 计划实现的传感器

### 手机端
视频传感器，声音记录，指定电台的守听，加速度，磁场，Wi-Fi 信号，GPS 等

### RP2040 端
空气质量（CO₂、O₂、TVOC、CH₂O），PM2.5/PM10，温湿度，气压，烟雾等

## 展示方式

### 本地 App（Compose UI）
- 字段下拉选择器
- 滚动折线图（1 分钟窗口，从右向左）
- 实时数据文本面板

### 网页版（嵌入式 Web Server）
手机启动后自动在 `http://<手机IP>:8080` 提供：
- **REST API**：
  - `GET /api/status` — 服务器状态
  - `GET /api/fields` — 所有可用字段
  - `GET /api/current` — 最新一条数据
  - `GET /api/history/<field>?count=N` — 某字段历史数据
- **Web 面板**：实时折线图 + 数据表格，自动刷新

## 数据流

```
数据源 (DataSource)
  └─→ FakeDataSource (模拟正弦波)
  └─→ 未来: SerialDataSource / VideoSource / AudioSource
        ↓
    DataBuffer (内存, 60点环形)
        ↓
    ┌──→ DataLogger (CSV 落盘)
    └──→ 本地 UI (Compose Canvas 图表)
    └──→ WebServer (REST API + 网页)
```

## 可能可以应用的
一个基于 LLM 的出行提醒方案