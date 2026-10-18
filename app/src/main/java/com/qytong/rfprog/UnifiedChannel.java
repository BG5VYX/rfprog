package com.qytong.rfprog;

/**
 * 与机型无关的统一频道模型。
 *
 * 读频时把各机型的数据解析到本模型；写频时再按目标机型编码。
 * 因此切换机型后频率/亚音等数据不需要重新输入，可直接写入另一台机器。
 */
public class UnifiedChannel {

    public int index;              // 频道号 0-based
    public long rxHz = 0;          // 接收频率 Hz (0 = 空频道)
    public long txHz = 0;          // 发射频率 Hz
    public Tone rxTone = Tone.off();
    public Tone txTone = Tone.off();
    public boolean narrow = false; // 窄带
    public int power = 2;          // 0=低 1=中 2=高（默认高功率）
    public String name = "";       // 频道名（UV-K5 最多10字符）
    public int modulation = 0;     // 0=FM 1=AM 2=USB 3=LSB (UV-K5)
    public boolean scramble = false; // 扰频 (UV-K5)
    public int step = 0;           // 步进索引（机型相关）

    /** 上次从 KT-10R 读到的原始 32 字节（用于保留未知字段） */
    public byte[] rawKt = null;
    /** 上次从 UV-K5 读到的原始 32 字节（用于保留未知字段） */
    public byte[] rawK5 = null;

    public boolean isEmpty() {
        return rxHz <= 0;
    }

    public String rxText() {
        return rxHz > 0 ? HexUtil.freqHzToMhzText(rxHz) : "——";
    }

    public String txText() {
        return txHz > 0 ? HexUtil.freqHzToMhzText(txHz) : "——";
    }

    public String toneSummary() {
        StringBuilder sb = new StringBuilder();
        if (rxTone.type != Tone.OFF) sb.append("R:").append(rxTone.label());
        if (txTone.type != Tone.OFF) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("T:").append(txTone.label());
        }
        return sb.toString();
    }

    public String summary() {
        if (isEmpty()) return "（空）";
        StringBuilder sb = new StringBuilder();
        sb.append(rxText()).append(" / ").append(txText());
        if (narrow) sb.append(" 窄");
        String t = toneSummary();
        if (t.length() > 0) sb.append(" ").append(t);
        if (name != null && name.length() > 0) sb.append("  「").append(name).append("」");
        return sb.toString();
    }

    public UnifiedChannel copy() {
        UnifiedChannel c = new UnifiedChannel();
        c.index = index;
        c.rxHz = rxHz;
        c.txHz = txHz;
        c.rxTone = rxTone;
        c.txTone = txTone;
        c.narrow = narrow;
        c.power = power;
        c.name = name;
        c.modulation = modulation;
        c.scramble = scramble;
        c.step = step;
        c.rawKt = rawKt;
        c.rawK5 = rawK5;
        return c;
    }
}
