package com.qytong.rfprog;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.util.ArrayList;
import java.util.List;

/**
 * 泉盛 UV-K5 / K6 写频协议（依据 k5web / Quansheng 官方协议实现）
 *
 * 串口: 38400 8N1
 * 包格式: AB CD [len_lo len_hi] [ XOR(data + CRC16-XMODEM(data)) ] DC BA
 * XOR 密钥: 16 6C 14 E6 2E 91 0D 40 21 35 D5 40 13 03 E9 80
 *
 * 命令:
 *   0x0514  EEPROM 初始化/读版本   → 0x0515
 *   0x051B  EEPROM 读 (≤0x80字节)  → 0x051C
 *   0x051D  EEPROM 写 (≤0x80字节)  → 0x051E
 *   0x05DD  重启
 *
 * 频道: EEPROM 0x0000 起，200 个 × 32 字节
 *   0x00 u32 RX频率(10Hz单位)  0x04 u32 TX频率
 *   0x08 u16 RX DCS(八进制)    0x0A u16 RX CTCSS(0.1Hz)
 *   0x0C u16 TX DCS            0x0E u16 TX CTCSS
 *   0x10 u16 步进索引          0x12 u8 调制(0=FM,1=AM,2=USB,3=LSB)
 *   0x13 u8 带宽(0=宽,1=窄)    0x14 u8 功率(0/1)
 *   0x15 u8 扰频               0x16..0x1F 频道名(10字节 ASCII)
 */
public class Uvk5Radio {

    public static final int BAUD = 38400;
    public static final int EEPROM_SIZE = 0x2000;     // 标准 UV-K5 为 8KB
    public static final int CH_COUNT = 200;
    public static final int CH_SIZE = 32;
    public static final int CH_BASE = 0x0000;

    private static final byte[] XOR_KEY = {
            0x16, 0x6C, 0x14, (byte) 0xE6, 0x2E, (byte) 0x91, 0x0D, 0x40,
            0x21, 0x35, (byte) 0xD5, 0x40, 0x13, 0x03, (byte) 0xE9, (byte) 0x80
    };

    private final UsbSerialPort port;
    private final Listener listener;
    private int timeoutMs = 1200;

    public interface Listener {
        void onLog(String msg);
        void onProgress(int current, int total, String msg);
    }

    public Uvk5Radio(UsbSerialPort port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    // ------------------------------------------------------------------
    // 底层：包封 / 解包
    // ------------------------------------------------------------------

    public static int crc16xmodem(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
            crc &= 0xFFFF;
        }
        return crc;
    }

    public static void xorInPlace(byte[] data, int off, int len) {
        for (int i = 0; i < len; i++) {
            data[off + i] ^= XOR_KEY[i % XOR_KEY.length];
        }
    }

    /** 组包: AB CD len data+CRC(异或) DC BA */
    public static byte[] packetize(byte[] payload) {
        int n = payload.length;
        byte[] body = new byte[n + 2];
        System.arraycopy(payload, 0, body, 0, n);
        int crc = crc16xmodem(payload, 0, n);
        body[n] = (byte) (crc & 0xFF);
        body[n + 1] = (byte) ((crc >> 8) & 0xFF);
        xorInPlace(body, 0, body.length);

        byte[] out = new byte[body.length + 6];
        out[0] = (byte) 0xAB;
        out[1] = (byte) 0xCD;
        out[2] = (byte) (n & 0xFF);
        out[3] = (byte) ((n >> 8) & 0xFF);
        System.arraycopy(body, 0, out, 4, body.length);
        out[out.length - 2] = (byte) 0xDC;
        out[out.length - 1] = (byte) 0xBA;
        return out;
    }

    private void writeRaw(byte[] data) throws Exception {
        port.write(data, 1500);
        log("TX: " + HexUtil.bytesToHex(data, 0, Math.min(data.length, 24)) + (data.length > 24 ? "..." : ""));
    }

