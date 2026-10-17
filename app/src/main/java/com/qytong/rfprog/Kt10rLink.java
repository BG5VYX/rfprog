package com.qytong.rfprog;

import com.hoho.android.usbserial.driver.UsbSerialPort;

/**
 * KT-10R / GT-12 (W818) 写频协议实现
 *
 * 串口参数: 9600 8N1
 *
 * 握手:
 *   主机 -> 50 6A 4F 4A 48 23 44   (7字节魔数 "PjOJH#D")
 *   电台 -> 06                     (ACK)
 *   主机 -> 02
 *   电台 -> 50 33 31 31 .. FF      (8字节 "P311" 身份帧)
 *   主机 -> 06
 *   电台 -> 06                     (握手完成)
 *
 * 读:
 *   主机 -> 52 1B 40 20            (信息同步命令，读参数块 0x1B40)
 *   电台 -> 57 .. .. 20 [32B] [Σ]  (37字节数据帧)
 *   主机 -> 52 [addr_H] [addr_L] 20 (读 32 字节)
 *   电台 -> 57 .. .. 20 [32B] [Σ]
 *   ... 直到地址 0x6400
 *   主机 -> 45                     ('E' 结束)
 *
 * 写:
 *   主机 -> 52 1B 40 20  (同步)
 *   主机 -> 57 [addr_H] [addr_L] 20 [32B] [Σ]
 *   电台 -> 06
 *   ... 逐块写完后主机 -> 45
 */
public class Kt10rLink {

    public static final int BAUD = 9600;

    private final UsbSerialPort port;

    /** 日志与状态回调（运行在 IO 线程，由调用方处理） */
    public interface Listener {
        void onLog(String msg);
        void onProgress(int current, int total, String msg);
    }

    private final Listener listener;
    private int frameTimeoutMs = 500;
    private int handshakeTimeoutMs = 1200;

