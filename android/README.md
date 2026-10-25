# 万物收纳 · Android

Jetpack Compose 客户端，功能、数据格式和 [iOS 版](../ios) 一致，两者通过同一个[服务端](../server)同步。

```
app/src/main/java/com/wanwu/nook/
├── App.kt                 Application、依赖容器、导航（每个标签页一个返回栈）
├── data/                  记录模型（与 iOS 同格式）、家具目录、HomeStore（本地存储 + 同步记账）、照片文件
├── net/                   服务端 API、同步引擎、Home Assistant、Keystore 加密存令牌
├── image/                 抠图（ML Kit → 内置 U²-Net-p → 服务器）、取色、SVG 解析
└── ui/                    户型/房间画布、家具收纳、物品、设备、设置、首次引导
```

- `app/src/main/assets/u2netp.onnx` 是 U²-Net-p 模型（4.5 MB，Apache-2.0，来自 [rembg](https://github.com/danielgatis/rembg) 的发布页）。
- `data/SymbolLibrary.kt` 由 `ios/Nook/Models/SymbolLibrary.swift` 生成；`ui/components/SymbolIcons.kt` 把 SF Symbols 名称映射到 Material 图标。

运行和测试见[根目录 README](../README.md)。
