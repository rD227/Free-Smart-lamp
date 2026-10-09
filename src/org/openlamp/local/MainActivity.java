package org.openlamp.local;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Foreground-only local BLE transmitter. No network permission, accounts or analytics. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final List<Button> controls = new ArrayList<>();
    private TextView status;
    private Spinner mode;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback activeCallback;
    private SharedPreferences prefs;
    private int controllerId;
    private int generation;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(246,247,249));
        getWindow().setNavigationBarColor(Color.rgb(246,247,249));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs = getSharedPreferences("controller", MODE_PRIVATE);
        if (!prefs.contains("id")) {
            int id;
            do { id = random.nextInt(0x1000000) & 0xFFFFF0FF; } while (id == 0);
            prefs.edit().putInt("id", id).commit();
        }
        controllerId = prefs.getInt("id", 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246,247,249));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24),dp(48),dp(24),dp(36));
        scroll.addView(layout);
        setContentView(scroll);

        TextView title = label("本地开灯",32,Color.rgb(25,32,45));
        title.setTypeface(null,Typeface.BOLD);
        layout.addView(title);
        TextView subtitle = label("手机直连蓝牙 · 开源 · 离线",16,Color.rgb(91,101,115));
        subtitle.setPadding(0,dp(8),0,dp(28)); layout.addView(subtitle);

        status = label("准备就绪。首次使用请先配对灯。",17,Color.rgb(36,72,98));
        status.setPadding(dp(18),dp(20),dp(18),dp(20));
        status.setBackground(background(Color.rgb(229,239,247)));
        layout.addView(status,new LinearLayout.LayoutParams(-1,dp(116)));

        addButton(layout,"开灯",Color.rgb(36,91,204),()->send(0x10,"开灯"));
        addButton(layout,"关灯",Color.rgb(44,54,70),()->send(0x11,"关灯"));

        TextView pairing = label("首次配对",20,Color.rgb(25,32,45));
        pairing.setTypeface(null,Typeface.BOLD);
        pairing.setPadding(0,dp(30),0,dp(8)); layout.addView(pairing);
        layout.addView(label("用墙壁开关给灯断电、再上电，5 秒内点击下面的按钮。灯闪烁后，尝试开灯或关灯。",16,Color.rgb(91,101,115)));
        addButton(layout,"配对这盏灯",Color.rgb(36,112,91),()->send(0x28,"配对"));

        TextView compatibility = label("兼容模式",16,Color.rgb(91,101,115));
        compatibility.setPadding(0,dp(24),0,dp(6)); layout.addView(compatibility);
        mode = new Spinner(this);
        ArrayAdapter<String> options = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
                new String[]{"自动（依次尝试三种模式）","模式 1","模式 2","模式 3"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(options); mode.setSelection(prefs.getInt("mode",0));
        layout.addView(mode,new LinearLayout.LayoutParams(-1,dp(52)));

        TextView note = label("命令通过本机蓝牙发送，不经过服务器。配对信息只保存在本机；卸载后需重新配对。手机发送成功并不代表灯已执行，请观察灯的反应。",14,Color.rgb(110,119,132));
        note.setPadding(0,dp(24),0,0); layout.addView(note);
        requestBluetoothPermissions();
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31 &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT},1);
        }
    }

    private void send(int command,String action) {
        if (busy) return;
        if (Build.VERSION.SDK_INT>=31 &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED ||
                 checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)) {
            status.setText("请允许附近设备权限，然后再点击按钮。");
            requestBluetoothPermissions(); return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter==null || !adapter.isEnabled()) {
            status.setText("请先在手机设置中打开蓝牙。"); return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser==null) { status.setText("这台手机暂时无法发送蓝牙广播。"); return; }

        int chosen = mode.getSelectedItemPosition();
        int seq = (prefs.getInt("sequence",0)+1)&255;
        prefs.edit().putInt("sequence",seq).putInt("mode",chosen).commit();
        List<AdvertiseData> packets = new ArrayList<>();
        try {
            for (int variant=1; variant<=3; variant++) {
                if (chosen!=0 && chosen!=variant) continue;
                AdvertiseData data = null;
                // Android deduplicates service UUIDs. Regenerate the seed if two encoded words collide.
                for (int attempt=0; attempt<64 && data==null; attempt++) {
                    byte[] payload = LampCodec.encode(variant,command,controllerId,seq,1+random.nextInt(0xFFF5));
                    data = toAdvertisingData(payload);
                }
                if (data==null) throw new IllegalStateException("无法生成广播数据");
                packets.add(data);
            }
        } catch (Exception error) { status.setText("编码失败："+error.getMessage()); return; }

        busy=true; setControls(false); int token=++generation;
        status.setText("正在发送"+action+"指令…");
        startPacket(packets,0,action,token);
    }

    private AdvertiseData toAdvertisingData(byte[] payload) {
        AdvertiseData.Builder builder = new AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false);
        Set<Integer> seen = new HashSet<>();
        for (int i=0; i<payload.length; i+=2) {
            int word = (payload[i]&255)|((payload[i+1]&255)<<8);
            if (!seen.add(word)) return null;
            builder.addServiceUuid(ParcelUuid.fromString(String.format(Locale.ROOT,"%08x-0000-1000-8000-00805f9b34fb",word)));
        }
        return builder.build();
    }

    private void startPacket(List<AdvertiseData> packets,int index,String action,int token) {
        if (token!=generation) return;
        if (index==packets.size()) {
            busy=false; setControls(true);
            status.setText("已发送"+action+"指令。\n请确认灯是否响应。"+
                    (action.equals("配对") ? "没有响应时，重新上电后尝试其他兼容模式。" : ""));
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false).setTimeout(2000).build();
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings ignored) {
                if (token!=generation) return;
                Log.i("OpenLamp","Advertising started: "+action+", packet "+index);
                handler.postDelayed(()->{
                    if (token!=generation) return;
                    stopAdvertisement();
                    handler.postDelayed(()->startPacket(packets,index+1,action,token),120);
                },650);
            }
            @Override public void onStartFailure(int code) {
                if (token!=generation) return;
                cancelSending();
                status.setText("蓝牙发送失败（"+code+"）。请关闭其他蓝牙控制 App 后重试。");
                Log.e("OpenLamp","Advertising failed: "+code);
            }
        };
        activeCallback=callback;
        try { advertiser.startAdvertising(settings,packets.get(index),callback); }
        catch (RuntimeException error) {
            cancelSending(); status.setText("无法发送："+error.getMessage());
        }
    }

    private void stopAdvertisement() {
        if (advertiser!=null && activeCallback!=null) {
            try { advertiser.stopAdvertising(activeCallback); } catch (RuntimeException ignored) {}
            activeCallback=null;
        }
    }
    private void cancelSending() {
        ++generation; handler.removeCallbacksAndMessages(null); stopAdvertisement(); busy=false;
        setControls(true);
    }
    @Override public void onPause() {
        super.onPause();
        if (busy) { cancelSending(); status.setText("发送已暂停，请回到页面后重试。"); }
    }
    private void setControls(boolean enabled) {
        for (Button button : controls) button.setEnabled(enabled);
        if (mode!=null) mode.setEnabled(enabled);
    }
    private void addButton(LinearLayout parent,String text,int color,Runnable action) {
        Button button=new Button(this); button.setText(text); button.setTextSize(20);
        button.setAllCaps(false); button.setTextColor(Color.WHITE); button.setBackground(background(color));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(64)); params.topMargin=dp(16);
        parent.addView(button,params); button.setOnClickListener(v->action.run()); controls.add(button);
    }
    private TextView label(String text,int size,int color) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3),1); return view;
    }
    private GradientDrawable background(int color) {
        GradientDrawable bg=new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(16)); return bg;
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
