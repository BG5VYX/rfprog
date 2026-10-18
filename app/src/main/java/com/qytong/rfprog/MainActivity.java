package com.qytong.rfprog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 写频助手 v3：卡片式频道编辑 + 频率表导入导出 + EEPROM/校准备份还原
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.qytong.rfprog.USB_PERMISSION";
    private static final int REQ_SAVE_IMAGE = 2;
    private static final int REQ_OPEN_IMAGE = 3;
    private static final int REQ_EXPORT_CSV = 4;
    private static final int REQ_IMPORT_CSV = 5;
    private static final int REQ_BACKUP = 6;
    private static final int REQ_RESTORE = 7;

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Kt10rLink ktLink;
    private Uvk5Radio k5Radio;

    private Spinner spinnerMachine, spinnerDevices, spQt1, spQt2, spBand, spPower, spMod;
    private Button btnConnect, btnRead, btnWrite, btnTools, btnPrev, btnNext, btnSaveCh, btnClearLog;
    private TextView tvStatus, tvLog, tvMachineInfo, tvChannelNo, tvTotal;
    private CheckBox cbScramble;
    private View panelK5Only;
    private ProgressBar progress;
    private ScrollView scrollLog;
    private EditText etRx, etTx, etName;

    private final List<UsbSerialDriver> drivers = new ArrayList<UsbSerialDriver>();
    private ArrayAdapter<String> deviceLabels, machineLabels, toneAdapter;
    private List<Tone> toneOptions = Tone.allOptions();

    private RadioType machine = RadioType.KT10R;
    private List<UnifiedChannel> channels = new ArrayList<UnifiedChannel>();
    private int currentIndex = 0;

    private byte[] imageKt = new byte[EepromImage.SIZE];
    private byte[] imageK5 = new byte[Uvk5Radio.EEPROM_SIZE];
    private boolean ktLoaded = false;
    private boolean k5Loaded = false;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String radioInfo = "";
    private int pendingBackupRange = 0;   // 0=全量 1=校准

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            onCreateInner(savedInstanceState);
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    private void onCreateInner(Bundle savedInstanceState) {
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        spinnerMachine = (Spinner) findViewById(R.id.spinnerMachine);
        spinnerDevices = (Spinner) findViewById(R.id.spinnerDevices);
        spQt1 = (Spinner) findViewById(R.id.spQt1);
        spQt2 = (Spinner) findViewById(R.id.spQt2);
        spBand = (Spinner) findViewById(R.id.spBand);
        spPower = (Spinner) findViewById(R.id.spPower);
        spMod = (Spinner) findViewById(R.id.spMod);
        btnConnect = (Button) findViewById(R.id.btnConnect);
        btnRead = (Button) findViewById(R.id.btnRead);
        btnWrite = (Button) findViewById(R.id.btnWrite);
        btnTools = (Button) findViewById(R.id.btnTools);
        btnPrev = (Button) findViewById(R.id.btnPrev);
        btnNext = (Button) findViewById(R.id.btnNext);
        btnSaveCh = (Button) findViewById(R.id.btnSaveCh);
        btnClearLog = (Button) findViewById(R.id.btnClearLog);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvLog = (TextView) findViewById(R.id.tvLog);
        tvMachineInfo = (TextView) findViewById(R.id.tvMachineInfo);
        tvChannelNo = (TextView) findViewById(R.id.tvChannelNo);
        tvTotal = (TextView) findViewById(R.id.tvTotal);
        cbScramble = (CheckBox) findViewById(R.id.cbScramble);
        panelK5Only = findViewById(R.id.panelK5Only);
        progress = (ProgressBar) findViewById(R.id.progress);
        scrollLog = (ScrollView) findViewById(R.id.scrollLog);
        etRx = (EditText) findViewById(R.id.etRx);
        etTx = (EditText) findViewById(R.id.etTx);
        etName = (EditText) findViewById(R.id.etName);

        machineLabels = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, RadioType.labels());
        spinnerMachine.setAdapter(machineLabels);
        SharedPreferences sp = getSharedPreferences("rfprog", MODE_PRIVATE);
        machine = RadioType.fromIndex(sp.getInt("machine", 0));
        spinnerMachine.setSelection(machine.ordinal());

        deviceLabels = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        spinnerDevices.setAdapter(deviceLabels);

        toneAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, toneLabels());
        spQt1.setAdapter(toneAdapter);
        spQt2.setAdapter(toneAdapter);

        spBand.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"宽带 Wide", "窄带 Narrow"}));

        spPower.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"低功率", "中功率", "高功率"}));

        spMod.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"FM", "AM", "USB", "LSB"}));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(new UsbReceiver(this), filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(new UsbReceiver(this), filter);
        }

        spinnerMachine.setOnItemSelectedListener(new MachineSelected(this));
        btnConnect.setOnClickListener(new Click(this, 0));
        btnRead.setOnClickListener(new Click(this, 1));
        btnWrite.setOnClickListener(new Click(this, 2));
        btnTools.setOnClickListener(new Click(this, 3));
        btnPrev.setOnClickListener(new Click(this, 4));
        btnNext.setOnClickListener(new Click(this, 5));
        btnSaveCh.setOnClickListener(new Click(this, 6));
        btnClearLog.setOnClickListener(new Click(this, 7));

        loadLocal();
        if (channels.isEmpty()) {
            initEmptyChannels();
        }
        currentIndex = 0;
        applyMachineUi();
        refreshDevices();
        showChannel(0);
        log("写频助手 v3.0 —— 卡片式写频 · 双机型");
        log("连接串口读取电台，或直接在卡片中录入频率");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        try {
            if (serialPort != null) serialPort.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        switch (requestCode) {
            case REQ_SAVE_IMAGE: saveImage(data.getData()); break;
            case REQ_OPEN_IMAGE: openImage(data.getData()); break;
            case REQ_EXPORT_CSV: exportCsv(data.getData()); break;
            case REQ_IMPORT_CSV: importCsv(data.getData()); break;
            case REQ_BACKUP: writeBackup(data.getData()); break;
            case REQ_RESTORE: readRestore(data.getData()); break;
        }
    }

    // ------------------------------------------------------------------
    // 机型 / UI
    // ------------------------------------------------------------------

    void setMachine(RadioType t) {
        if (machine == t) return;
        machine = t;
        getSharedPreferences("rfprog", MODE_PRIVATE).edit().putInt("machine", t.ordinal()).apply();
        applyMachineUi();
        if (serialPort != null) {
            disconnect();
            log("机型已切换，请重新连接（串口速率不同）");
        }
        showChannel(currentIndex);
    }

    void applyMachineUi() {
        panelK5Only.setVisibility(machine == RadioType.UVK5 ? View.VISIBLE : View.GONE);
        tvTotal.setText("/ " + (machine == RadioType.UVK5 ? Uvk5Radio.CH_COUNT : Kt10rCodec.channelCount()));
        updateMachineInfo();
    }

    void updateMachineInfo() {
        String s = machine.displayName + " · " + machine.baud + " 8N1 · "
                + (serialPort != null ? "已连接" : "未连接");
        if (radioInfo != null && radioInfo.length() > 0) s += " · " + radioInfo;
        tvMachineInfo.setText(s);
    }

    // ------------------------------------------------------------------
    // 频道卡片
    // ------------------------------------------------------------------

    void initEmptyChannels() {
        channels.clear();
        int n = machine == RadioType.UVK5 ? Uvk5Radio.CH_COUNT : Kt10rCodec.channelCount();
        for (int i = 0; i < n; i++) {
            UnifiedChannel c = new UnifiedChannel();
            c.index = i;
            channels.add(c);
        }
    }

    void showChannel(int idx) {
        if (channels.isEmpty()) initEmptyChannels();
        if (idx < 0) idx = 0;
        if (idx >= channels.size()) idx = channels.size() - 1;
        currentIndex = idx;
        UnifiedChannel c = channels.get(idx);
        tvChannelNo.setText(String.format("%03d", idx + 1));
        etRx.setText(c.rxHz > 0 ? HexUtil.freqHzToMhzText(c.rxHz) : "");
        etTx.setText(c.txHz > 0 ? HexUtil.freqHzToMhzText(c.txHz) : "");
        etName.setText(c.name == null ? "" : c.name);
        spQt1.setSelection(indexOfTone(c.rxTone));
        spQt2.setSelection(indexOfTone(c.txTone));
        spBand.setSelection(c.narrow ? 1 : 0);
        spPower.setSelection(Math.max(0, Math.min(2, c.power)));
        spMod.setSelection(Math.max(0, Math.min(3, c.modulation)));
        cbScramble.setChecked(c.scramble);
        tvStatus.setText("正在编辑 信道 " + (idx + 1) + (c.isEmpty() ? "（空）" : " · " + c.summary()));
    }

    /** 读取卡片内容写回当前频道 */
    void readCard() {
        UnifiedChannel c = channels.get(currentIndex);
        long rx = HexUtil.parseMhz(etRx.getText().toString());
        long tx = HexUtil.parseMhz(etTx.getText().toString());
        if (rx <= 0 && tx <= 0) {
            c.rxHz = 0;
            c.txHz = 0;
        } else {
            c.rxHz = rx > 0 ? rx : tx;
            c.txHz = tx > 0 ? tx : c.rxHz;
        }
        c.rxTone = toneOptions.get(Math.max(0, Math.min(toneOptions.size() - 1, spQt1.getSelectedItemPosition())));
        c.txTone = toneOptions.get(Math.max(0, Math.min(toneOptions.size() - 1, spQt2.getSelectedItemPosition())));
        c.narrow = spBand.getSelectedItemPosition() == 1;
        c.power = spPower.getSelectedItemPosition();
        c.modulation = spMod.getSelectedItemPosition();
        c.scramble = cbScramble.isChecked();
        c.name = etName.getText().toString().trim();
        c.index = currentIndex;
    }

    void saveChannelLocal() {
        readCard();
        saveLocal();
        UnifiedChannel c = channels.get(currentIndex);
        tvStatus.setText("已保存 信道 " + (currentIndex + 1) + (c.isEmpty() ? "（空）" : " · " + c.summary()));
        log("信道 " + (currentIndex + 1) + " 已保存到本地");
        Toast.makeText(this, "已保存到本地", Toast.LENGTH_SHORT).show();
    }

    void nextChannel() {
        readCard();
        saveLocal();
        showChannel(currentIndex + 1);
    }

    void prevChannel() {
        readCard();
        saveLocal();
        showChannel(currentIndex - 1);
    }

    private List<String> toneLabels() {
        List<String> labels = new ArrayList<String>();
        for (Tone t : toneOptions) labels.add(t.label());
        return labels;
    }

    private int indexOfTone(Tone t) {
        if (t == null) return 0;
        for (int i = 0; i < toneOptions.size(); i++) {
            if (toneOptions.get(i).sameAs(t)) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 本地存储
    // ------------------------------------------------------------------

    private java.io.File localFile() {
        return new java.io.File(getFilesDir(), "channels.json");
    }

    void saveLocal() {
        try {
            JSONArray arr = new JSONArray();
            for (UnifiedChannel c : channels) {
                if (c.isEmpty() && (c.name == null || c.name.length() == 0)
                        && c.rxTone.type == Tone.OFF && c.txTone.type == Tone.OFF) continue;
                JSONObject o = new JSONObject();
                o.put("i", c.index);
                o.put("rx", c.rxHz);
                o.put("tx", c.txHz);
                o.put("rt", toneToText(c.rxTone));
                o.put("tt", toneToText(c.txTone));
                o.put("nw", c.narrow);
                o.put("pw", c.power);
                o.put("nm", c.name == null ? "" : c.name);
                o.put("md", c.modulation);
                o.put("sc", c.scramble);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("machine", machine.name());
            root.put("channels", arr);
            OutputStream os = new java.io.FileOutputStream(localFile());
            os.write(root.toString().getBytes("UTF-8"));
            os.close();
        } catch (Exception e) {
            log("本地保存失败: " + e.getMessage());
        }
    }

    void loadLocal() {
        try {
            java.io.File f = localFile();
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            InputStream is = new java.io.FileInputStream(f);
            int n = is.read(buf);
            is.close();
            JSONObject root = new JSONObject(new String(buf, 0, n, "UTF-8"));
            JSONArray arr = root.optJSONArray("channels");
            if (arr == null) return;
            initEmptyChannels();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int idx = o.optInt("i", i);
                if (idx < 0 || idx >= channels.size()) continue;
                UnifiedChannel c = channels.get(idx);
                c.rxHz = o.optLong("rx", 0);
                c.txHz = o.optLong("tx", 0);
                c.rxTone = toneFromText(o.optString("rt", "OFF"));
                c.txTone = toneFromText(o.optString("tt", "OFF"));
                c.narrow = o.optBoolean("nw", false);
                c.power = o.optInt("pw", 1);
                c.name = o.optString("nm", "");
                c.modulation = o.optInt("md", 0);
                c.scramble = o.optBoolean("sc", false);
            }
            log("已载入本地频率表");
        } catch (Exception e) {
            log("本地载入失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 频率表 CSV
    // ------------------------------------------------------------------

    void startExportCsv() {
        readCard();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "channels.csv");
        startActivityForResult(i, REQ_EXPORT_CSV);
    }

    void startImportCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT_CSV);
    }

    void exportCsv(android.net.Uri uri) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("ch,rx,tx,rx_tone,tx_tone,bandwidth,power,name,modulation,scramble\n");
            for (UnifiedChannel c : channels) {
                if (c.isEmpty()) continue;
                sb.append(c.index + 1).append(',')
                        .append(c.rxHz > 0 ? HexUtil.freqHzToMhzText(c.rxHz) : "").append(',')
                        .append(c.txHz > 0 ? HexUtil.freqHzToMhzText(c.txHz) : "").append(',')
                        .append(toneToText(c.rxTone)).append(',')
                        .append(toneToText(c.txTone)).append(',')
                        .append(c.narrow ? "narrow" : "wide").append(',')
                        .append(c.power == 0 ? "low" : (c.power == 2 ? "high" : "mid")).append(',')
                        .append(c.name == null ? "" : c.name.replace(",", " ")).append(',')
                        .append(c.modulation == 1 ? "AM" : (c.modulation == 2 ? "USB" : (c.modulation == 3 ? "LSB" : "FM"))).append(',')
                        .append(c.scramble ? 1 : 0).append('\n');
            }
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            Toast.makeText(this, "频率表已导出", Toast.LENGTH_SHORT).show();
            log("频率表已导出");
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void importCsv(android.net.Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            int count = 0;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                if (first && line.toLowerCase().startsWith("ch,")) { first = false; continue; }
                first = false;
                String[] p = line.split(",", -1);
                if (p.length < 2) continue;
                int idx;
                try {
                    idx = Integer.parseInt(p[0].trim()) - 1;
                } catch (Exception e) {
                    continue;
                }
                if (idx < 0 || idx >= channels.size()) continue;
                UnifiedChannel c = channels.get(idx);
                c.index = idx;
                if (p.length > 1 && p[1].trim().length() > 0) {
                    long v = HexUtil.parseMhz(p[1]);
                    if (v > 0) c.rxHz = v;
                }
                if (p.length > 2 && p[2].trim().length() > 0) {
                    long v = HexUtil.parseMhz(p[2]);
                    if (v > 0) c.txHz = v;
                } else if (c.txHz <= 0) {
                    c.txHz = c.rxHz;
                }
                if (p.length > 3) c.rxTone = toneFromText(p[3].trim());
                if (p.length > 4) c.txTone = toneFromText(p[4].trim());
                if (p.length > 5) c.narrow = p[5].trim().toLowerCase().startsWith("n");
                if (p.length > 6) {
                    String pw = p[6].trim().toLowerCase();
                    c.power = pw.startsWith("l") ? 0 : (pw.startsWith("h") ? 2 : 1);
                }
                if (p.length > 7) c.name = p[7].trim();
                if (p.length > 8) {
                    String md = p[8].trim().toUpperCase();
                    c.modulation = md.equals("AM") ? 1 : (md.equals("USB") ? 2 : (md.equals("LSB") ? 3 : 0));
                }
                if (p.length > 9) c.scramble = p[9].trim().equals("1");
                count++;
            }
            br.close();
            saveLocal();
            showChannel(currentIndex);
            Toast.makeText(this, "已导入 " + count + " 个信道", Toast.LENGTH_SHORT).show();
            log("频率表导入完成，共 " + count + " 个信道");
        } catch (Exception e) {
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static String toneToText(Tone t) {
        if (t == null || t.type == Tone.OFF) return "OFF";
        if (t.type == Tone.CTCSS) return String.format("%.1f", t.hz);
        return "D" + String.format("%03d", t.dcsCode) + (t.inverted ? "I" : "");
    }

    static Tone toneFromText(String s) {
        if (s == null) return Tone.off();
        s = s.trim();
        if (s.length() == 0 || s.equalsIgnoreCase("OFF") || s.equals("0")) return Tone.off();
        if (s.toUpperCase().startsWith("D")) {
            boolean inv = s.toUpperCase().endsWith("I");
            String num = s.substring(1);
            if (inv) num = num.substring(0, num.length() - 1);
            try {
                return Tone.dcs(Integer.parseInt(num), inv);
            } catch (Exception e) {
                return Tone.off();
            }
        }
        try {
            return Tone.ctcss(Double.parseDouble(s));
        } catch (Exception e) {
            return Tone.off();
        }
    }

    // ------------------------------------------------------------------
    // 备份 / 还原
    // ------------------------------------------------------------------

    void startBackup(int range) {
        pendingBackupRange = range;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        String name = machine == RadioType.KT10R ? "kt10r_full.bin"
                : (range == 1 ? "uvk5_calibration.bin" : "uvk5_eeprom.bin");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_BACKUP);
    }

    void startRestore(int range) {
        pendingBackupRange = range;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_RESTORE);
    }

    void writeBackup(android.net.Uri uri) {
        try {
            if (serialPort == null) {
                Toast.makeText(this, "请先连接电台", Toast.LENGTH_SHORT).show();
                return;
            }
            if (machine == RadioType.KT10R) {
                byte[] img = imageKt;
                if (!ktLoaded) {
                    ktLink.handshake();
                    EepromImage e = new EepromImage();
                    ktLink.readAll(e, "");
                    img = e.data;
                    imageKt = img;
                    ktLoaded = true;
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(img);
                os.close();
                log("KT-10R 全量备份完成 (" + img.length + " 字节)");
            } else {
                byte[] img = k5Radio.readAll();
                imageK5 = img;
                k5Loaded = true;
                byte[] out;
                if (pendingBackupRange == 1) {
                    out = new byte[0x200];
                    System.arraycopy(img, 0x1E00, out, 0, 0x200);
                    log("UV-K5 校准备份完成 (0x1E00-0x2000)");
                } else {
                    out = img;
                    log("UV-K5 EEPROM 备份完成 (" + img.length + " 字节)");
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(out);
                os.close();
            }
            Toast.makeText(this, "备份已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "备份失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            log("备份失败: " + e.getMessage());
        }
    }

    void readRestore(android.net.Uri uri) {
        try {
            if (serialPort == null) {
                Toast.makeText(this, "请先连接电台", Toast.LENGTH_SHORT).show();
                return;
            }
            InputStream is = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();

            if (machine == RadioType.KT10R) {
                if (data.length != EepromImage.SIZE) {
                    Toast.makeText(this, "文件大小不符（应为 " + EepromImage.SIZE + " 字节）", Toast.LENGTH_LONG).show();
                    return;
                }
                ktLink.handshake();
                ktLink.writeAll(EepromImage.from(data));
                imageKt = data;
                ktLoaded = true;
                log("KT-10R 全量还原完成");
            } else {
                if (pendingBackupRange == 1) {
                    if (data.length != 0x200) {
                        Toast.makeText(this, "校准文件应为 512 字节", Toast.LENGTH_LONG).show();
                        return;
                    }
                    k5Radio.init();
                    k5Radio.writeEeprom(0x1E00, data, 0x200);
                    log("UV-K5 校准已还原");
                } else {
                    if (data.length > Uvk5Radio.EEPROM_SIZE) {
                        Toast.makeText(this, "文件过大", Toast.LENGTH_LONG).show();
                        return;
                    }
                    byte[] img = new byte[Uvk5Radio.EEPROM_SIZE];
                    System.arraycopy(data, 0, img, 0, data.length);
                    k5Radio.init();
                    k5Radio.writeAll(img);
                    k5Radio.reboot();
                    imageK5 = img;
                    k5Loaded = true;
                    log("UV-K5 EEPROM 还原完成");
                }
            }
            Toast.makeText(this, "还原完成，建议重新读取", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "还原失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            log("还原失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 工具菜单
    // ------------------------------------------------------------------

    void showTools() {
        final String[] items;
        if (machine == RadioType.UVK5) {
            items = new String[]{
                    "导入频率表 (CSV)", "导出频率表 (CSV)",
                    "备份 EEPROM", "还原 EEPROM",
                    "备份校准数据", "还原校准数据",
                    "保存镜像文件", "打开镜像文件",
                    "查看原始数据 (HEX)"};
        } else {
            items = new String[]{
                    "导入频率表 (CSV)", "导出频率表 (CSV)",
                    "备份 EEPROM (全量)", "还原 EEPROM (全量)",
                    "保存镜像文件", "打开镜像文件",
                    "查看原始数据 (HEX)"};
        }
        new AlertDialog.Builder(this)
                .setTitle("工具 · " + machine.displayName)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (machine == RadioType.UVK5) {
                            switch (which) {
                                case 0: startImportCsv(); break;
                                case 1: startExportCsv(); break;
                                case 2: startBackup(0); break;
                                case 3: startRestore(0); break;
                                case 4: startBackup(1); break;
                                case 5: startRestore(1); break;
                                case 6: startSave(); break;
                                case 7: startOpen(); break;
                                case 8: openHex(); break;
                            }
                        } else {
                            switch (which) {
                                case 0: startImportCsv(); break;
                                case 1: startExportCsv(); break;
                                case 2: startBackup(0); break;
                                case 3: startRestore(0); break;
                                case 4: startSave(); break;
                                case 5: startOpen(); break;
                                case 6: openHex(); break;
                            }
                        }
                    }
                })
                .show();
    }

    void openHex() {
        HexViewActivity.image = getImage();
        HexViewActivity.title = machine.displayName + " 原始数据";
        startActivity(new Intent(this, HexViewActivity.class));
    }

    void startSave() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, (machine == RadioType.KT10R ? "kt10r" : "uvk5") + "_image.bin");
        startActivityForResult(i, REQ_SAVE_IMAGE);
    }

    void startOpen() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN_IMAGE);
    }

    void saveImage(android.net.Uri uri) {
        try {
            byte[] img = getImage();
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(img);
            os.flush();
            os.close();
            Toast.makeText(this, "镜像已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void openImage(android.net.Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            int size = machine == RadioType.KT10R ? EepromImage.SIZE : Uvk5Radio.EEPROM_SIZE;
            byte[] buf = new byte[size];
            int n = is.read(buf);
            is.close();
            if (machine == RadioType.KT10R) {
                imageKt = buf;
                ktLoaded = true;
            } else {
                imageK5 = buf;
                k5Loaded = true;
            }
            List<UnifiedChannel> list = machine == RadioType.KT10R
                    ? Kt10rCodec.parseChannels(buf)
                    : Uvk5Radio.parseChannels(buf);
            channels = new ArrayList<UnifiedChannel>(list);
            saveLocal();
            showChannel(0);
            log("镜像已加载: " + n + " 字节");
        } catch (Exception e) {
            Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // USB
    // ------------------------------------------------------------------

    void refreshDevices() {
        drivers.clear();
        List<String> labels = new ArrayList<String>();
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            UsbSerialDriver drv = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (drv != null) {
                drivers.add(drv);
                labels.add(String.format("%04X:%04X %s", dev.getVendorId(), dev.getProductId(), dev.getDeviceName()));
            }
        }
        deviceLabels.clear();
        deviceLabels.addAll(labels);
        deviceLabels.notifyDataSetChanged();
        log("发现串口设备: " + labels.size() + " 个");
    }

    void requestOpen() {
        if (drivers.isEmpty()) {
            Toast.makeText(this, "未发现USB串口，请检查OTG", Toast.LENGTH_LONG).show();
            refreshDevices();
            return;
        }
        int idx = spinnerDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= drivers.size()) idx = 0;
        UsbSerialDriver drv = drivers.get(idx);
        if (usbManager.hasPermission(drv.getDevice())) {
            openSelected();
        } else {
            int flags = android.os.Build.VERSION.SDK_INT >= 23
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(drv.getDevice(), pi);
        }
    }

    void openSelected() {
        int idx = spinnerDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= drivers.size()) idx = 0;
        UsbSerialDriver drv = drivers.get(idx);
        UsbSerialPort port = drv.getPorts().get(0);
        try {
            port.open(usbManager.openDevice(drv.getDevice()));
            port.setParameters(machine.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort = port;
            if (machine == RadioType.KT10R) {
                ktLink = new Kt10rLink(port, new LinkListener(this));
                k5Radio = null;
            } else {
                k5Radio = new Uvk5Radio(port, new K5Listener(this));
                ktLink = null;
            }
            btnConnect.setText("断开");
            updateMachineInfo();
            log("串口已打开: " + machine.baud + " 8N1");
        } catch (Exception e) {
            log("打开串口失败: " + e.getMessage());
            Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void disconnect() {
        try {
            if (serialPort != null) serialPort.close();
        } catch (Exception ignored) {
        }
        serialPort = null;
        ktLink = null;
        k5Radio = null;
        btnConnect.setText("连接");
        updateMachineInfo();
        log("串口已关闭");
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    void startRead() {
        if (serialPort == null) {
            Toast.makeText(this, "请先连接串口", Toast.LENGTH_SHORT).show();
            return;
        }
        setButtonsEnabled(false);
        io.execute(new ReadTask(this));
    }

    void confirmWrite() {
        if (serialPort == null) {
            Toast.makeText(this, "请先连接串口", Toast.LENGTH_SHORT).show();
            return;
        }
        readCard();
        saveLocal();
        new AlertDialog.Builder(this)
                .setTitle("写入电台")
                .setMessage("将当前 " + channels.size() + " 个信道写入 " + machine.displayName
                        + "？\n会覆盖电台原有数据。")
                .setPositiveButton("写入", new WriteConfirmClick(this))
                .setNegativeButton("取消", null)
                .show();
    }

    void startWrite() {
        setButtonsEnabled(false);
        io.execute(new WriteTask(this));
    }

    void setButtonsEnabled(boolean en) {
        btnRead.setEnabled(en);
        btnWrite.setEnabled(en);
        btnSaveCh.setEnabled(en);
    }

    byte[] getImage() {
        return machine == RadioType.KT10R ? imageKt : imageK5;
    }

    void setImage(byte[] img) {
        if (machine == RadioType.KT10R) {
            imageKt = img;
            ktLoaded = true;
        } else {
            imageK5 = img;
            k5Loaded = true;
        }
    }

    boolean isImageLoaded() {
        return machine == RadioType.KT10R ? ktLoaded : k5Loaded;
    }

    void setChannels(List<UnifiedChannel> list) {
        channels = new ArrayList<UnifiedChannel>(list);
        saveLocal();
        showChannel(0);
    }

    void setRadioInfo(String s) {
        radioInfo = s;
        updateMachineInfo();
    }

    TextView getTvStatus() {
        return tvStatus;
    }

    Handler getUi() {
        return ui;
    }

    void log(String msg) {
        ui.post(new LogPost(this, msg));
    }

    void showProgress(int current, int total, String msg) {
        ui.post(new ProgressPost(this, current, total, msg));
    }

    /** 启动异常时显示错误，便于定位 */
    private void showCrash(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String msg = "启动失败：\n" + sw.toString();
            android.util.Log.e("rfprog", msg);
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setText(msg);
            tv.setTextSize(11);
            tv.setTextColor(0xFFE6F1FF);
            tv.setTextIsSelectable(true);
            tv.setPadding(24, 24, 24, 24);
            sv.setBackgroundColor(0xFF070D18);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    // 内部类
    // ==================================================================

    static class MachineSelected implements AdapterView.OnItemSelectedListener {
        private final MainActivity act;

        MachineSelected(MainActivity a) {
            act = a;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            act.setMachine(RadioType.fromIndex(position));
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    static class UsbReceiver extends BroadcastReceiver {
        private final MainActivity act;

        UsbReceiver(MainActivity a) {
            act = a;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MainActivity.ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        act.log("已获得USB权限");
                        act.openSelected();
                    } else {
                        act.log("USB权限被拒绝");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                act.refreshDevices();
            }
        }
    }

    static class Click implements View.OnClickListener {
        private final MainActivity act;
        private final int what;

        Click(MainActivity a, int what) {
            this.act = a;
            this.what = what;
        }

        @Override
        public void onClick(View v) {
            switch (what) {
                case 0:
                    if (act.serialPort != null) act.disconnect();
                    else act.requestOpen();
                    break;
                case 1: act.startRead(); break;
                case 2: act.confirmWrite(); break;
                case 3: act.showTools(); break;
                case 4: act.prevChannel(); break;
                case 5: act.nextChannel(); break;
                case 6: act.saveChannelLocal(); break;
                case 7: act.tvLog.setText(""); break;
            }
        }
    }

    static class WriteConfirmClick implements DialogInterface.OnClickListener {
        private final MainActivity act;

        WriteConfirmClick(MainActivity a) {
            act = a;
        }

        @Override
        public void onClick(DialogInterface d, int w) {
            act.startWrite();
        }
    }

    static class LogPost implements Runnable {
        private final MainActivity act;
        private final String msg;

        LogPost(MainActivity a, String m) {
            act = a;
            msg = m;
        }

        @Override
        public void run() {
            act.tvLog.append(msg + "\n");
            act.scrollLog.post(new ScrollPost(act));
        }
    }

    static class ScrollPost implements Runnable {
        private final MainActivity act;

        ScrollPost(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            act.scrollLog.fullScroll(View.FOCUS_DOWN);
        }
    }

    static class ProgressPost implements Runnable {
        private final MainActivity act;
        private final int current, total;
        private final String msg;

        ProgressPost(MainActivity a, int c, int t, String m) {
            act = a;
            current = c;
            total = t;
            msg = m;
        }

        @Override
        public void run() {
            act.progress.setMax(total);
            act.progress.setProgress(current);
            act.tvStatus.setText((total > 0 ? (current * 100 / total) + "% " : "") + msg);
        }
    }

    static class ReadDonePost implements Runnable {
        private final MainActivity act;
        private final List<UnifiedChannel> list;
        private final String status;

        ReadDonePost(MainActivity a, List<UnifiedChannel> l, String s) {
            act = a;
            list = l;
            status = s;
        }

        @Override
        public void run() {
            act.setChannels(list);
            act.tvStatus.setText(status);
            act.setButtonsEnabled(true);
        }
    }

    static class WriteDonePost implements Runnable {
        private final MainActivity act;

        WriteDonePost(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            act.tvStatus.setText("写入完成");
            act.log("写频完成");
            act.setButtonsEnabled(true);
        }
    }

    static class ErrorPost implements Runnable {
        private final MainActivity act;
        private final String msg;

        ErrorPost(MainActivity a, String m) {
            act = a;
            msg = m;
        }

        @Override
        public void run() {
            act.tvStatus.setText(msg);
            act.log(msg);
            act.setButtonsEnabled(true);
        }
    }

    static class LinkListener implements Kt10rLink.Listener {
        private final MainActivity act;

        LinkListener(MainActivity a) {
            act = a;
        }

        @Override
        public void onLog(String msg) {
            act.log(msg);
        }

        @Override
        public void onProgress(int current, int total, String msg) {
            act.showProgress(current, total, msg);
        }
    }

    static class K5Listener implements Uvk5Radio.Listener {
        private final MainActivity act;

        K5Listener(MainActivity a) {
            act = a;
        }

        @Override
        public void onLog(String msg) {
            act.log(msg);
        }

        @Override
        public void onProgress(int current, int total, String msg) {
            act.showProgress(current, total, msg);
        }
    }

    static class ReadTask implements Runnable {
        private final MainActivity act;

        ReadTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            try {
                if (act.machine == RadioType.KT10R) {
                    String model = act.ktLink.handshake();
                    act.setRadioInfo("电台 " + model);
                    EepromImage img = new EepromImage();
                    act.ktLink.readAll(img, model);
                    act.setImage(img.data);
                    List<UnifiedChannel> list = Kt10rCodec.parseChannels(img.data);
                    act.ui.post(new ReadDonePost(act, list, "读取完成，共 " + list.size() + " 信道"));
                } else {
                    String ver = act.k5Radio.init();
                    act.setRadioInfo("UV-K5 " + ver);
                    byte[] img = act.k5Radio.readAll();
                    act.setImage(img);
                    List<UnifiedChannel> list = Uvk5Radio.parseChannels(img);
                    act.ui.post(new ReadDonePost(act, list, "读取完成，共 " + list.size() + " 信道"));
                }
            } catch (Exception e) {
                act.ui.post(new ErrorPost(act, "读频失败: " + e.getMessage()));
            }
        }
    }

    static class WriteTask implements Runnable {
        private final MainActivity act;

        WriteTask(MainActivity a) {
            act = a;
        }

        @Override
        public void run() {
            try {
                if (!act.isImageLoaded()) {
                    act.log("当前机型尚未读取，先读取原机数据以保留设置...");
                    if (act.machine == RadioType.KT10R) {
                        act.ktLink.handshake();
                        EepromImage img = new EepromImage();
                        act.ktLink.readAll(img, "");
                        act.setImage(img.data);
                    } else {
                        act.k5Radio.init();
                        act.setImage(act.k5Radio.readAll());
                    }
                }
                if (act.machine == RadioType.KT10R) {
                    byte[] img = act.getImage().clone();
                    Kt10rCodec.applyChannels(img, act.channels);
                    act.ktLink.handshake();
                    act.ktLink.writeAll(EepromImage.from(img));
                    act.setImage(img);
                } else {
                    byte[] img = act.getImage().clone();
                    Uvk5Radio.applyChannels(img, act.channels);
                    act.k5Radio.init();
                    act.k5Radio.writeAll(img);
                    act.k5Radio.reboot();
                    act.setImage(img);
                }
                act.ui.post(new WriteDonePost(act));
            } catch (Exception e) {
                act.ui.post(new ErrorPost(act, "写频失败: " + e.getMessage()));
            }
        }
    }
}
