package com.qytong.rfprog;

/**
 * 支持的写频机型
 */
public enum RadioType {
    KT10R("泉益通 KT-10R", "GT-12 / W818 平台", 9600),
    UVK5("泉盛 UV-K5", "Quansheng UV-K5 / K6", 38400);

    public final String displayName;
    public final String subName;
    public final int baud;

    RadioType(String displayName, String subName, int baud) {
        this.displayName = displayName;
        this.subName = subName;
        this.baud = baud;
    }

    public static RadioType fromIndex(int i) {
        return i == 1 ? UVK5 : KT10R;
    }

    public static String[] labels() {
        return new String[]{KT10R.displayName + "  (" + KT10R.subName + ")",
                UVK5.displayName + "  (" + UVK5.subName + ")"};
    }
}
