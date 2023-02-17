// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.pauldemarco.flutter_blue

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.PluginRegistry.Registrar
import com.pauldemarco.flutter_blue.Protos.BluetoothState
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.pauldemarco.flutter_blue.Protos.ConnectedDevicesResponse
import com.pauldemarco.flutter_blue.Protos.ConnectRequest
import com.google.protobuf.InvalidProtocolBufferException
import com.pauldemarco.flutter_blue.Protos.DiscoverServicesResult
import com.pauldemarco.flutter_blue.Protos.ReadCharacteristicRequest
import com.pauldemarco.flutter_blue.Protos.ReadDescriptorRequest
import com.pauldemarco.flutter_blue.Protos.WriteCharacteristicRequest
import com.pauldemarco.flutter_blue.Protos.WriteDescriptorRequest
import com.pauldemarco.flutter_blue.Protos.SetNotificationRequest
import com.pauldemarco.flutter_blue.Protos.MtuSizeResponse
import com.pauldemarco.flutter_blue.Protos.MtuSizeRequest
import kotlin.Throws
import io.flutter.plugin.common.EventChannel.EventSink
import com.pauldemarco.flutter_blue.Protos.ReadCharacteristicResponse
import com.pauldemarco.flutter_blue.Protos.WriteCharacteristicResponse
import com.pauldemarco.flutter_blue.Protos.OnCharacteristicChanged
import com.pauldemarco.flutter_blue.Protos.ReadDescriptorResponse
import com.google.protobuf.ByteString
import com.pauldemarco.flutter_blue.Protos.WriteDescriptorResponse
import com.pauldemarco.flutter_blue.Protos.SetNotificationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.Exception
import java.lang.IllegalStateException
import java.nio.charset.StandardCharsets
import java.util.*

