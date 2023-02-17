package com.pauldemarco.flutter_blue

import android.bluetooth.BluetoothDevice
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class BleData(
    var type: String = "W",
    var field: String = "E",
    var data: SendData = SendData()
) {
    fun toByte() : ByteArray{
        return "+${Gson().toJson(this)}*".toByteArray()
    }

    fun toJsonString(): String{
        return "+${Gson().toJson(this)}*"
    }
}


data class SendData(
    @SerializedName("object")
    var objectSend: String = "all",
    var value: String = "0"
)

data class BleDevice(
    var device: BluetoothDevice? = null,
    var status: Int = 0
) {
    fun isConnected(): Boolean = status != 0
}

data class Reconnect(var isConnect : Boolean = false)
open class BleResponse<T>(
    var type: String = "W",
    var field: String = "E",
    var data: T? = null
)

class BleResponseSate : BleResponse<ResponseState>()

class BleResponseDevice : BleResponse<ResponseDevice>()

data class ResponseState(
    @SerializedName("object")
    var objectSend: String = "all",
    var state: Boolean = false
)

data class ResponseDevice(
    @SerializedName("serial_number")
    var deviceName: String = "",

    var lat: String = "",

    @SerializedName("lon")
    var longitude: String = "",

    var speed: String = "",
    var error: String = "",
    var odo: Long = 0,

    @SerializedName("input_vol")
    var maxLoad:String ="",

    @SerializedName("bp_num")
    var bpNum: String = "",

    @SerializedName("max_speed")
    var maxSpeed: Int = 0,

    var horn: String = "",
    var light: String = "",

    @SerializedName("signal_light")
    var signalLight: String = "",

    @SerializedName("drive_mode")
    var driveMode: String = "",

    var key: String = ""
){
    fun totalDistance():String{
        val floatSpeed = odo/1000
        return "$floatSpeed km"
    }

    fun isHorn(): Boolean {
        return horn == "1"
    }

    fun isLight(): Boolean{
        return light =="1"
    }

    fun getMaxSpeed(): Float {
        var maxSpeed = try {
            this.maxSpeed.toFloat()
        } catch (e: Exception) {
            Const.DEFAULT_SPEED
        }
        if (maxSpeed < Const.DEFAULT_SPEED) maxSpeed = Const.DEFAULT_SPEED
        return maxSpeed
    }

    fun getDriveMode(): Float {
        var driveMode = try {
            this.driveMode.toFloat()
        } catch (e: Exception) {
            1f
        }
        if (driveMode < 1f) driveMode = 1f
        return driveMode
    }
}