    /**
     * 读一个完整包（自动跳过前导垃圾、校验 DC BA 与长度）
     * @return payload（已异或还原，含末尾2字节CRC）
     */
    private byte[] readPacket(int expectFirstByte, int totalTimeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        byte[] buf = new byte[4096];
        int len = 0;
        while (System.currentTimeMillis() - start < totalTimeoutMs) {
            byte[] one = new byte[1];
            int r = port.read(one, 100);
            if (r <= 0) continue;
            if (len < buf.length) buf[len++] = one[0];
            // 找包
            int startIdx = -1;
            for (int i = 0; i + 1 < len; i++) {
                if ((buf[i] & 0xFF) == 0xAB && (buf[i + 1] & 0xFF) == 0xCD) {
                    startIdx = i;
                    break;
                }
            }
            if (startIdx < 0) {
                // 丢弃无包头数据
                if (len > 1) {
                    len = 1;
                    buf[0] = buf[len - 1];
                }
                continue;
            }
            if (startIdx > 0) {
                System.arraycopy(buf, startIdx, buf, 0, len - startIdx);
                len -= startIdx;
            }
            if (len < 4) continue;
            int plen = (buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8);
            int total = plen + 8;
            if (len < total) continue;
            if ((buf[total - 2] & 0xFF) != 0xDC || (buf[total - 1] & 0xFF) != 0xBA) {
                // 包头不可靠，丢弃1字节重找
                System.arraycopy(buf, 1, buf, 0, len - 1);
                len--;
                continue;
            }
            byte[] payload = new byte[plen + 2];
            System.arraycopy(buf, 4, payload, 0, plen + 2);
            xorInPlace(payload, 0, payload.length);
            // 校验 CRC
            int crc = crc16xmodem(payload, 0, plen);
            int got = (payload[plen] & 0xFF) | ((payload[plen + 1] & 0xFF) << 8);
            if (crc != got) {
                log("CRC 错误，丢弃包");
                System.arraycopy(buf, total, buf, 0, len - total);
                len -= total;
                continue;
            }
            if (plen > 0 && expectFirstByte >= 0 && (payload[0] & 0xFF) != expectFirstByte) {
                log("非期望包 0x" + Integer.toHexString(payload[0] & 0xFF));
                System.arraycopy(buf, total, buf, 0, len - total);
                len -= total;
                continue;
            }
            return payload;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    /** 初始化并读固件版本 */
    public String init() throws Exception {
        writeRaw(packetize(new byte[]{0x14, 0x05, 0x04, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        byte[] resp = readPacket(0x15, 2000);
        if (resp == null) throw new Exception("UV-K5 无响应（请确认已开机、写频线为 38400）");
        // resp: 15 05 len lo offset... 版本字符串在偏移4起
        StringBuilder sb = new StringBuilder();
        for (int i = 4; i < resp.length - 2 && sb.length() < 16; i++) {
            int b = resp[i] & 0xFF;
            if (b == 0) break;
            if (b >= 32 && b < 127) sb.append((char) b);
        }
        log("UV-K5 版本: " + sb);
        return sb.toString();
    }

    /** 读 EEPROM（size ≤ 0x80） */
    public byte[] readEeprom(int addr, int size) throws Exception {
        byte[] cmd = new byte[]{0x1B, 0x05, 0x08, 0x00,
                (byte) (addr & 0xFF), (byte) ((addr >> 8) & 0xFF),
                (byte) size, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        for (int retry = 0; retry < 3; retry++) {
            writeRaw(packetize(cmd));
            byte[] resp = readPacket(0x1C, timeoutMs);
            if (resp == null) continue;
            if (resp.length < 8 + size) continue;
            if ((resp[6] & 0xFF) != size) continue;
            byte[] out = new byte[size];
            System.arraycopy(resp, 8, out, 0, size);
            return out;
        }
        throw new Exception("读 EEPROM 0x" + Integer.toHexString(addr) + " 失败");
    }

    /** 写 EEPROM（size ≤ 0x80） */
    public void writeEeprom(int addr, byte[] data, int size) throws Exception {
        byte[] cmd = new byte[12 + size];
        cmd[0] = 0x1D;
        cmd[1] = 0x05;
        cmd[2] = (byte) ((size + 8) & 0xFF);
        cmd[3] = (byte) (((size + 8) >> 8) & 0xFF);
        cmd[4] = (byte) (addr & 0xFF);
        cmd[5] = (byte) ((addr >> 8) & 0xFF);
        cmd[6] = (byte) size;
        cmd[7] = 0x01;
        cmd[8] = (byte) 0xFF;
        cmd[9] = (byte) 0xFF;
        cmd[10] = (byte) 0xFF;
        cmd[11] = (byte) 0xFF;
        System.arraycopy(data, 0, cmd, 12, size);
        for (int retry = 0; retry < 3; retry++) {
            writeRaw(packetize(cmd));
            byte[] resp = readPacket(0x1E, timeoutMs);
            if (resp != null) return;
        }
        throw new Exception("写 EEPROM 0x" + Integer.toHexString(addr) + " 失败");
    }

    public void reboot() throws Exception {
        writeRaw(packetize(new byte[]{(byte) 0xDD, 0x05}));
    }

    // ------------------------------------------------------------------
    // 全量读 / 写
    // ------------------------------------------------------------------

    /** 读整个 8KB EEPROM（频道 + 设置） */
    public byte[] readAll() throws Exception {
        byte[] image = new byte[EEPROM_SIZE];
        int total = EEPROM_SIZE / 0x80;
        for (int i = 0; i < EEPROM_SIZE; i += 0x80) {
            byte[] chunk = readEeprom(i, 0x80);
            System.arraycopy(chunk, 0, image, i, 0x80);
            int done = i / 0x80 + 1;
            if (listener != null) listener.onProgress(done, total, "0x" + Integer.toHexString(i).toUpperCase());
            if (done % 16 == 0) log("读取进度 " + done + "/" + total);
        }
        if (listener != null) listener.onProgress(total, total, "读取完成");
        return image;
    }

    /** 写回整个 8KB EEPROM */
    public void writeAll(byte[] image) throws Exception {
        int total = EEPROM_SIZE / 0x40;
        for (int i = 0; i < EEPROM_SIZE; i += 0x40) {
            byte[] chunk = new byte[0x40];
            System.arraycopy(image, i, chunk, 0, 0x40);
            writeEeprom(i, chunk, 0x40);
            int done = i / 0x40 + 1;
            if (listener != null) listener.onProgress(done, total, "0x" + Integer.toHexString(i).toUpperCase());
            if (done % 16 == 0) log("写入进度 " + done + "/" + total);
        }
        if (listener != null) listener.onProgress(total, total, "写入完成");
    }

    // ------------------------------------------------------------------
    // 频道编解码
    // ------------------------------------------------------------------

    public static long getU32(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    public static void putU32(byte[] d, int o, long v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >> 8) & 0xFF);
        d[o + 2] = (byte) ((v >> 16) & 0xFF);
        d[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    public static int getU16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    public static void putU16(byte[] d, int o, int v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /** 解析 UV-K5 32 字节频道记录到统一模型 */
    public static UnifiedChannel decodeChannel(int idx, byte[] rec) {
        UnifiedChannel c = new UnifiedChannel();
        c.index = idx;
        long rx = getU32(rec, 0);
        long tx = getU32(rec, 4);
        c.rxHz = rx * 10L;
        c.txHz = tx * 10L;
        if (c.rxHz == 0xFFFFFFFFL * 10L) c.rxHz = 0;
        if (c.txHz == 0xFFFFFFFFL * 10L) c.txHz = 0;

        c.rxTone = decodeK5Tone(getU16(rec, 0x08), getU16(rec, 0x0A));
        c.txTone = decodeK5Tone(getU16(rec, 0x0C), getU16(rec, 0x0E));

        c.step = getU16(rec, 0x10);
        c.modulation = rec[0x12] & 0xFF;
        c.narrow = (rec[0x13] & 0xFF) == 1;
        c.power = rec[0x14] & 0xFF;
        c.scramble = (rec[0x15] & 0xFF) != 0;

        StringBuilder name = new StringBuilder();
        for (int i = 0x16; i < 0x20; i++) {
            int b = rec[i] & 0xFF;
            if (b == 0 || b == 0xFF) break;
            if (b >= 32 && b < 127) name.append((char) b);
        }
        c.name = name.toString().trim();
        c.rawK5 = rec.clone();
        return c;
    }

    /** 统一模型编码为 UV-K5 32 字节记录（保留 rawK5 中的未知字节） */
    public static byte[] encodeChannel(UnifiedChannel c) {
        byte[] rec = c.rawK5 != null ? c.rawK5.clone() : new byte[CH_SIZE];
        putU32(rec, 0, c.rxHz / 10L);
        putU32(rec, 4, c.txHz / 10L);

        int[] rxt = encodeK5Tone(c.rxTone);
        putU16(rec, 0x08, rxt[0]);
        putU16(rec, 0x0A, rxt[1]);
        int[] txt = encodeK5Tone(c.txTone);
        putU16(rec, 0x0C, txt[0]);
        putU16(rec, 0x0E, txt[1]);

        putU16(rec, 0x10, c.step);
        rec[0x12] = (byte) c.modulation;
        rec[0x13] = (byte) (c.narrow ? 1 : 0);
        rec[0x14] = (byte) c.power;
        rec[0x15] = (byte) (c.scramble ? 1 : 0);

        byte[] nm = new byte[10];
        java.util.Arrays.fill(nm, (byte) 0);
        byte[] src = c.name == null ? new byte[0] : c.name.getBytes();
        for (int i = 0; i < 10 && i < src.length; i++) nm[i] = src[i];
        System.arraycopy(nm, 0, rec, 0x16, 10);
        return rec;
    }

    /**
     * UV-K5 亚音解码：
     *  cts != 0 → CTCSS（0.1Hz 单位）
     *  dcs != 0 → DCS（八进制 BCD，如 0x0023 = D023）
     */
    public static Tone decodeK5Tone(int dcs, int cts) {
        if (cts != 0 && cts != 0xFFFF) {
            return Tone.ctcss(cts / 10.0);
        }
        if (dcs != 0 && dcs != 0xFFFF) {
            int code = ((dcs >> 8) & 0xF) * 100 + ((dcs >> 4) & 0xF) * 10 + (dcs & 0xF);
            return Tone.dcs(code, false);
        }
        return Tone.off();
    }

    /** @return int[]{dcs, cts} */
    public static int[] encodeK5Tone(Tone t) {
        if (t == null || t.type == Tone.OFF) return new int[]{0, 0};
        if (t.type == Tone.CTCSS) {
            return new int[]{0, (int) Math.round(t.hz * 10)};
        }
        // DCS: 八进制 BCD
        int code = t.dcsCode;
        int d1 = code / 100, d2 = (code / 10) % 10, d3 = code % 10;
        return new int[]{((d1 << 8) | (d2 << 4) | d3), 0};
    }

    /** 从 EEPROM 镜像解析全部频道 */
    public static List<UnifiedChannel> parseChannels(byte[] image) {
        List<UnifiedChannel> list = new ArrayList<UnifiedChannel>();
        for (int i = 0; i < CH_COUNT; i++) {
            int off = CH_BASE + i * CH_SIZE;
            if (off + CH_SIZE > image.length) break;
            byte[] rec = new byte[CH_SIZE];
            System.arraycopy(image, off, rec, 0, CH_SIZE);
            list.add(decodeChannel(i, rec));
        }
        return list;
    }

    /** 把统一频道写回 EEPROM 镜像 */
    public static void applyChannels(byte[] image, List<UnifiedChannel> channels) {
        for (int i = 0; i < CH_COUNT && i < channels.size(); i++) {
            int off = CH_BASE + i * CH_SIZE;
            if (off + CH_SIZE > image.length) break;
            byte[] rec = encodeChannel(channels.get(i));
            System.arraycopy(rec, 0, image, off, CH_SIZE);
        }
    }
}
