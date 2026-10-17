# 写频助手 (RFProg)

安卓 OTG 串口写频工具，支持 **泉益通 KT-10R**（GT-12 / W818 平台）与 **泉盛 UV-K5 / K6** 两种机型。

一套频道数据可在两种机型之间自由切换写入，**更换机型无需重新输入频率**。

## 功能

### 通用
- OTG USB 串口连接（CH340 / FTDI / PL2303 / CP210x / CDC-ACM）
- 读频 / 写频（整机 EEPROM 镜像）
- 统一频道模型：RX/TX 频率、RX/TX 亚音、频宽、功率、信道名
- 49 个 CTCSS + 104 个 DCS（含正/反相）下拉选择
- 32 字节原始数据 HEX 编辑，与结构化字段实时同步
- 整机镜像十六进制查看（分页 + 地址跳转）
- 镜像保存 / 加载（.bin），支持备份与多机复制
- 双机型数据互通：读 KT-10R → 切 UV-K5 → 直接写

### 泉益通 KT-10R（9600 8N1）
- 握手协议 `PjOJH#D` / `P311` 身份识别
- EEPROM 25,600 字节，794 个 32 字节块（跳过 0x1F40-0x2000 保留区）
- 频率 BCD 编码、CTCSS/DCS BCD 编码
- 频宽（宽带/窄带）、禁止接收、功率参数、标志位
- A/B 双半区（PC 版 "A Channal"/"B Channal"）

### 泉盛 UV-K5 / K6（38400 8N1）
- 标准 Quansheng 协议：`AB CD` 包头 / `DC BA` 包尾、CRC16-XMODEM、16 字节 XOR 混淆
- 命令：0x0514 初始化 / 0x051B 读 / 0x051D 写 / 0x05DD 重启
- 200 个频道 × 32 字节
- UV-K5 专属参数（仅选择 UV-K5 时显示）：
  - FM / AM / USB / LSB 调制
  - 扰频 (Scramble)
  - 10 字符信道名
  - 18-1300MHz 宽频接收机型支持

## 安装

从 [Releases](https://github.com/BG5VYX/rfprog/releases) 下载 APK 安装（Android 5.0+，需支持 OTG）。

## 使用

1. 选择机型（KT-10R / UV-K5）
2. OTG 连接写频线，**打开电台电源**
3. 点【连接】（首次授权 USB）
4. 点【读频】读取全部频道
5. 点击频道编辑参数，或【打开镜像】载入之前备份
6. 点【写频】写入电台

> 换机型：直接切换机型下拉框，频道数据保留；重新连接后即可写入另一台机器。
> 写频会覆盖电台数据，请先读频备份。

## 构建

无 Gradle，使用 aapt2 + ecj + d8 + apksigner 手动构建：

```bash
# 依赖：JDK 17+、Android SDK platform-34 + build-tools 34.0.0
# 修改 build.sh 顶部的 APP_HOME / SDK / JAVA_HOME
bash build.sh
```

## 协议文档

见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 致谢

- UV-K5 协议参考：[silenty4ng/k5web](https://github.com/silenty4ng/k5web)
- USB 串口库：[mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (Apache-2.0)
- KT-10R 协议由本项目的逆向分析得出

## 更新日志

### v2.0
- 新增 泉盛 UV-K5 / K6 支持（38400 波特率、Quansheng 协议）
- 新增机型切换与统一频道模型（跨机型复用频率数据）
- 新增 UV-K5 专属参数（调制/扰频/信道名）
- 全新蓝色主题，更名为「写频助手」
- 修复 KT-10R 编辑器的 A/B 半区处理

### v1.0
- 泉益通 KT-10R 读频/写频/频道编辑

## 免责声明

仅供个人无线电设备写频使用。请遵守所在国家/地区的无线电管理法规，在合法频段与功率范围内使用。
