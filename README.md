# 简单相机 (WatermarkCameraApp)

一个基于 Android 原生 Kotlin + CameraX 的现场拍照应用。

拍照后会在图片中写入水印信息：
- 经度
- 纬度
- 地址
- 时间
- 现场拍照标识

## 功能特性

- CameraX 相机预览与拍照
- 定位成功后才允许拍照（定位中/定位失败不可拍）
- 支持 GPS 与 Wi-Fi 网络定位兜底
- 经纬度反查地址（Geocoder）
- 图片水印绘制并保存到系统相册
- Android 多版本存储兼容（含 Android 10+ 分区存储）

## 环境要求

- Android Studio Iguana 或更高版本
- JDK 17
- Android SDK 34

## 运行方式

1. 用 Android Studio 打开项目目录：
   - `/Users/ppyang/Documents/MyProject/WatermarkCameraApp`
2. 等待 Gradle 同步完成
3. 连接真机运行（定位能力在真机上更稳定）
4. 首次启动按提示授予权限：
   - 相机权限
   - 定位权限（建议精确定位）
   - 存储权限（Android 9 及以下）

## 构建命令

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

## 版本信息

当前版本：
- `versionName`: `1.2`
- `versionCode`: `2`

## 注意事项

- 若 Wi-Fi 定位失败，请检查系统设置中的位置服务、Wi-Fi 扫描和定位精度选项。
- 若长时间无法定位，建议到室外重试以加速 GPS 首次定位。

## 仓库

- Remote: `git@github.com:hlbt/WatermarkCamerApp.git`
