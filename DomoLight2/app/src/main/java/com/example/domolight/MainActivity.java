package com.example.domolight;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DomoLight";
    private static final String DEVICE_NAME = "domo_p";
    private static final UUID SERVICE_UUID = UUID.fromString("f364abab-00b0-4240-ba50-05ca45bf8abc");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("f364abeb-00b0-4240-ba50-05ca45bf8abc");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic targetCharacteristic;

    private boolean isConnected = false;
    private boolean notificationsEnabled = false;
    private boolean isScanning = false;

    private Button btnConnect, btnToggleLight;
    private TextView tvStatus, tvNotifStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = !result.containsValue(false);
            if (allGranted) startScan();
            else updateStatus("Permissions refusées");
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect     = findViewById(R.id.btnConnect);
        btnToggleLight = findViewById(R.id.btnToggleLight);
        tvStatus       = findViewById(R.id.tvStatus);
        tvNotifStatus  = findViewById(R.id.tvNotifStatus);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bm.getAdapter();

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) requestPermissionsAndScan();
            else disconnect();
        });
        btnToggleLight.setOnClickListener(v -> toggleNotifications());
        btnToggleLight.setEnabled(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    private String btPerm() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? Manifest.permission.BLUETOOTH_CONNECT
            : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private String scanPerm() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? Manifest.permission.BLUETOOTH_SCAN
            : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void requestPermissionsAndScan() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        boolean allGranted = true;
        for (String p : perms)
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                allGranted = false;
        if (allGranted) startScan();
        else permissionLauncher.launch(perms.toArray(new String[0]));
    }

    private void startScan() {
        if (isScanning) return;
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        isScanning = true;
        updateStatus("Recherche de " + DEVICE_NAME + "...");
        btnConnect.setText("Annuler");
        if (ActivityCompat.checkSelfPermission(this, scanPerm()) == PackageManager.PERMISSION_GRANTED)
            bleScanner.startScan(scanCallback);
        mainHandler.postDelayed(() -> {
            if (isScanning) { stopScan(); if (!isConnected) { updateStatus("Introuvable"); btnConnect.setText("Connecter"); } }
        }, 10000);
    }

    private void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        if (ActivityCompat.checkSelfPermission(this, scanPerm()) == PackageManager.PERMISSION_GRANTED)
            bleScanner.stopScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this, btPerm()) != PackageManager.PERMISSION_GRANTED) return;
            BluetoothDevice device = result.getDevice();
            if (DEVICE_NAME.equals(device.getName())) {
                stopScan();
                updateStatus("Trouvé, connexion...");
                bluetoothGatt = device.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                mainHandler.post(() -> { updateStatus("Connecté !"); btnConnect.setText("Déconnecter"); });
                if (ActivityCompat.checkSelfPermission(MainActivity.this, btPerm()) == PackageManager.PERMISSION_GRANTED)
                    gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                notificationsEnabled = false;
                targetCharacteristic = null;
                mainHandler.post(() -> {
                    updateStatus("Déconnecté");
                    btnConnect.setText("Connecter");
                    btnToggleLight.setEnabled(false);
                    btnToggleLight.setText("Activer notifications");
                    tvNotifStatus.setText("Notifications : inactives");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) { mainHandler.post(() -> updateStatus("Service introuvable")); return; }
            targetCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
            if (targetCharacteristic == null) { mainHandler.post(() -> updateStatus("Caractéristique introuvable")); return; }
            mainHandler.post(() -> { updateStatus("Prêt !"); btnToggleLight.setEnabled(true); });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            mainHandler.post(() -> {
                if (notificationsEnabled) {
                    tvNotifStatus.setText("Notifications : ✅ actives");
                    btnToggleLight.setText("Désactiver notifications");
                } else {
                    tvNotifStatus.setText("Notifications : ❌ inactives");
                    btnToggleLight.setText("Activer notifications");
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "Notification reçue");
        }
    };

    private void toggleNotifications() {
        if (bluetoothGatt == null || targetCharacteristic == null) return;
        notificationsEnabled = !notificationsEnabled;
        if (ActivityCompat.checkSelfPermission(this, btPerm()) != PackageManager.PERMISSION_GRANTED) return;
        bluetoothGatt.setCharacteristicNotification(targetCharacteristic, notificationsEnabled);
        BluetoothGattDescriptor descriptor = targetCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(notificationsEnabled
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
        }
    }

    private void disconnect() {
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, btPerm()) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            }
            bluetoothGatt = null;
        }
    }

    private void updateStatus(String msg) {
        tvStatus.setText("État : " + msg);
    }
}
