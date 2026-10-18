package com.qytong.rfprog;

import java.util.ArrayList;
import java.util.List;

/**
 * KT-10R 频道编解码：32 字节块 <-> 统一频道模型
 *   [0-3] RX频率BCD  [4-7] TX频率BCD  [8-9] RX QT/DQT  [10-11] TX QT/DQT
 *   [12] ?  [13] 标志  [14] 标志(bit3=窄带)  [15] ?
 */
public class Kt10rCodec {

    /** 频道块数量 */
    public static int channelCount() {
        return EepromImage.addressSequence().length;
    }

    public static UnifiedChannel decode(int idx, byte[] raw) {
        UnifiedChannel c = new UnifiedChannel();
        c.index = idx;
        c.rxHz = HexUtil.bcdToFreqHz(raw, 0);
        c.txHz = HexUtil.bcdToFreqHz(raw, 4);
        if (c.rxHz < 0) c.rxHz = 0;
        if (c.txHz < 0) c.txHz = 0;
        c.rxTone = Tone.decode(raw, 8);
        c.txTone = Tone.decode(raw, 10);
        c.narrow = (raw[14] & 0x08) != 0;
        c.power = (raw[13] >> 6) & 0x03;
        c.rawKt = raw.clone();
        return c;
    }

    public static byte[] encode(UnifiedChannel c) {
        byte[] raw = c.rawKt != null ? c.rawKt.clone() : new byte[32];
        if (c.rxHz > 0) {
            byte[] bcd = HexUtil.freqHzToBcd(c.rxHz);
            if (bcd != null) System.arraycopy(bcd, 0, raw, 0, 4);
        }
        if (c.txHz > 0) {
            byte[] bcd = HexUtil.freqHzToBcd(c.txHz);
            if (bcd != null) System.arraycopy(bcd, 0, raw, 4, 4);
        }
        byte[] t1 = c.rxTone.encode();
        byte[] t2 = c.txTone.encode();
        System.arraycopy(t1, 0, raw, 8, 2);
        System.arraycopy(t2, 0, raw, 10, 2);
        // 窄带 bit3
        if (c.narrow) raw[14] |= 0x08;
        else raw[14] &= ~0x08;
        // 两位参数
        raw[13] = (byte) ((raw[13] & 0x3F) | ((c.power & 0x03) << 6));
        return raw;
    }

    /** 从 KT-10R 镜像解析频道（跳过保留区） */
    public static List<UnifiedChannel> parseChannels(byte[] image) {
        List<UnifiedChannel> list = new ArrayList<UnifiedChannel>();
        int[] seq = EepromImage.addressSequence();
        for (int i = 0; i < seq.length; i++) {
            int addr = seq[i];
            byte[] raw = new byte[32];
            System.arraycopy(image, addr, raw, 0, 32);
            UnifiedChannel c = decode(i, raw);
            c.index = i;
            list.add(c);
        }
        return list;
    }

    /** 把统一频道写回 KT-10R 镜像 */
    public static void applyChannels(byte[] image, List<UnifiedChannel> channels) {
        int[] seq = EepromImage.addressSequence();
        for (int i = 0; i < seq.length && i < channels.size(); i++) {
            byte[] raw = encode(channels.get(i));
            System.arraycopy(raw, 0, image, seq[i], 32);
        }
    }
}