    public Kt10rLink(UsbSerialPort port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    // ------------------------------------------------------------------
    // 底层收发
    // ------------------------------------------------------------------

    public void write(byte[] data) throws Exception {
        port.write(data, 1000);
        log("TX: " + HexUtil.bytesToHex(data));
    }

    /** 读满 n 字节；每字节等待 perByteMs 毫秒，超时返回 null */
    public byte[] readExact(int n, int perByteMs) throws Exception {
        byte[] out = new byte[n];
        int got = 0;
        while (got < n) {
            byte[] buf = new byte[1];
            int r = port.read(buf, perByteMs);
            if (r <= 0) {
                log("RX timeout after " + got + "/" + n + " bytes");
                return null;
            }
            out[got++] = buf[0];
        }
        log("RX: " + HexUtil.bytesToHex(out));
        return out;
    }

    // ------------------------------------------------------------------
    // 握手
    // ------------------------------------------------------------------

    /** @return 电台身份帧文本（如 "P311.."），失败抛异常 */
    public String handshake() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            log("握手 第" + attempt + "次");
            write(new byte[]{0x50, 0x6A, 0x4F, 0x4A, 0x48, 0x23, 0x44});
            byte[] ack = readExact(1, handshakeTimeoutMs);
            if (ack == null || (ack[0] & 0xFF) != 0x06) {
                log("等待ACK失败");
                continue;
            }
            write(new byte[]{0x02});
            byte[] id = readExact(8, handshakeTimeoutMs);
            if (id == null) continue;
            write(new byte[]{0x06});
            byte[] fin = readExact(1, handshakeTimeoutMs);
            if (fin == null || (fin[0] & 0xFF) != 0x06) {
                log("等待最终ACK失败");
                continue;
            }
            return HexUtil.bytesToHex(id, 0, 8);
        }
        throw new Exception("握手失败，请确认电台已开机且连接正常");
    }

    // ------------------------------------------------------------------
    // 读频
    // ------------------------------------------------------------------

    /** 读一个 32 字节块（发送 52 请求，验证 57 帧与校验和） */
    public byte[] readBlock(int addr) throws Exception {
        for (int retry = 0; retry < 3; retry++) {
            write(new byte[]{
                    0x52,
                    (byte) ((addr >> 8) & 0xFF),
                    (byte) (addr & 0xFF),
                    0x20});
            byte[] frame = readExact(37, frameTimeoutMs);
            if (frame == null) {
                log("读块 0x" + Integer.toHexString(addr) + " 超时，重试");
                continue;
            }
            if ((frame[0] & 0xFF) != 0x57) {
                log("帧头错误: " + HexUtil.bytesToHex(frame, 0, 4));
                continue;
            }
            int sum = HexUtil.checksum(frame, 4, 32);
            if (sum != (frame[36] & 0xFF)) {
                log("校验和错误");
                continue;
            }
            byte[] out = new byte[32];
            System.arraycopy(frame, 4, out, 0, 32);
            return out;
        }
        throw new Exception("读块 0x" + Integer.toHexString(addr) + " 失败");
    }

    /**
     * 完整读频流程。@param image 输出镜像；@return 读到的块数
     */
    public int readAll(EepromImage image, String radioModel) throws Exception {
        int total = EepromImage.blockCount() + 1;
        int done = 0;
        if (listener != null) listener.onProgress(0, total, "准备");

        // 1. 信息同步命令
        byte[] info = readBlock(EepromImage.INFO_ADDR);
        image.setBlock(EepromImage.INFO_ADDR, info);
        done++;
        if (listener != null) listener.onProgress(done, total, "参数块 0x1B40");

        // 2. 全量读取
        for (int addr : EepromImage.addressSequence()) {
            byte[] blk = readBlock(addr);
            image.setBlock(addr, blk);
            done++;
            if (listener != null) listener.onProgress(done, total, addrText(addr));
            if (done % 20 == 0) log("读取进度 " + done + "/" + total);
        }

        // 3. 结束
        write(new byte[]{0x45});
        if (listener != null) listener.onProgress(total, total, "读取完成");
        return done;
    }

    // ------------------------------------------------------------------
    // 写频
    // ------------------------------------------------------------------

    /** 发送同步命令并读取信息响应（36或37字节均可） */
    public void syncInfo() throws Exception {
        for (int retry = 0; retry < 3; retry++) {
            write(new byte[]{0x52, 0x1B, 0x40, 0x20});
            byte[] resp = readAvailable(37, 200, 1000);
            if (resp != null && resp.length >= 34 && (resp[0] & 0xFF) == 0x57) {
                log("信息同步完成: " + HexUtil.bytesToHex(resp));
                return;
            }
            log("信息同步响应异常，重试");
        }
        throw new Exception("同步失败：电台无有效响应");
    }

    /**
     * 宽松读取：直到读满 n 字节，或出现 gapMs 静默且至少收到 1 字节，或总超时。
     * 用于处理 36/37 字节两种长度的信息响应。
     */
    public byte[] readAvailable(int n, int gapMs, int totalMs) throws Exception {
        byte[] buf = new byte[n];
        long start = System.currentTimeMillis();
        long lastRx = start;
        int got = 0;
        while (got < n) {
            byte[] one = new byte[1];
            long remain = totalMs - (System.currentTimeMillis() - start);
            long waitMs = Math.min((long) gapMs, remain);
            if (waitMs < 20) waitMs = 20;
            int r = port.read(one, (int) waitMs);
            if (r > 0) {
                buf[got++] = one[0];
                lastRx = System.currentTimeMillis();
            } else {
                if (got > 0 && System.currentTimeMillis() - lastRx >= gapMs) {
                    byte[] out = new byte[got];
                    System.arraycopy(buf, 0, out, 0, got);
                    log("RX(" + got + "): " + HexUtil.bytesToHex(out));
                    return out;
                }
                if (System.currentTimeMillis() - start > totalMs) {
                    log("RX timeout after " + got + "/" + n + " bytes");
                    return null;
                }
            }
        }
        log("RX: " + HexUtil.bytesToHex(buf));
        return buf;
    }

    /** 写一个 32 字节块 */
    public void writeBlock(int addr, byte[] block) throws Exception {
        byte[] frame = new byte[37];
        frame[0] = 0x57;
        frame[1] = (byte) ((addr >> 8) & 0xFF);
        frame[2] = (byte) (addr & 0xFF);
        frame[3] = 0x20;
        System.arraycopy(block, 0, frame, 4, 32);
        frame[36] = (byte) HexUtil.checksum(block, 0, 32);

        for (int retry = 0; retry < 3; retry++) {
            write(frame);
            byte[] ack = readExact(1, frameTimeoutMs);
            if (ack != null && (ack[0] & 0xFF) == 0x06) {
                return;
            }
            log("写块 0x" + Integer.toHexString(addr) + " 未确认，重试");
        }
        throw new Exception("写块 0x" + Integer.toHexString(addr) + " 失败");
    }

    /** 完整写频流程 */
    public int writeAll(EepromImage image) throws Exception {
        int total = EepromImage.blockCount();
        int done = 0;
        if (listener != null) listener.onProgress(0, total, "同步");
        syncInfo();

        for (int addr : EepromImage.addressSequence()) {
            writeBlock(addr, image.getBlock(addr));
            done++;
            if (listener != null) listener.onProgress(done, total, addrText(addr));
            if (done % 20 == 0) log("写入进度 " + done + "/" + total);
        }
        write(new byte[]{0x45});
        if (listener != null) listener.onProgress(total, total, "写入完成");
        return done;
    }

    private String addrText(int addr) {
        return "0x" + Integer.toHexString(addr).toUpperCase();
    }
}
