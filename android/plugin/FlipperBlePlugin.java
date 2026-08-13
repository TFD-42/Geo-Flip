package com.mhzlocalise.rftriangulator;

/*
 * FlipperBlePlugin
 * ----------------
 * Capacitor native plugin that bridges the WebView UI to the Flipper Zero's
 * BLE Serial GATT service. Mirrors FlipperSerialPlugin's JS API exactly so
 * app.js can treat USB and BLE as interchangeable transports:
 *
 *   FlipperBle.scan()          -> { devices: [{name, address, rssi}] }
 *   FlipperBle.connect()       -> { connected: true, deviceName }
 *        optional { address }     (no address: scans and picks the
 *                                  strongest device named "Flipper*")
 *   FlipperBle.disconnect()    -> { connected: false }
 *   FlipperBle.isConnected()   -> { connected: boolean }
 *   addListener('data', cb)    -> emits { line } per newline-terminated line
 *   addListener('status', cb)  -> emits { state: "connected"|"disconnected"|"error", message? }
 *
 * Uses only android.bluetooth framework APIs — no extra Gradle dependency.
 *
 * NOTES
 * - The Flipper's BLE Serial service is the same GATT service the stock
 *   firmware uses for CLI-over-BLE. Service UUID below is from the official
 *   firmware (ble_glue serial_service). TX/RX characteristic direction is
 *   resolved AT RUNTIME by property (NOTIFY = flipper->phone stream) rather
 *   than by hardcoded char UUID, so a firmware-side UUID reshuffle cannot
 *   silently break us.
 * - The serial characteristics are encrypted: the first connect triggers
 *   Android's system pairing dialog. A bond-state receiver retries
 *   notification setup once bonding completes.
 * - IMPORTANT: with stock firmware, this link carries the Flipper CLI, not
 *   the rf_logger CSV. Firmware-side BLE output from rf_logger.fap is a
 *   separate change (see SETUP.md, "BLE support").
 */

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@CapacitorPlugin(
        name = "FlipperBle",
        permissions = {
                @Permission(alias = "bluetooth", strings = {
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                }),
                // Pre-Android-12 BLE scanning requires location
                @Permission(alias = "location", strings = {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                }),
        })
public class FlipperBlePlugin extends Plugin {

    private static final String TAG = "FlipperBle";

    /* Flipper Zero BLE Serial service (flipperzero-firmware ble_glue,
     * serial_service_uuid.inc). The TX characteristic carries the
     * peripheral->central data stream and uses INDICATE; the flow-control
     * characteristic uses NOTIFY — subscribing to the wrong one yields a
     * live connection with zero data, so we target TX explicitly. */
    /* BLE 128-bit UUIDs are little-endian on the wire, so the string form is
     * the firmware byte array REVERSED. Confirmed against the service list
     * Android actually discovers on the device. */
    private static final UUID SERIAL_SERVICE_UUID =
            UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000");
    private static final UUID SERIAL_TX_CHAR_UUID = // Flipper -> phone (indicate)
            UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String NAME_PREFIX = "Flipper";
    private static final long SCAN_WINDOW_MS = 4000;
    private static final long RECONNECT_DELAY_MS = 2000; // matches USB plugin behavior
    private static final int REQUEST_MTU = 247;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder lineBuf = new StringBuilder(256);

    private BluetoothGatt gatt;
    private BluetoothDevice targetDevice;
    private BluetoothGattCharacteristic notifyChar;
    private boolean streamReady = false;
    private boolean userDisconnect = false;
    private PluginCall pendingConnect;
    private int failStreak = 0; // connects that dropped before the stream started

