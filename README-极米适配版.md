# Aerial 极米适配版（1.47-xgimi）

基于 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) 上游 **v1.47**（commit `7b005c6b`），
在原生功能之上叠加了极米投影仪（GMUI）的硬件/固件适配与一项新壁纸功能。

- versionCode `68`，versionName `1.47-xgimi`
- 面向设备：极米 `pig_chivalrous`（Android 9 / GMUI 9），同类 GMUI 机型应通用
- 相对上游 v1.47：**新增 6 个文件、修改 32 个文件、+1232 / −228 行**

---

## 一、新增功能

### 1. 自建「应用信息」页（App Info）
固件没有 Android 原生 App Info 页时（如小米 HyperOS 的 MiTV-MFTR0），第三方应用请求该页会落到
「错误」。Aerial 用 `AppInfoActivity` 顶上，通过 `AndroidManifest` 里 4 个按需启用的 activity-alias
接管对应设置页的 intent。

可查看任意应用的：版本、已授予权限、特殊权限状态；可执行：打开、卸载、强制停止、清除数据。

### 2. 通过电视自带 adbd 管理其他应用
`AdbShell` / `AppDetails` / `AppInfoSheet` / `AdbAccessDialog`：用电视自身的 adbd 读写其他应用的
权限与特殊权限（所有文件访问、修改系统设置、安装未知应用）。

**默认关闭**，需在「个性化 → 壁纸」页阅读风险说明并主动开启；关闭时会删除已保存的 adb 密钥。

### 3. 极米 DLP 光机亮度控制
极米的真实亮度是光机（DLP）等级，系统 `screen_brightness` 并不跟随它（上游 issue #241）。
`SystemProbe` 通过反射调用私有 HAL `com.xgimi.gmpf.api.DisplayManager` 读写 `DlpLumensLevel`（1–10 级），
控制中心直接步进光机等级，并在写入后轮询真实值——电视拒写时如实显示，不假装成功。

### 4. 接入极米自有屏保场景
屏保选择「系统」时，启动极米自己的氛围场景（`com.xgimi.atmosphere`），而非 Android 的 dream。
同时识别并尊重固件自带屏保（`firmwareDream` / `xgimiDream`），避免 Aerial 抢占后无法还原。

### 5. 极米信号源 / 输入切换
没有应用响应极米信号源切换的 passthrough intent，改为跳转极米设置服务的对应页面
（`Settings://com.xgimi.settings.signalSource/hdmi_misckey`）。

### 6. 首页应用名三种显示模式
`AerialSettings.NameMode`：

| 模式 | 效果 |
|---|---|
| `Focused` | 只显示当前焦点应用的名字（tvOS 风格） |
| `All` | 每个磁贴都显示名字，顶部玻璃板上的也显示 |
| `HeroTitle` | 网格上方一个大字标题，替代磁贴下的标签（旧 CleanYourTV 风格） |

老版本布尔型的「显示全部名字」会自动迁移到 `All`。

### 7. 高对比焦点框
磁贴上紧贴一圈细白环（`FocusRing` 思路），聚焦时由内向外展开，在浅色壁纸上也能看清。

### 8. 控制中心使用实时壁纸背景
控制中心打开时背景是**实时**壁纸，而非 Home 的静态快照——这样点「随机一张」或换任意图片后
背景立刻跟着变（与「个性化」面板行为一致），不再有最长 30 秒的重新截图等待。

### 9. 手机端免配对码常驻
手机页不再需要扫二维码、不再有随机 token：只要 Aerial 在跑，`http://<电视IP>:8790` 一直可访问。
上传完成后也不再有超时关闭。

### 10. 随机一张历史必应壁纸
「个性化 → 壁纸」和手机网页端新增「随机一张」卡片：点一下从必应历史图库随机取一张老照片设为桌面。

- 三级来源兜底：中文源 → 英文源 → 必应官方接口
- 排除最近 8 天，优先中文图说
- 每次点击取新图，**取新即删旧，目录始终只留一张**（不占储存）
- 独立 id 与独立文件夹，不进照片屏保池，不影响原生「每日一图」
- 手机端支持缩略图缓存击穿与抓取完成轮询
- 该项已单独提交上游 PR #512

### 11. WiFi SSID 兜底读取
Android 8.1+ 的实时 SSID 被位置开关挡住（电视上通常关闭），改为从已保存网络列表读当前连接
的 SSID。

### 12. 盒式模糊加速
`Images.kt` 的模糊改为滑窗盒式实现，耗时与半径无关（原实现半径越大越慢）。

### 13. 单元测试
新增 `XgimiSettingsTest`（JVM 单测），`build.gradle.kts` 增加 `junit` 与 `org.json` 测试依赖。

---

## 二、改动的功能

| 项 | 原生 v1.47 | 本版 |
|---|---|---|
| 版本标识 | `1.47` / vc55 | `1.47-xgimi` / vc68 |
| 屏保「系统」选项 | 只启动固件 dream | 扩展为极米场景 / 小米应用 / dream，按固件能力选择 |
| 亮度调节 | 系统 `screen_brightness` | 极米机上改走光机等级（1–10），其余机型不变 |
| 手机页访问方式 | 扫码 + token，壁纸页打开时才监听 | 免配对码，应用运行期间常驻监听 |
| 首页名字显示 | 单一模式 | 三模式可选（见上） |
| 诊断输出 | `names=` | `nameMode=`（跟随新设置） |

---

## 三、相比原生的差异（需注意）

上游自带的功能（「每日一图」、「我的照片·每天换」等）均完整保留，本版只做叠加。

---

## 四、构建

```bash
# local.properties
sdk.dir=C\:\\Users\\Administrator\\android-sdk

# 构建
JAVA_HOME=C:/Users/Administrator/jdk-17.0.20.1+1 \
  gradle --offline assembleStandardRelease
```

调试签名 SHA-256 `5cae…7964`，与电视上已装版本一致，覆盖安装不会清除数据。
