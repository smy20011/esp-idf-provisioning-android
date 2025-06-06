// Copyright 2020 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ui.activities;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.espressif.AppConstants;
import com.espressif.provisioning.DeviceConnectionEvent;
import com.espressif.provisioning.ESPConstants;
import com.espressif.provisioning.ESPDevice;
import com.espressif.provisioning.ESPProvisionManager;
import com.espressif.provisioning.listeners.QRCodeScanListener;
import com.espressif.wifi_provisioning.R;
import com.google.android.material.card.MaterialCardView;
import com.wang.avi.AVLoadingIndicatorView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class AddDeviceActivity extends AppCompatActivity {

    private static final String TAG = AddDeviceActivity.class.getSimpleName();

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_ACCESS_FINE_LOCATION = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    private TextView tvTitle, tvBack, tvCancel;
    private SharedPreferences sharedPreferences;

    private ESPDevice espDevice;
    private ESPProvisionManager provisionManager;

    //    private CameraSourcePreview cameraPreview;
    private CodeScanner codeScanner;
    private MaterialCardView btnAddManually, btnGetPermission;
    private TextView txtAddManuallyBtn;
    private LinearLayout layoutQrCode, layoutPermissionErr;
    private TextView tvPermissionErr;
    private ImageView ivPermissionErr;
    private AVLoadingIndicatorView loader;

    private boolean isQrCodeDataReceived = false;
    private Intent intent;
    private boolean buttonClicked = false;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_device);
        intent = new Intent();
        sharedPreferences = getSharedPreferences(AppConstants.ESP_PREFERENCES, Context.MODE_PRIVATE);
        provisionManager = ESPProvisionManager.getInstance(getApplicationContext());
        initViews();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionErrorText();

        if (areAllPermissionsGranted()) {
            layoutQrCode.setVisibility(View.VISIBLE);
            layoutPermissionErr.setVisibility(View.GONE);
            btnGetPermission.setVisibility(View.GONE);

            openCamera();

        } else {
            layoutQrCode.setVisibility(View.GONE);
            layoutPermissionErr.setVisibility(View.VISIBLE);
            btnGetPermission.setVisibility(View.VISIBLE);
        }

        // This condition is to get event of cancel button of "try again" popup. Because Android 10 is not giving event on cancel button click if network is not found.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && espDevice != null && espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_SOFTAP)) {

            String ssid = getWifiSsid();
            Log.d(TAG, "Currently connected WiFi SSID : " + ssid);
            Log.d(TAG, "Device Name  : " + espDevice.getDeviceName());
            if (!TextUtils.isEmpty(ssid) && !ssid.equals(espDevice.getDeviceName())) {
                Log.e(TAG, "Device is not connected");
                finish();
            }
        }
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
//        if (cameraPreview != null) {
//            cameraPreview.stop();
//        }
        if (codeScanner != null) {
            codeScanner.stopPreview();
        }
    }

    @Override
    protected void onDestroy() {

        hideLoading();
        EventBus.getDefault().unregister(this);
//        if (cameraPreview != null) {
//            cameraPreview.release();
//        }
        if (codeScanner != null) {
            codeScanner.releaseResources();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {

        if (provisionManager.getEspDevice() != null) {
            provisionManager.getEspDevice().disconnectDevice();
        }
        super.onBackPressed();
    }

    private boolean areAllPermissionsGranted() {
        boolean cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean locationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean bluetoothScanPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean bluetoothConnectPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            return cameraPermission && locationPermission && bluetoothScanPermission && bluetoothConnectPermission;
        } else {
            return cameraPermission && locationPermission;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionErrorText();
        Log.e(TAG, "onRequestPermissionsResult , requestCode : " + requestCode);

        if (areAllPermissionsGranted()) {
            layoutQrCode.setVisibility(View.VISIBLE);
            layoutPermissionErr.setVisibility(View.GONE);
            btnGetPermission.setVisibility(View.GONE);
            openCamera();
        } else {
            findViewById(R.id.scanner_view).setVisibility(View.GONE);
            layoutQrCode.setVisibility(View.GONE);
            layoutPermissionErr.setVisibility(View.VISIBLE);
            btnGetPermission.setVisibility(View.VISIBLE);

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                tvPermissionErr.setText(R.string.error_camera_permission);
                ivPermissionErr.setImageResource(R.drawable.ic_no_camera_permission);
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                tvPermissionErr.setText(R.string.error_location_permission);
                ivPermissionErr.setImageResource(R.drawable.ic_no_location_permission);
                showLocationPermissionAlertDialog();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                            || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                tvPermissionErr.setText(R.string.error_nearby_devices_permission);
                ivPermissionErr.setImageResource(R.drawable.ic_no_bluetooth_permission);
                showLocationPermissionAlertDialog();
            } else {
                tvPermissionErr.setText("");
                ivPermissionErr.setImageDrawable(null);
            }
        }
    }


    private void updatePermissionErrorText() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            tvPermissionErr.setText(R.string.error_camera_permission);
            ivPermissionErr.setImageResource(R.drawable.ic_no_camera_permission);
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tvPermissionErr.setText(R.string.error_location_permission);
            ivPermissionErr.setImageResource(R.drawable.ic_no_location_permission);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            tvPermissionErr.setText(R.string.error_nearby_devices_permission);
            ivPermissionErr.setImageResource(R.drawable.ic_no_bluetooth_permission);
        } else {
            tvPermissionErr.setText("");
            ivPermissionErr.setImageDrawable(null);
        }
    }

    private void navigateToAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            startProvisioningFlow();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(DeviceConnectionEvent event) {

        Log.d(TAG, "On Device Connection Event RECEIVED : " + event.getEventType());

        switch (event.getEventType()) {

            case ESPConstants.EVENT_DEVICE_CONNECTED:

                Log.d(TAG, "Device Connected Event Received");
                setSecurityTypeFromVersionInfo();
                break;

            case ESPConstants.EVENT_DEVICE_DISCONNECTED:

                if (espDevice != null && espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_BLE)) {

                    Toast.makeText(AddDeviceActivity.this, "Device disconnected", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    if (!isFinishing()) {
                        askForManualDeviceConnection();
                    }
                }
                break;

            case ESPConstants.EVENT_DEVICE_CONNECTION_FAILED:

                if (espDevice != null && espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_BLE)) {
                    alertForDeviceNotSupported("Failed to connect with device");
                } else {
                    if (!isFinishing()) {
                        askForManualDeviceConnection();
                    }
                }
                break;
        }
    }

    View.OnClickListener btnAddManuallyClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {

            String deviceType = sharedPreferences.getString(AppConstants.KEY_DEVICE_TYPES, AppConstants.DEVICE_TYPE_DEFAULT);

            if (deviceType.equals(AppConstants.DEVICE_TYPE_BLE) || deviceType.equals(AppConstants.DEVICE_TYPE_BOTH)) {

                final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter bleAdapter = bluetoothManager.getAdapter();

                if (!bleAdapter.isEnabled()) {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                        } else {
                            Log.e(TAG, "BLUETOOTH_CONNECT permission is not granted.");
                            return;
                        }
                    } else {
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                    }

                } else {
                    startProvisioningFlow();
                }
            } else {
                startProvisioningFlow();
            }
        }
    };

    private View.OnClickListener cancelBtnClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {

            if (provisionManager.getEspDevice() != null) {
                provisionManager.getEspDevice().disconnectDevice();
            }
            setResult(RESULT_CANCELED, intent);
            finish();
        }
    };

    private View.OnClickListener btnGetPermissionClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            buttonClicked = true;

            if (ContextCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

                if (ActivityCompat.shouldShowRequestPermissionRationale(AddDeviceActivity.this, Manifest.permission.CAMERA)) {
                    showCameraPermissionExplanation();
                } else {
                    requestCameraPermission();
                }

            } else {

                if (ContextCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                    if (ActivityCompat.shouldShowRequestPermissionRationale(AddDeviceActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                        showLocationAndBluetoothPermissionExplanation(false);
                    } else {
                        requestLocationAndBluetoothPermission();
                    }
                }

                else {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                        if (ContextCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                                || ContextCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

                            if (ActivityCompat.shouldShowRequestPermissionRationale(AddDeviceActivity.this, Manifest.permission.BLUETOOTH_SCAN)
                                    || ActivityCompat.shouldShowRequestPermissionRationale(AddDeviceActivity.this, Manifest.permission.BLUETOOTH_CONNECT)) {

                                showLocationAndBluetoothPermissionExplanation(true);

                            } else {
                                requestLocationAndBluetoothPermission();
                            }
                        }
                    }
                }
            }
        }
    };

    private void showCameraPermissionExplanation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);

        builder.setTitle(R.string.dialog_title_camera_permission);
        builder.setMessage(R.string.dialog_msg_camera_permission_use);
        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

                requestCameraPermission();
                dialog.dismiss();
            }
        });

        builder.setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

                dialog.dismiss();
                finish();
            }
        });

        builder.show();
    }

    private void showLocationAndBluetoothPermissionExplanation(boolean includeBtPermission) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);

        if (includeBtPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                builder.setTitle(R.string.dialog_title_location_permission);
                builder.setMessage(R.string.dialog_msg_location_permission_use);
            } else {
                builder.setTitle(R.string.dialog_title_nearby_devices_permission);
                builder.setMessage(R.string.dialog_msg_nearby_devices_permission_use);
            }
        } else {
            builder.setTitle(R.string.dialog_title_location_permission);
            builder.setMessage(R.string.dialog_msg_location_permission_use);
        }

        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                requestLocationAndBluetoothPermission();
                dialog.dismiss();
            }
        });

        builder.setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                finish();
            }
        });

        builder.show();
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    private void requestLocationAndBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new
                    String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_ACCESS_FINE_LOCATION);
        } else {
            ActivityCompat.requestPermissions(this, new
                    String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_ACCESS_FINE_LOCATION);
        }
    }

    private void showLocationPermissionAlertDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);

        int messageResId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                messageResId = R.string.error_location_permission;
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                messageResId = R.string.error_nearby_devices_permission;
            } else {
                messageResId = R.string.error_location_permission;
            }
        } else {
            messageResId = R.string.error_location_permission;
        }

        builder.setMessage(messageResId);

        builder.setPositiveButton(R.string.action_settings, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                navigateToAppSettings();
            }
        });

        builder.setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        AlertDialog alertDialog = builder.create();
        if (!isFinishing()) {
            alertDialog.show();
        }
    }

    private void initViews() {

        tvTitle = findViewById(R.id.main_toolbar_title);
        tvBack = findViewById(R.id.btn_back);
        tvCancel = findViewById(R.id.btn_cancel);

        tvTitle.setText(R.string.title_activity_add_device);
        tvBack.setVisibility(View.GONE);
        tvCancel.setVisibility(View.VISIBLE);
        tvCancel.setOnClickListener(cancelBtnClickListener);

        CodeScannerView scannerView = findViewById(R.id.scanner_view);
        codeScanner = new CodeScanner(this, scannerView);

//        cameraPreview = findViewById(R.id.preview);
        loader = findViewById(R.id.loader);
        layoutQrCode = findViewById(R.id.layout_qr_code_txt);
        layoutPermissionErr = findViewById(R.id.layout_permission_error);
        tvPermissionErr = findViewById(R.id.tv_permission_error);
        ivPermissionErr = findViewById(R.id.iv_permission_error);

        btnAddManually = findViewById(R.id.btn_add_device_manually);
        txtAddManuallyBtn = btnAddManually.findViewById(R.id.text_btn);
        txtAddManuallyBtn.setText(R.string.btn_no_qr_code);
        btnAddManually.setOnClickListener(btnAddManuallyClickListener);

        btnGetPermission = findViewById(R.id.btn_get_permission);
        TextView btnPermissionText = btnGetPermission.findViewById(R.id.text_btn);
        btnPermissionText.setText(R.string.btn_get_permission);
        btnGetPermission.setOnClickListener(btnGetPermissionClickListener);

        if (ActivityCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            ActivityCompat.requestPermissions(AddDeviceActivity.this, new
                    String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void openCamera() {
        findViewById(R.id.scanner_view).setVisibility(View.VISIBLE);
        if (codeScanner != null) {
            codeScanner.startPreview();
        }
        scanQrCode();
    }

    private void scanQrCode() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                provisionManager.scanQRCode(codeScanner, qrCodeScanListener);
            } else {
                checkLocationAndBluetoothPermission();
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                provisionManager.scanQRCode(codeScanner, qrCodeScanListener);
            } else {
                checkLocationAndBluetoothPermission();
            }
        }
    }

    private void checkLocationAndBluetoothPermission() {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                showLocationAndBluetoothPermissionExplanation(false);
            } else {
                requestLocationAndBluetoothPermission();
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_SCAN)
                            || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT)) {

                        showLocationAndBluetoothPermissionExplanation(true);

                    } else {
                        requestLocationAndBluetoothPermission();
                    }
                } else {
                    scanQrCode();
                }
            } else {
                scanQrCode();
            }
        }
    }

    private void showLoading() {
        loader.setVisibility(View.VISIBLE);
        loader.show();
    }

    private void hideLoading() {
        loader.hide();
    }

    private QRCodeScanListener qrCodeScanListener = new QRCodeScanListener() {

        @Override
        public void qrCodeScanned() {

            runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    showLoading();
                    Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    vib.vibrate(50);
                    isQrCodeDataReceived = true;
                }
            });
        }

        @Override
        public void deviceDetected(final ESPDevice device) {

            Log.d(TAG, "Device detected");
            espDevice = device;
            final String deviceType = sharedPreferences.getString(AppConstants.KEY_DEVICE_TYPES, AppConstants.DEVICE_TYPE_DEFAULT);

            runOnUiThread(new Runnable() {

                @Override
                public void run() {

                    if (ActivityCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Location Permission not granted.");
                        return;
                    }

                    if (deviceType.equals(AppConstants.DEVICE_TYPE_BLE)) {

                        if (espDevice != null && espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_SOFTAP)) {
                            alertForDeviceNotSupported(getString(R.string.error_device_transport_not_supported));
                        } else {
                            device.connectToDevice();
                        }
                    } else if (deviceType.equals(AppConstants.DEVICE_TYPE_SOFTAP)) {

                        if (espDevice != null && espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_BLE)) {
                            alertForDeviceNotSupported(getString(R.string.error_device_transport_not_supported));
                        } else {

                            if (espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_SOFTAP)
                                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

                                if (!wifiManager.isWifiEnabled()) {
                                    alertForWiFi();
                                    return;
                                }
                            }

                            device.connectToDevice();
                        }
                    } else {

                        if (espDevice.getTransportType().equals(ESPConstants.TransportType.TRANSPORT_SOFTAP)
                                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

                            if (!wifiManager.isWifiEnabled()) {
                                alertForWiFi();
                                return;
                            }
                        }
                        device.connectToDevice();
                    }
                }
            });
        }

        @Override
        public void onFailure(final Exception e) {

            Log.e(TAG, "Error : " + e.getMessage());

            runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    hideLoading();
                    String msg = e.getMessage();
                    Toast.makeText(AddDeviceActivity.this, msg, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }

        @Override
        public void onFailure(Exception e, String qrCodeData) {
            // Called when QR code is not in supported format.
            // Comment below error handling and do whatever you want to do with your QR code data.
            Log.e(TAG, "Error : " + e.getMessage());
            Log.e(TAG, "QR code data : " + qrCodeData);

            runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    hideLoading();
                    String msg = e.getMessage();
                    Toast.makeText(AddDeviceActivity.this, msg, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }
    };

    private void goToWiFiScanActivity() {

        finish();
        Intent wifiListIntent = new Intent(getApplicationContext(), WiFiScanActivity.class);
        startActivity(wifiListIntent);
    }

    private void goToThreadScanActivity(boolean scanCapAvailable) {

        finish();
        Intent threadConfigIntent = new Intent(getApplicationContext(), ThreadConfigActivity.class);
        threadConfigIntent.putExtra(AppConstants.KEY_THREAD_SCAN_AVAILABLE, scanCapAvailable);
        startActivity(threadConfigIntent);
    }

    private void goToWiFiConfigActivity() {

        finish();
        Intent wifiConfigIntent = new Intent(getApplicationContext(), WiFiConfigActivity.class);
        startActivity(wifiConfigIntent);
    }

    private void alertForWiFi() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setMessage(R.string.error_wifi_off);

        // Set up the buttons
        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

                dialog.dismiss();
                espDevice = null;
                hideLoading();
                if (codeScanner != null) {
                    codeScanner.releaseResources();
                    codeScanner.startPreview();
                    if (ActivityCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            && ActivityCompat.checkSelfPermission(AddDeviceActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        provisionManager.scanQRCode(codeScanner, qrCodeScanListener);
                    } else {
                        Log.e(TAG, "Permissions are not granted");
                    }
                }
            }
        });

        builder.show();
    }

    private void askForManualDeviceConnection() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);

        builder.setMessage("Unable to connect with device. \nDo you want to connect device manually ?");

        // Set up the buttons
        builder.setPositiveButton(R.string.btn_yes, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

                dialog.dismiss();
                if (espDevice != null) {

                    switch (espDevice.getSecurityType()) {
                        case SECURITY_0:
                            goToWiFiProvisionLandingActivity(AppConstants.SEC_TYPE_0);
                            break;
                        case SECURITY_1:
                            goToWiFiProvisionLandingActivity(AppConstants.SEC_TYPE_1);
                            break;
                        case SECURITY_2:
                        default:
                            goToWiFiProvisionLandingActivity(AppConstants.SEC_TYPE_2);
                            break;
                    }
                } else {
                    finish();
                }
            }
        });

        builder.setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

                dialog.dismiss();
                finish();
            }
        });

        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    private void startProvisioningFlow() {

        String deviceType = sharedPreferences.getString(AppConstants.KEY_DEVICE_TYPES, AppConstants.DEVICE_TYPE_DEFAULT);
        final boolean isSecure = sharedPreferences.getBoolean(AppConstants.KEY_SECURITY_TYPE, true);
        Log.d(TAG, "Device Types : " + deviceType);
        int securityType = AppConstants.SEC_TYPE_0;
        if (isSecure) {
            securityType = AppConstants.SEC_TYPE_DEFAULT;
        }

        if (deviceType.equals(AppConstants.DEVICE_TYPE_BLE)) {

            if (isSecure) {
                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_2);
            } else {
                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_0);
            }
            goToBLEProvisionLandingActivity(securityType);

        } else if (deviceType.equals(AppConstants.DEVICE_TYPE_SOFTAP)) {

            if (isSecure) {
                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_SOFTAP, ESPConstants.SecurityType.SECURITY_2);
            } else {
                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_SOFTAP, ESPConstants.SecurityType.SECURITY_0);
            }
            goToWiFiProvisionLandingActivity(securityType);

        } else {

            final String[] deviceTypes = {"BLE", "SoftAP"};
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setCancelable(true);
            builder.setTitle(R.string.dialog_msg_device_selection);
            final int finalSecurityType = securityType;
            builder.setItems(deviceTypes, new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int position) {

                    switch (position) {
                        case 0:

                            if (isSecure) {
                                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_2);
                            } else {
                                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_0);
                            }
                            dialog.dismiss();
                            goToBLEProvisionLandingActivity(finalSecurityType);
                            break;

                        case 1:

                            if (isSecure) {
                                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_SOFTAP, ESPConstants.SecurityType.SECURITY_2);
                            } else {
                                provisionManager.createESPDevice(ESPConstants.TransportType.TRANSPORT_SOFTAP, ESPConstants.SecurityType.SECURITY_0);
                            }
                            dialog.dismiss();
                            goToWiFiProvisionLandingActivity(finalSecurityType);
                            break;
                    }
                    dialog.dismiss();
                }
            });
            builder.show();
        }
    }

    private void setSecurityTypeFromVersionInfo() {

        boolean isSecure = sharedPreferences.getBoolean(AppConstants.KEY_SECURITY_TYPE, true);
        String protoVerStr = provisionManager.getEspDevice().getVersionInfo();
        int securityType = AppConstants.SEC_TYPE_2;

        try {
            JSONObject jsonObject = new JSONObject(protoVerStr);
            JSONObject provInfo = jsonObject.getJSONObject("prov");

            if (provInfo.has("sec_ver")) {

                int serVer = provInfo.optInt("sec_ver");
                Log.d(TAG, "Security Version : " + serVer);

                switch (serVer) {
                    case AppConstants.SEC_TYPE_0:
                        securityType = AppConstants.SEC_TYPE_0;
                        provisionManager.getEspDevice().setSecurityType(ESPConstants.SecurityType.SECURITY_0);
                        break;
                    case AppConstants.SEC_TYPE_1:
                        securityType = AppConstants.SEC_TYPE_1;
                        provisionManager.getEspDevice().setSecurityType(ESPConstants.SecurityType.SECURITY_1);
                        break;
                    case AppConstants.SEC_TYPE_2:
                    default:
                        securityType = AppConstants.SEC_TYPE_2;
                        provisionManager.getEspDevice().setSecurityType(ESPConstants.SecurityType.SECURITY_2);
                        ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();

                        if (deviceCaps != null && deviceCaps.size() > 0
                                && (deviceCaps.contains(AppConstants.CAPABILITY_THREAD_SCAN) || deviceCaps.contains(AppConstants.CAPABILITY_THREAD_PROV))
                        ) {
                            String userName = sharedPreferences.getString(AppConstants.KEY_USER_NAME_THREAD, AppConstants.DEFAULT_USER_NAME_THREAD);
                            provisionManager.getEspDevice().setUserName(userName);
                        } else if (TextUtils.isEmpty(provisionManager.getEspDevice().getUserName())) {
                            String userName = sharedPreferences.getString(AppConstants.KEY_USER_NAME_WIFI, AppConstants.DEFAULT_USER_NAME_WIFI);
                            provisionManager.getEspDevice().setUserName(userName);
                        }
                        break;
                }
            } else {
                if (securityType == AppConstants.SEC_TYPE_2) {
                    securityType = AppConstants.SEC_TYPE_1;
                    provisionManager.getEspDevice().setSecurityType(ESPConstants.SecurityType.SECURITY_1);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            Log.d(TAG, "Capabilities JSON not available.");
        }

        if (isSecure) {
            if (securityType == AppConstants.SEC_TYPE_0) {
                alertForDeviceNotSupported(getString(R.string.error_security_mismatch));
            } else {
                processDeviceCapabilities();
            }
        } else {
            if (securityType != AppConstants.SEC_TYPE_0) {
                alertForDeviceNotSupported(getString(R.string.error_security_mismatch));
            } else {
                processDeviceCapabilities();
            }
        }
    }

    private void processDeviceCapabilities() {
        ArrayList<String> deviceCaps = espDevice.getDeviceCapabilities();
        if (deviceCaps.contains(AppConstants.CAPABILITY_WIFI_SCAN)) {
            goToWiFiScanActivity();
        } else if (deviceCaps.contains(AppConstants.CAPABILITY_THREAD_SCAN)) {
            goToThreadScanActivity(true);
        } else if (deviceCaps.contains(AppConstants.CAPABILITY_THREAD_PROV)) {
            goToThreadScanActivity(false);
        } else {
            goToWiFiConfigActivity();
        }
    }

    private void goToBLEProvisionLandingActivity(int securityType) {

        finish();
        Intent bleProvisioningIntent = new Intent(AddDeviceActivity.this, BLEProvisionLanding.class);
        bleProvisioningIntent.putExtra(AppConstants.KEY_SECURITY_TYPE, securityType);
        startActivity(bleProvisioningIntent);
    }

    private void goToWiFiProvisionLandingActivity(int securityType) {

        finish();
        Intent wifiProvisioningIntent = new Intent(getApplicationContext(), ProvisionLanding.class);
        wifiProvisioningIntent.putExtra(AppConstants.KEY_SECURITY_TYPE, securityType);

        if (espDevice != null) {
            wifiProvisioningIntent.putExtra(AppConstants.KEY_DEVICE_NAME, espDevice.getDeviceName());
            wifiProvisioningIntent.putExtra(AppConstants.KEY_PROOF_OF_POSSESSION, espDevice.getProofOfPossession());
        }
        startActivity(wifiProvisioningIntent);
    }

    private String getWifiSsid() {

        String ssid = null;
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo.getSupplicantState() == SupplicantState.COMPLETED) {

            ssid = wifiInfo.getSSID();
            ssid = ssid.replace("\"", "");
        }
        return ssid;
    }

    private void alertForDeviceNotSupported(String msg) {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);

        builder.setTitle(R.string.error_title);
        builder.setMessage(msg);

        // Set up the buttons
        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (provisionManager.getEspDevice() != null) {
                    provisionManager.getEspDevice().disconnectDevice();
                }
                dialog.dismiss();
                finish();
            }
        });

        builder.show();
    }
}
