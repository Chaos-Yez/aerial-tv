# Aerial 极米适配版（1.48-xgimi）

极米投影仪定制版，**基于上游 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) v1.48** 叠加极米适配。

## 这个版本做了什么

在官方 v1.48 之上，针对极米投影仪做了这些增强（上游自带功能全部保留）：

**本次（相对 1.47-xgimi）优化：** 跟进上游 v1.48（控制中心缩小至 300dp、Motion 动画、Effects 等），并把控制中心默认焦点改为「随机一张」。

### 控制中心

- **Wi-Fi 名称**能正常显示当前连接的名称
- **亮度条**能正常改变投影仪亮度
- **信号源**可正常切换，包括主屏幕的磁贴
- **无线按钮**正常打开
- **设置按钮**正常打开
- 新增一个**随机一张**的按钮：点一下从必应归档取一张老图设为壁纸，只留一张、不占存储

### 个性化

- 新增**随机一张**的卡片：点一下从必应归档取一张老图设为壁纸，只留一张、不占存储
- 新增**第一排显示大字**，可在个性化里开启
- 屏保选择「系统内置」时打开极米内置屏保
- **手机端免配对码**，扫码即连（个人需要）

### 安装

- 包名 `app.orchard.tv`，versionName `1.48-xgimi`，versionCode `69`
- 覆盖安装需与已装版本同签名；本 APK 用本机 debug 签名，若电视已装官方版请先卸载再装
- 极米桌面建议 `pm disable-user com.xgimi.home`，让 Aerial 成为唯一 HOME

## 构建

```bash
# local.properties
sdk.dir=C\:\\Users\\Administrator\\android-sdk

# 构建
JAVA_HOME=C:/Users/Administrator/jdk-17.0.20.1+1 \
  gradle --offline assembleStandardRelease
```

调试签名 SHA-256 `5cae…7964`，与电视上已装版本一致，覆盖安装不会清除数据。