    /* The Flipper serial service is encrypted and several Android stacks
     * will NOT auto-pair on an encrypted CCCD write — so we bond explicitly
     * before opening GATT. This receiver drives the flow: bond lands ->
     * connect (or retry notifications); pairing rejected -> fail loudly. */
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null || targetDevice == null
                    || !dev.getAddress().equals(targetDevice.getAddress())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            int prev  = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
            if (state == BluetoothDevice.BOND_BONDED) {
                if (gatt == null) {
                    try {
                        gatt = targetDevice.connectGatt(getContext(), false, gattCallback);
                    } catch (SecurityException ignored) {}
                } else if (!streamReady) {
                    enableNotifications();
                }
            } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                failConnect("Pairing was rejected or canceled. Retry and accept the pairing "
                        + "dialog on BOTH the phone and the Flipper screen.");
            }
        }
    };

    @Override
    public void load() {
        getContext().registerReceiver(bondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    @Override
    protected void handleOnDestroy() {
        try { getContext().unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
        userDisconnect = true;
        closeGatt();
    }

    /* ---------------- JS-exposed methods ---------------- */

    @PluginMethod
    public void scan(PluginCall call) {
        if (!ensurePermissions(call, "scanPermCallback")) return;
        doScan(call, null);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (!ensurePermissions(call, "connectPermCallback")) return;
        doConnect(call);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        userDisconnect = true;
        closeGatt();
        emitStatus("disconnected", null);
        JSObject ret = new JSObject();
        ret.put("connected", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("connected", streamReady);
        call.resolve(ret);
    }

    /* ---------------- permissions ---------------- */

    private boolean ensurePermissions(PluginCall call, String callbackName) {
        if (getPermissionState("bluetooth") == PermissionState.GRANTED
                && getPermissionState("location") == PermissionState.GRANTED) {
            return true;
        }
        requestPermissionForAliases(new String[]{"bluetooth", "location"}, call, callbackName);
        return false;
    }

    @PermissionCallback
    private void scanPermCallback(PluginCall call) {
        if (getPermissionState("bluetooth") == PermissionState.GRANTED) doScan(call, null);
        else call.reject("Bluetooth permission denied");
    }

    @PermissionCallback
    private void connectPermCallback(PluginCall call) {
        if (getPermissionState("bluetooth") == PermissionState.GRANTED) doConnect(call);
        else call.reject("Bluetooth permission denied");
    }

    /* ---------------- scan ---------------- */

    private interface ScanDone { void onDone(Map<String, ScanResult> found, java.util.Set<String> seenNames); }

    private void doScan(PluginCall call, ScanDone chain) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("Bluetooth is off");
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { call.reject("BLE scanner unavailable"); return; }

        final Map<String, ScanResult> found = new HashMap<>();  // likely Flippers
        final Map<String, ScanResult> all   = new HashMap<>();  // every advertiser seen
        final java.util.Set<String> seenNames = new java.util.HashSet<>();
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                String addr = result.getDevice().getAddress();
                String name = advertisedName(result);
                seenNames.add(name != null ? name : addr);
                ScanResult prevAll = all.get(addr);
                if (prevAll == null || result.getRssi() > prevAll.getRssi()) all.put(addr, result);
                /* "Likely Flipper": name prefix (default-named units) OR the
                 * serial service UUID in the adv. Custom-named Flippers
                 * advertise the bare name (e.g. "MyFlipper") — prefix matching
                 * alone misses them, hence the picker fallback in the UI. */
                boolean matches = name != null
                        && name.regionMatches(true, 0, NAME_PREFIX, 0, NAME_PREFIX.length());
                if (!matches && result.getScanRecord() != null
                        && result.getScanRecord().getServiceUuids() != null) {
                    matches = result.getScanRecord().getServiceUuids()
                            .contains(new ParcelUuid(SERIAL_SERVICE_UUID));
                }
                if (!matches) return;
                ScanResult prev = found.get(addr);
                if (prev == null || result.getRssi() > prev.getRssi()) {
                    found.put(addr, result);
                }
            }
        };

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, cb);
        } catch (SecurityException e) {
            call.reject("Scan not permitted: " + e.getMessage());
            return;
        }

        handler.postDelayed(() -> {
            try { scanner.stopScan(cb); } catch (Exception ignored) {}
            if (chain != null) { chain.onDone(found, seenNames); return; }
            /* Listing mode returns EVERY advertiser so the UI can offer a
             * manual picker when auto-match fails (custom-named Flippers). */
            JSONArray arr = new JSONArray();
            for (ScanResult r : all.values()) {
                try {
                    String addr = r.getDevice().getAddress();
                    JSONObject o = new JSONObject();
                    o.put("name", advertisedName(r));
                    o.put("address", addr);
                    o.put("rssi", r.getRssi());
                    o.put("likelyFlipper", found.containsKey(addr));
                    arr.put(o);
                } catch (Exception ignored) {}
            }
            JSObject ret = new JSObject();
            ret.put("devices", arr);
            call.resolve(ret);
        }, SCAN_WINDOW_MS);
    }

    /* ---------------- connect ---------------- */

    private void doConnect(PluginCall call) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("Bluetooth is off");
            return;
        }
        String address = call.getString("address");
        if (address != null && !address.isEmpty()) {
            try {
                openGatt(adapter.getRemoteDevice(address), call);
            } catch (IllegalArgumentException e) {
                call.reject("Bad BLE address: " + address);
            }
            return;
        }
        // No address given: scan and pick the strongest "Flipper*" advertiser.
        doScan(call, (found, seenNames) -> {
            ScanResult best = null;
            for (ScanResult r : found.values()) {
                if (best == null || r.getRssi() > best.getRssi()) best = r;
            }
            if (best == null) {
                String msg = "No Flipper found over BLE. Is Bluetooth enabled on the Flipper (Settings > Bluetooth)?";
                if (!seenNames.isEmpty()) msg += " Nearby BLE devices seen: " + seenNames;
                call.reject(msg);
                return;
            }
            openGatt(best.getDevice(), call);
        });
    }

    private void openGatt(BluetoothDevice device, PluginCall call) {
        closeGatt();
        userDisconnect = false;
        failStreak = 0;
        targetDevice = device;
        pendingConnect = call;
        try {
            /* A stuck half-bond (e.g. from an aborted classic-PIN attempt)
             * blocks LE pairing — cancel it before connecting. */
            if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
                try { device.getClass().getMethod("cancelBondProcess").invoke(device); }
                catch (Exception ignored) {}
            }
            /* ALWAYS force the LE transport. With TRANSPORT_AUTO an
             * address-constructed device has unknown type, so Android
             * attempts a classic BR/EDR bond -> legacy PIN dialog -> dead
             * end (the Flipper is LE-only). Bonding is initiated lazily
             * over the LE link instead — the Flipper sends an SMP Security
             * Request on connect, and the descriptor-write fallback below
             * covers stacks that ignore it. */
            gatt = device.connectGatt(getContext(), false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            pendingConnect = null;
            call.reject("Connect not permitted: " + e.getMessage());
            return;
        }
        final PluginCall thisCall = call;
        handler.postDelayed(() -> {
            if (pendingConnect == thisCall && !streamReady) {
                failConnect("Connect/pairing timed out. If a pairing dialog appeared, "
                        + "accept it on both devices and retry.");
            }
        }, 35000);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                /* Clear Android's cached GATT table before discovery. Across
                 * our earlier connects/re-pairs/re-flashes Android cached a
                 * stale service list (only 0x1801 Generic Attribute), so the
                 * Flipper serial service 0xfe60 was invisible. refresh() is a
                 * hidden API — reflection is the only way to reach it. */
                refreshGattCache(g);
                handler.postDelayed(() -> {
                    if (gatt != g) return;
                    if (!g.requestMtu(REQUEST_MTU)) g.discoverServices();
                }, 600);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasReady = streamReady;
                streamReady = false;
                notifyChar = null;
                try { g.close(); } catch (Exception ignored) {}
                if (gatt == g) gatt = null;
                if (userDisconnect) return;
                if (wasReady) {
                    failStreak = 0;
                    emitStatus("disconnected", null);
                } else {
                    /* Dropped before the stream ever started. A stale bond
                     * (old keys from the official Flipper app) looks exactly
                     * like this: instant drop, every time. */
                    failStreak++;
                    if (failStreak >= 3) {
                        targetDevice = null; // stop the reconnect loop
                        failConnect("BLE drops before the stream starts — pairing is likely stale. "
                                + "Forget 'Flipper' in Android Bluetooth settings AND on the Flipper "
                                + "(Settings > Bluetooth > Forget All Paired Devices), then reconnect.");
                        return;
                    }
                }
                // Same auto-reconnect contract as the USB plugin: retry every 2 s
                if (targetDevice != null) {
                    handler.postDelayed(() -> {
                        if (!userDisconnect && gatt == null && targetDevice != null) {
                            try {
                                gatt = targetDevice.connectGatt(getContext(), false, gattCallback);
                            } catch (SecurityException ignored) {}
                        }
                    }, RECONNECT_DELAY_MS);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect("Service discovery failed: " + status);
                return;
            }
            StringBuilder svcList = new StringBuilder();
            for (BluetoothGattService s : g.getServices()) svcList.append(s.getUuid()).append(' ');
            Log.i(TAG, "discovered services: " + svcList);
            BluetoothGattService svc = g.getService(SERIAL_SERVICE_UUID);
            if (svc == null) {
                // UUID-drift fallback: any service exposing the TX char, else
                // any service with an INDICATE/NOTIFY characteristic.
                for (BluetoothGattService s : g.getServices()) {
                    if (s.getCharacteristic(SERIAL_TX_CHAR_UUID) != null) { svc = s; break; }
                }
                if (svc == null) {
                    for (BluetoothGattService s : g.getServices()) {
                        if (pickStreamChar(s) != null) { svc = s; break; }
                    }
                }
            }
            if (svc == null) {
                failConnect("No serial service on device");
                return;
            }
            /* Prefer the exact TX characteristic; fall back to any char that
             * can push data (INDICATE preferred, then NOTIFY). The flow-control
             * char is NOTIFY-only and must NOT be chosen when TX exists. */
            notifyChar = svc.getCharacteristic(SERIAL_TX_CHAR_UUID);
            if (notifyChar == null) notifyChar = pickStreamChar(svc);
            if (notifyChar == null) {
                failConnect("No data (TX) characteristic in serial service");
                return;
            }
            Log.i(TAG, "svc=" + svc.getUuid() + " txChar=" + notifyChar.getUuid()
                    + " props=0x" + Integer.toHexString(notifyChar.getProperties()));
            enableNotifications();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "onDescriptorWrite status=" + status + " (0=OK) uuid=" + d.getUuid());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                streamReady = true;
                failStreak = 0;
                String name = safeName(targetDevice);
                emitStatus("connected", name);
                if (pendingConnect != null) {
                    JSObject ret = new JSObject();
                    ret.put("connected", true);
                    ret.put("deviceName", name);
                    pendingConnect.resolve(ret);
                    pendingConnect = null;
                }
            } else {
                /* Encrypted characteristic: the write fails until bonded.
                 * Kick off bonding explicitly — some stacks never do it
                 * on their own. bondReceiver retries once bonded. */
                try {
                    if (targetDevice != null
                            && targetDevice.getBondState() == BluetoothDevice.BOND_NONE) {
                        emitStatus("pairing", safeName(targetDevice));
                        targetDevice.createBond();
                    }
                } catch (SecurityException ignored) {}
            }
        }

        /* API 33+ (Android 13+): the value arrives as a parameter here.
         * On API 36 the deprecated no-value overload below gets a null/stale
         * getValue(), so this overload is what actually delivers data. */
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null && value.length > 0) feedBytes(value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] data = c.getValue();
            if (data != null && data.length > 0) feedBytes(data);
        }
    };

    /* The data characteristic that pushes bytes to us: TX uses INDICATE,
     * flow-control uses NOTIFY. Prefer INDICATE so we never bind flow-control
     * by accident; fall back to NOTIFY for firmware that reshuffles this. */
    private static BluetoothGattCharacteristic pickStreamChar(BluetoothGattService svc) {
        BluetoothGattCharacteristic notify = null;
        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
            int p = c.getProperties();
            if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) return c;
            if (notify == null && (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) notify = c;
        }
        return notify;
    }

    private void enableNotifications() {
        if (gatt == null || notifyChar == null) return;
        try {
            gatt.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                // Match the descriptor value to the characteristic's mode:
                // TX is INDICATE, so enabling NOTIFICATION on it would silently
                // deliver nothing — the exact failure we just diagnosed.
                boolean indicate = (notifyChar.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                cccd.setValue(indicate
                        ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(cccd);
            } else {
                failConnect("Data characteristic has no CCCD descriptor");
            }
        } catch (SecurityException e) {
            failConnect("Notification setup not permitted: " + e.getMessage());
        }
    }

    /* Same newline framing as FlipperSerialPlugin.onNewData — BLE notify
     * packets fragment lines arbitrarily, the buffer reassembles them. */
    private void feedBytes(byte[] data) {
        for (byte b : data) {
            char ch = (char) (b & 0xFF);
            if (ch == '\n') {
                String line = lineBuf.toString().trim();
                lineBuf.setLength(0);
                if (!line.isEmpty()) {
                    JSObject o = new JSObject();
                    o.put("line", line);
                    notifyListeners("data", o);
                }
            } else if (ch != '\r') {
                lineBuf.append(ch);
                if (lineBuf.length() > 1024) lineBuf.setLength(0); /* runaway guard */
            }
        }
    }

    /* ---------------- helpers ---------------- */

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /* Hidden BluetoothGatt.refresh() — forces the platform to drop its cached
     * service table and re-read it from the device on the next discovery. */
    private static void refreshGattCache(BluetoothGatt g) {
        try {
            java.lang.reflect.Method m = g.getClass().getMethod("refresh");
            Object ok = m.invoke(g);
            Log.i(TAG, "gatt refresh() -> " + ok);
        } catch (Exception e) {
            Log.w(TAG, "gatt refresh() unavailable: " + e);
        }
    }

    /* device.getName() is often null for unbonded devices mid-scan; the
     * advertised name from the scan record is the reliable source. */
    private static String advertisedName(ScanResult r) {
        String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
        if (n == null || n.isEmpty()) {
            try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
        }
        return n;
    }

    private String safeName(BluetoothDevice d) {
        try { return d != null && d.getName() != null ? d.getName() : "Flipper"; }
        catch (SecurityException e) { return "Flipper"; }
    }

    private void failConnect(String msg) {
        Log.w(TAG, msg);
        emitStatus("error", msg);
        if (pendingConnect != null) { pendingConnect.reject(msg); pendingConnect = null; }
        closeGatt();
    }

    private synchronized void closeGatt() {
        streamReady = false;
        notifyChar = null;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        lineBuf.setLength(0);
    }

    private void emitStatus(String state, String message) {
        JSObject o = new JSObject();
        o.put("state", state);
        if (message != null) o.put("message", message);
        notifyListeners("status", o);
    }
}
