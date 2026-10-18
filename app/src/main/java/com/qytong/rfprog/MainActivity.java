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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 写频助手主界面：支持 KT-10R 与 UV-K5 双机型，
 * 频道数据以统一模型保存，切换机型后可直接写入另一台机器。
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.qytong.rfprog.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Kt10rLink ktLink;
    private Uvk5Radio k5Radio;

    private Spinner spinnerMachine, spinnerDevices;
    private Button btnConnect, btnRead, btnWrite, btnSave, btnOpen, btnHex;
    private TextView tvStatus, tvLog, tvUvk5Info;
    private View panelUvk5;
    private ProgressBar progress;
    private ListView listChannels;
    private ScrollView scrollLog;

    private final List<UsbSerialDriver> drivers = new ArrayList<UsbSerialDriver>();
    private ArrayAdapter<String> deviceLabels;
    private ArrayAdapter<String> machineLabels;

    private RadioType machine = RadioType.KT10R;
    private List<UnifiedChannel> channels = new ArrayList<UnifiedChannel>();

    /** 各机型最近一次读到的原始镜像 */
    private byte[] imageKt = new byte[EepromImage.SIZE];
    private byte[] imageK5 = new byte[Uvk5Radio.EEPROM_SIZE];
    private boolean ktLoaded = false;
    private boolean k5Loaded = false;

    private ChannelAdapter channelAdapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String radioInfo = "";

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
        android.util.Log.i("rfprog", "onCreate start, sdk=" + android.os.Build.VERSION.SDK_INT);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        spinnerMachine = (Spinner) findViewById(R.id.spinnerMachine);
        spinnerDevices = (Spinner) findViewById(R.id.spinnerDevices);
        btnConnect = (Button) findViewById(R.id.btnConnect);
        btnRead = (Button) findViewById(R.id.btnRead);
        btnWrite = (Button) findViewById(R.id.btnWrite);
        btnSave = (Button) findViewById(R.id.btnSave);
        btnOpen = (Button) findViewById(R.id.btnOpen);
        btnHex = (Button) findViewById(R.id.btnHex);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvLog = (TextView) findViewById(R.id.tvLog);
        tvUvk5Info = (TextView) findViewById(R.id.tvUvk5Info);
        panelUvk5 = findViewById(R.id.panelUvk5);
        progress = (ProgressBar) findViewById(R.id.progress);
        listChannels = (ListView) findViewById(R.id.listChannels);
        scrollLog = (ScrollView) findViewById(R.id.scrollLog);

        machineLabels = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, RadioType.labels());
        spinnerMachine.setAdapter(machineLabels);

        SharedPreferences sp = getSharedPreferences("rfprog", MODE_PRIVATE);
        machine = RadioType.fromIndex(sp.getInt("machine", 0));
        spinnerMachine.setSelection(machine.ordinal());

        deviceLabels = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        spinnerDevices.setAdapter(deviceLabels);

        channelAdapter = new ChannelAdapter(channels, machine);
        listChannels.setAdapter(channelAdapter);

        spinnerMachine.setOnItemSelectedListener(new MachineSelected(this));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(new UsbReceiver(this), filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(new UsbReceiver(this), filter);
        }

        btnConnect.setOnClickListener(new ConnectClick(this));
        btnRead.setOnClickListener(new ActionClick(this, 0));
        btnWrite.setOnClickListener(new ActionClick(this, 1));
        btnSave.setOnClickListener(new ActionClick(this, 2));
        btnOpen.setOnClickListener(new ActionClick(this, 3));
        btnHex.setOnClickListener(new ActionClick(this, 4));
        listChannels.setOnItemClickListener(new ItemClick(this));

        applyMachineUi();
        refreshDevices();
        log("写频助手 v2.0 —— 支持 泉益通 KT-10R 与 泉盛 UV-K5");
        log("选择机型 → 连接 OTG 串口 → 读频 → 编辑 → 写频");
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
        if (requestCode == 2) saveImage(data.getData());
        else if (requestCode == 3) openImage(data.getData());
    }

    /** 启动异常时把错误显示出来，便于定位（而不是直接闪退） */
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
            tv.setTextIsSelectable(true);
            tv.setPadding(24, 24, 24, 24);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 机型
    // ------------------------------------------------------------------

    void setMachine(RadioType t) {
        if (machine == t) return;
        machine = t;
        getSharedPreferences("rfprog", MODE_PRIVATE).edit().putInt("machine", t.ordinal()).apply();
        applyMachineUi();
        channelAdapter = new ChannelAdapter(channels, machine);
        listChannels.setAdapter(channelAdapter);
        log("已切换机型: " + t.displayName + "（频道数据保留，可直接写入）");
    }

    void applyMachineUi() {
        panelUvk5.setVisibility(machine == RadioType.UVK5 ? View.VISIBLE : View.GONE);
        if (machine == RadioType.UVK5) {
            tvUvk5Info.setText("UV-K5 特性：18-1300MHz 宽频接收 · FM/AM/USB/LSB 调制 · 扰频 · 200 频道 · 10 字符信道名 · 38400 波特率");
        }
        if (serialPort != null) {
            // 换机型需要重新连接（波特率不同）
            disconnect();
            log("机型已变更，请重新连接（串口速率不同）");
        }
        tvStatus.setText("机型: " + machine.displayName + " · 未连接");
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
                labels.add(String.format("%04X:%04X  %s", dev.getVendorId(), dev.getProductId(), dev.getDeviceName()));
            }
        }
        deviceLabels.clear();
        deviceLabels.addAll(labels);
        deviceLabels.notifyDataSetChanged();
        log("发现串口设备: " + labels.size() + " 个");
    }

    void requestOpen() {
        if (drivers.isEmpty()) {
            Toast.makeText(this, "未发现USB串口设备，请检查OTG连接", Toast.LENGTH_LONG).show();
            refreshDevices();
            return;
        }
        int idx = spinnerDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= drivers.size()) idx = 0;
        UsbSerialDriver drv = drivers.get(idx);
        if (usbManager.hasPermission(drv.getDevice())) {
            openSelected();
        } else {
            int piFlags = android.os.Build.VERSION.SDK_INT >= 23
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), piFlags);
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
            btnConnect.setText(R.string.disconnect);
            tvStatus.setText("已连接 " + machine.displayName + " · " + machine.baud + " 8N1");
            log("串口已打开: " + machine.baud + " 8N1");
        } catch (Exception e) {
            log("打开串口失败: " + e.getMessage());
            Toast.makeText(this, "打开串口失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        btnConnect.setText(R.string.connect);
        tvStatus.setText("机型: " + machine.displayName + " · 未连接");
        log("串口已关闭");
    }

    UsbSerialPort getSerialPort() {
        return serialPort;
    }

    Kt10rLink getKtLink() {
        return ktLink;
    }

    Uvk5Radio getK5Radio() {
        return k5Radio;
    }

    RadioType getMachine() {
        return machine;
    }

    // ------------------------------------------------------------------
    // 读 / 写
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
        if (channels.isEmpty()) {
            Toast.makeText(this, "还没有频道数据，请先读频或打开镜像", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认写频")
                .setMessage("将当前 " + channels.size() + " 个频道写入 " + machine.displayName
                        + "。\n注意：会覆盖电台原有数据，确定继续？")
                .setPositiveButton("确定写入", new WriteConfirmClick(this))
                .setNegativeButton("取消", null)
                .show();
    }

    void startWrite() {
        setButtonsEnabled(false);
        io.execute(new WriteTask(this));
    }

    void setChannels(List<UnifiedChannel> list) {
        channels.clear();
        channels.addAll(list);
        channelAdapter = new ChannelAdapter(channels, machine);
        listChannels.setAdapter(channelAdapter);
    }

    List<UnifiedChannel> getChannels() {
        return channels;
    }

    void applyImageStatus(String status) {
        channelAdapter.notifyDataSetChanged();
        tvStatus.setText(status + (radioInfo.isEmpty() ? "" : " · " + radioInfo));
        setButtonsEnabled(true);
    }

    void setButtonsEnabled(boolean en) {
        btnRead.setEnabled(en);
        btnWrite.setEnabled(en);
        btnSave.setEnabled(en);
        btnOpen.setEnabled(en);
    }

    void setRadioInfo(String info) {
        this.radioInfo = info;
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

    TextView getTvStatus() {
        return tvStatus;
    }

    Handler getUi() {
        return ui;
    }

    void startSave() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,
                (machine == RadioType.KT10R ? "kt10r" : "uvk5") + "_image.bin");
        startActivityForResult(intent, 2);
    }

    void startOpen() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 3);
    }

    void saveImage(android.net.Uri uri) {
        try {
            byte[] img = getImage();
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(img);
            os.flush();
            os.close();
            Toast.makeText(this, "镜像已保存", Toast.LENGTH_SHORT).show();
            log("镜像已保存: " + img.length + " 字节");
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
            setImage(buf);
            List<UnifiedChannel> list = machine == RadioType.KT10R
                    ? Kt10rCodec.parseChannels(buf)
                    : Uvk5Radio.parseChannels(buf);
            setChannels(list);
            applyImageStatus("打开镜像 " + n + " 字节");
            log("镜像已加载: " + n + " 字节，解析 " + list.size() + " 个频道");
        } catch (Exception e) {
            Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // 频道编辑
    // ------------------------------------------------------------------

    void editChannel(final int position) {
        final UnifiedChannel ch = channels.get(position);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inf = LayoutInflater.from(this);
        View view = inf.inflate(R.layout.dialog_channel, null);

        final EditText etRx = (EditText) view.findViewById(R.id.etRx);
        final EditText etTx = (EditText) view.findViewById(R.id.etTx);
        final EditText etName = (EditText) view.findViewById(R.id.etName);
        final EditText etHex = (EditText) view.findViewById(R.id.etHex);
        final TextView tvAddr = (TextView) view.findViewById(R.id.tvAddr);
        final Spinner spQt1 = (Spinner) view.findViewById(R.id.spQt1);
        final Spinner spQt2 = (Spinner) view.findViewById(R.id.spQt2);
        final Spinner spBand = (Spinner) view.findViewById(R.id.spBand);
        final Spinner spPower = (Spinner) view.findViewById(R.id.spPower);
        final Spinner spMod = (Spinner) view.findViewById(R.id.spMod);
        final CheckBox cbScramble = (CheckBox) view.findViewById(R.id.cbScramble);
        final View panelK5 = view.findViewById(R.id.panelK5Only);

        tvAddr.setText("频道 " + (position + 1) + (machine == RadioType.KT10R ? "  ·  0x" + Integer.toHexString(position * 32).toUpperCase() : "  ·  UV-K5 CH"));
        etRx.setText(ch.rxText().equals("——") ? "" : ch.rxText());
        etTx.setText(ch.txText().equals("——") ? "" : ch.txText());
        etName.setText(ch.name);
        byte[] raw = machine == RadioType.KT10R
                ? (ch.rawKt != null ? ch.rawKt : Kt10rCodec.encode(ch))
                : (ch.rawK5 != null ? ch.rawK5 : Uvk5Radio.encodeChannel(ch));
        etHex.setText(HexUtil.blockToHexLines(raw));

        List<Tone> tones = Tone.allOptions();
        ArrayAdapter<String> toneAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, toneLabels(tones));
        spQt1.setAdapter(toneAdapter);
        spQt2.setAdapter(toneAdapter);
        spQt1.setSelection(indexOfTone(tones, ch.rxTone));
        spQt2.setSelection(indexOfTone(tones, ch.txTone));

        ArrayAdapter<String> bandAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"宽带 Wide 25K", "窄带 Narrow 12.5K"});
        spBand.setAdapter(bandAdapter);
        spBand.setSelection(ch.narrow ? 1 : 0);

        ArrayAdapter<String> powerAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"低功率", "中功率", "高功率"});
        spPower.setAdapter(powerAdapter);
        spPower.setSelection(Math.max(0, Math.min(2, ch.power)));

        ArrayAdapter<String> modAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"FM", "AM", "USB", "LSB"});
        spMod.setAdapter(modAdapter);
        spMod.setSelection(Math.max(0, Math.min(3, ch.modulation)));

        cbScramble.setChecked(ch.scramble);
        panelK5.setVisibility(machine == RadioType.UVK5 ? View.VISIBLE : View.GONE);

        builder.setView(view);
        final AlertDialog dialog = builder.create();
        view.findViewById(R.id.btnDialogOk).setOnClickListener(
                new DialogOkClick(this, dialog, position, tones, spQt1, spQt2, spBand,
                        spPower, spMod, cbScramble, etRx, etTx, etName, etHex));
        view.findViewById(R.id.btnDialogCancel).setOnClickListener(new CancelClick(dialog));
        dialog.show();
    }

    private List<String> toneLabels(List<Tone> tones) {
        List<String> labels = new ArrayList<String>();
        for (Tone t : tones) labels.add(t.label());
        return labels;
    }

    private int indexOfTone(List<Tone> tones, Tone t) {
        for (int i = 0; i < tones.size(); i++) {
            if (tones.get(i).sameAs(t)) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 日志 / 进度
    // ------------------------------------------------------------------

    void log(final String msg) {
        ui.post(new LogPost(this, msg));
    }

    void showProgress(final int current, final int total, final String msg) {
        ui.post(new ProgressPost(this, current, total, msg));
    }

    // ==================================================================
    // 静态内部类
    // ==================================================================

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
            act.applyImageStatus(status);
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

    static class ConnectClick implements View.OnClickListener {
        private final MainActivity act;

        ConnectClick(MainActivity a) {
            act = a;
        }

        @Override
        public void onClick(View v) {
            if (act.getSerialPort() != null) act.disconnect();
            else act.requestOpen();
        }
    }

    static class ActionClick implements View.OnClickListener {
        private final MainActivity act;
        private final int what;

        ActionClick(MainActivity a, int what) {
            this.act = a;
            this.what = what;
        }

        @Override
        public void onClick(View v) {
            switch (what) {
                case 0: act.startRead(); break;
                case 1: act.confirmWrite(); break;
                case 2: act.startSave(); break;
                case 3: act.startOpen(); break;
                case 4:
                    HexViewActivity.image = act.getImage();
                    HexViewActivity.title = act.getMachine().displayName + " 原始数据";
                    act.startActivity(new Intent(act, HexViewActivity.class));
                    break;
            }
        }
    }

    static class ItemClick implements AdapterView.OnItemClickListener {
        private final MainActivity act;

        ItemClick(MainActivity a) {
            act = a;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            act.editChannel(position);
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

    static class CancelClick implements View.OnClickListener {
        private final AlertDialog dialog;

        CancelClick(AlertDialog d) {
            dialog = d;
        }

        @Override
        public void onClick(View v) {
            dialog.dismiss();
        }
    }

    static class DialogOkClick implements View.OnClickListener {
        private final MainActivity act;
        private final AlertDialog dialog;
        private final int position;
        private final List<Tone> tones;
        private final Spinner spQt1, spQt2, spBand, spPower, spMod;
        private final CheckBox cbScramble;
        private final EditText etRx, etTx, etName, etHex;

        DialogOkClick(MainActivity a, AlertDialog d, int pos, List<Tone> tones,
                      Spinner qt1, Spinner qt2, Spinner band, Spinner power, Spinner mod,
                      CheckBox scramble, EditText rx, EditText tx, EditText name, EditText hex) {
            this.act = a;
            this.dialog = d;
            this.position = pos;
            this.tones = tones;
            this.spQt1 = qt1;
            this.spQt2 = qt2;
            this.spBand = band;
            this.spPower = power;
            this.spMod = mod;
            this.cbScramble = scramble;
            this.etRx = rx;
            this.etTx = tx;
            this.etName = name;
            this.etHex = hex;
        }

        @Override
        public void onClick(View v) {
            try {
                byte[] raw = HexUtil.hexToBytes(etHex.getText().toString());
                if (raw.length != 32) {
                    Toast.makeText(act, "原始数据必须是 32 字节", Toast.LENGTH_SHORT).show();
                    return;
                }
                UnifiedChannel c = act.getChannels().get(position);

                long rxHz = HexUtil.parseMhz(etRx.getText().toString());
                long txHz = HexUtil.parseMhz(etTx.getText().toString());
                c.rxHz = rxHz > 0 ? rxHz : 0;
                c.txHz = txHz > 0 ? txHz : c.rxHz;
                c.rxTone = pick(spQt1.getSelectedItemPosition());
                c.txTone = pick(spQt2.getSelectedItemPosition());
                c.narrow = spBand.getSelectedItemPosition() == 1;
                c.power = spPower.getSelectedItemPosition();
                c.modulation = spMod.getSelectedItemPosition();
                c.scramble = cbScramble.isChecked();
                c.name = etName.getText().toString().trim();

                // 原始数据回写（保留用户手动修改）
                if (act.getMachine() == RadioType.KT10R) {
                    c.rawKt = raw;
                    // 同步结构化字段进 raw
                    byte[] enc = Kt10rCodec.encode(c);
                    System.arraycopy(raw, 12, enc, 12, 4); // 保留标志区手动修改
                    c.rawKt = enc;
                } else {
                    c.rawK5 = raw;
                    byte[] enc = Uvk5Radio.encodeChannel(c);
                    System.arraycopy(raw, 0x10, enc, 0x10, 2); // 保留步进
                    System.arraycopy(raw, 0x15, enc, 0x15, 11); // 保留扰频及名称区手动修改
                    c.rawK5 = enc;
                }

                act.getChannels().set(position, c);
                act.applyImageStatus("频道已更新");
                act.log("频道 " + (position + 1) + " 已更新");
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(act, "数据错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        private Tone pick(int idx) {
            if (idx < 0 || idx >= tones.size()) return Tone.off();
            return tones.get(idx);
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
                if (act.getMachine() == RadioType.KT10R) {
                    String model = act.getKtLink().handshake();
                    act.setRadioInfo("电台 " + model);
                    act.log("KT-10R 握手成功: " + model);
                    EepromImage img = new EepromImage();
                    int n = act.getKtLink().readAll(img, model);
                    act.setImage(img.data);
                    final List<UnifiedChannel> list = Kt10rCodec.parseChannels(img.data);
                    act.ui.post(new ReadDonePost(act, list, "读取完成，共 " + list.size() + " 块"));
                } else {
                    String ver = act.getK5Radio().init();
                    act.setRadioInfo("UV-K5 " + ver);
                    byte[] img = act.getK5Radio().readAll();
                    act.setImage(img);
                    final List<UnifiedChannel> list = Uvk5Radio.parseChannels(img);
                    act.ui.post(new ReadDonePost(act, list, "读取完成，共 " + list.size() + " 频道"));
                }
            } catch (final Exception e) {
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
                // 若当前机型尚未读频，先读取原机数据，避免把空白镜像写入电台
                if (!act.isImageLoaded()) {
                    act.log("当前机型尚未读取，先读取原机数据以保留设置...");
                    if (act.getMachine() == RadioType.KT10R) {
                        act.getKtLink().handshake();
                        EepromImage img = new EepromImage();
                        act.getKtLink().readAll(img, "");
                        act.setImage(img.data);
                    } else {
                        act.getK5Radio().init();
                        act.setImage(act.getK5Radio().readAll());
                    }
                }
                if (act.getMachine() == RadioType.KT10R) {
                    byte[] img = act.getImage().clone();
                    Kt10rCodec.applyChannels(img, act.getChannels());
                    act.getKtLink().handshake();
                    act.getKtLink().writeAll(com.qytong.rfprog.EepromImage.from(img));
                    act.setImage(img);
                } else {
                    byte[] img = act.getImage().clone();
                    Uvk5Radio.applyChannels(img, act.getChannels());
                    act.getK5Radio().init();
                    act.getK5Radio().writeAll(img);
                    act.getK5Radio().reboot();
                    act.setImage(img);
                }
                act.ui.post(new WriteDonePost(act));
            } catch (final Exception e) {
                act.ui.post(new ErrorPost(act, "写频失败: " + e.getMessage()));
            }
        }
    }
}
