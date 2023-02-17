package com.pauldemarco.flutter_blue

object Const {
    //speed
    const val DEFAULT_SPEED = 20f
    const val MAX_SPEED_VALUE = 255f

    // values send
    const val MESSAGE_OBJECT = "message"
    const val READ_ID = "all"
    const val CALL_OBJECT = "call"
    const val CHECK_ID = "ev_id"
    const val GENERATE_OTP = "id_maker"
    const val CHECK_OTP = "ble_id"
    const val FIND_VEHICLE = "find_ev"
    const val THROTTLE = "lock_ev"
    const val MAX_SPEED = "speed"
    const val DRIVE_MODE = "drive_mode"
    const val DISCONNECT = "disconnect"

    const val INTENT_ACTION_DISCONNECT: String = BuildConfig.LIBRARY_PACKAGE_NAME + ".Disconnect"

}