/** FlutterBluePlugin  */
class FlutterBluePlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
    RequestPermissionsResultListener, SerialListener {
    private val initializationLock = Any()
    private var context: Context? = null
    private var channel: MethodChannel? = null
    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var pluginBinding: FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var application: Application? = null
    private var activity: Activity? = null
    private val mDevices: MutableMap<String, BluetoothDeviceCache> = HashMap()
    private var logLevel = LogLevel.EMERGENCY

    // Pending call and result for startScan, in the case where permissions are needed
    private var pendingCall: MethodCall? = null
    private var pendingResult: MethodChannel.Result? = null
    private val macDeviceScanned = ArrayList<String>()
    private var socket: SerialSocket? = null
    private var allowDuplicates = false
    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        pluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        setup(
            pluginBinding!!.binaryMessenger,
            pluginBinding!!.applicationContext as Application,
            activityBinding!!.activity,
            null,
            activityBinding
        )
    }

    override fun onDetachedFromActivity() {
        tearDown()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    private fun setup(
        messenger: BinaryMessenger,
        application: Application?,
        activity: Activity?,
        registrar: Registrar?,
        activityBinding: ActivityPluginBinding?
    ) {
        synchronized(initializationLock) {
            Log.i(TAG, "setup")
            this.activity = activity
            this.application = application
            context = application
            channel = MethodChannel(messenger, NAMESPACE + "/methods")
            channel!!.setMethodCallHandler(this)

            mBluetoothManager =
                application!!.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            mBluetoothAdapter = mBluetoothManager!!.adapter
            // V1 embedding setup for activity listeners.
            registrar?.addRequestPermissionsResultListener(this)
                ?: // V2 embedding setup for activity listeners.
                activityBinding!!.addRequestPermissionsResultListener(this)
        }
    }

    private fun tearDown() {
        Log.i(TAG, "teardown")
        context = null
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        channel?.setMethodCallHandler(null)
        channel = null

        mBluetoothAdapter = null
        mBluetoothManager = null
        application = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (mBluetoothAdapter == null && "isAvailable" != call.method) {
            result.error("bluetooth_unavailable", "the device does not have bluetooth", null)
            return
        }
        when (call.method) {
            "setLogLevel" -> {
                val logLevelIndex = call.arguments as Int
                logLevel = LogLevel.values()[logLevelIndex]
                result.success(null)
            }
            "state" -> {
                val p = BluetoothState.newBuilder()
                try {
                    when (mBluetoothAdapter!!.state) {
                        BluetoothAdapter.STATE_OFF -> p.state = BluetoothState.State.OFF
                        BluetoothAdapter.STATE_ON -> p.state = BluetoothState.State.ON
                        BluetoothAdapter.STATE_TURNING_OFF -> p.state =
                            BluetoothState.State.TURNING_OFF
                        BluetoothAdapter.STATE_TURNING_ON -> p.state =
                            BluetoothState.State.TURNING_ON
                        else -> p.state = BluetoothState.State.UNKNOWN
                    }
                } catch (e: SecurityException) {
                    p.state = BluetoothState.State.UNAUTHORIZED
                }
                result.success(p.build().toByteArray())
            }
            "isAvailable" -> {
                result.success(mBluetoothAdapter != null)
            }
            "isOn" -> {
                result.success(mBluetoothAdapter!!.isEnabled)
            }
            "startScan" -> {
                if (ContextCompat.checkSelfPermission(
                        context!!,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        activityBinding!!.activity, arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ),
                        REQUEST_FINE_LOCATION_PERMISSIONS
                    )
                    pendingCall = call
                    pendingResult = result
                    return
                }
                startScan()
//                startScan(call, result)
            }
            "stopScan" -> {
                stopScan()
                result.success(null)
            }
            "getConnectedDevices" -> {
                val devices = mBluetoothManager!!.getConnectedDevices(BluetoothProfile.GATT)
                val p = ConnectedDevicesResponse.newBuilder()
                for (d in devices) {
                    p.addDevices(ProtoMaker.from(d))
                }
                result.success(p.build().toByteArray())
            }
            "connect" -> {

                val data = call.arguments<ByteArray>()
                val options: ConnectRequest
                options = try {
                    ConnectRequest.newBuilder().mergeFrom(data).build()
                } catch (e: InvalidProtocolBufferException) {
                    result.error("RuntimeException", e.message, e)
                    return
                }
                val deviceId = options.remoteId
                val device = mBluetoothAdapter!!.getRemoteDevice(deviceId)
                val isConnected =
                    mBluetoothManager!!.getConnectedDevices(BluetoothProfile.GATT).contains(device)

                // If device is already connected, return error
                if (mDevices.containsKey(deviceId) && isConnected) {
                    result.error("already_connected", "connection with device already exists", null)
                    return
                }

                connect("CM000")
            }
            "disconnect" -> {
                disconnect()
            }
            "write" -> {
                val data = call.arguments
                write(data as ByteArray?)
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ): Boolean {
        if (requestCode == REQUEST_FINE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan()
//                startScan(pendingCall, pendingResult)
            } else {
                pendingResult!!.error(
                    "no_permissions",
                    "flutter_blue plugin requires location permissions for scanning",
                    null
                )
                pendingResult = null
                pendingCall = null
            }
            return true
        }
        return false
    }

    private fun startScan() {
        CoroutineScope(Dispatchers.IO).launch {
            mBluetoothAdapter?.bluetoothLeScanner?.startScan(leScanCallBack)
        }
    }

    private val leScanCallBack: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val bleDevice = result.device
            val deviceName: String? = bleDevice.name
            if (deviceName.isNullOrEmpty()) return
            if (macDeviceScanned.contains(result.device.address)) return
            macDeviceScanned.add(result.device.address)
            Log.d("TAG", "ScanResult: $deviceName")
            invokeMethod("ScanResult", macDeviceScanned)
        }
    }

    private fun stopScan() {
        mBluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallBack)
    }

    private fun connect(deviceAddress: String) {
        try {
            val bluetoothManager =
                context?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            val bluetoothAdapter = bluetoothManager?.adapter
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: return
            socket = SerialSocket(context, device)
            socket?.connect(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disconnect() {
        socket?.disconnect()
        socket = null
    }

    private fun write(data: ByteArray?) {
        try {
            socket?.write(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSerialConnect() {
        invokeMethod("connect", true)
    }

    override fun onSerialConnectError(e: Exception) {}

    override fun onSerialRead(data: ByteArray?) {
        val msg = data?.let { String(it) }
        invokeMethod("read", "$msg")
    }

    override fun onSerialIoError(e: Exception) {}

    internal enum class LogLevel {
        EMERGENCY, ALERT, CRITICAL, ERROR, WARNING, NOTICE, INFO, DEBUG
    }

    private fun log(level: LogLevel, message: String) {
        if (level.ordinal <= logLevel.ordinal) {
            Log.d(TAG, message)
        }
    }


    private fun invokeMethodUIThread(name: String, byteArray: ByteArray) {
        activity!!.runOnUiThread { channel!!.invokeMethod(name, byteArray) }
    }

    private fun invokeMethod(name: String, argument: Any) {
        activity?.runOnUiThread {
            channel?.invokeMethod(name, argument)
        }
    }

    // BluetoothDeviceCache contains any other cached information not stored in Android Bluetooth API
    // but still needed Dart side.
    internal inner class BluetoothDeviceCache(val gatt: BluetoothGatt?) {
        var mtu = 20
    }

    companion object {
        private const val TAG = "FlutterBluePlugin"
        private const val NAMESPACE = "plugins.pauldemarco.com/flutter_blue"
        private const val REQUEST_FINE_LOCATION_PERMISSIONS = 1452
        private val CCCD_ID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Plugin registration.  */
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = FlutterBluePlugin()
            val activity = registrar.activity()
            var application: Application? = null
            if (registrar.context() != null) {
                application = registrar.context().applicationContext as Application
            }
            instance.setup(registrar.messenger(), application, activity, registrar, null)
        }
    }
}