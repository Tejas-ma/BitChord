package com.music.bitchord.ui.social

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class JamDiscoveryManager(private val context: Context) {

    companion object {
        val JAM_SERVICE_UUID: UUID = UUID.fromString(
            "0000FFF0-0000-1000-8000-00805F9B34FB"
        )
    }

    private val bluetoothManager = context
        .getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter
    private val bleScanner =
        bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser =
        bluetoothAdapter?.bluetoothLeAdvertiser

    private val _nearbyRoomIds =
        MutableStateFlow<List<String>>(emptyList())
    val nearbyRoomIds: StateFlow<List<String>> =
        _nearbyRoomIds.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> =
        _isScanning.asStateFlow()

    private val _isBluetoothAvailable = MutableStateFlow(
        bluetoothAdapter?.isEnabled == true
    )
    val isBluetoothAvailable: StateFlow<Boolean> =
        _isBluetoothAvailable.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val serviceData = result.scanRecord
                ?.getServiceData(
                    ParcelUuid(JAM_SERVICE_UUID)
                )
            if (serviceData != null) {
                val roomId = String(serviceData)
                if (roomId.isNotBlank() &&
                    !_nearbyRoomIds.value.contains(roomId)
                ) {
                    _nearbyRoomIds.value =
                        _nearbyRoomIds.value + roomId
                }
            }
        }
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) { }
            override fun onStartFailure(errorCode: Int) { }
        }

    fun startScanning() {
        if (bluetoothAdapter?.isEnabled != true) {
            _isBluetoothAvailable.value = false
            return
        }
        _nearbyRoomIds.value = emptyList()
        _isScanning.value = true
        try {
            bleScanner?.startScan(scanCallback)
        } catch (e: SecurityException) {
            _isScanning.value = false
        }
    }

    fun stopScanning() {
        _isScanning.value = false
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) { }
    }

    fun startAdvertising(roomId: String) {
        if (bluetoothAdapter?.isEnabled != true) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(
                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(JAM_SERVICE_UUID))
            .addServiceData(
                ParcelUuid(JAM_SERVICE_UUID),
                roomId.toByteArray()
            )
            .build()
        try {
            bleAdvertiser?.startAdvertising(
                settings, data, advertiseCallback
            )
        } catch (e: SecurityException) { }
    }

    fun stopAdvertising() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) { }
    }
}
