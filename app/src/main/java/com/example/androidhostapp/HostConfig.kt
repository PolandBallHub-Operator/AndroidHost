package com.example.androidhostapp

internal object HostConfig {
    const val RELAY_PORT = 31416
    const val CLIENT_PACKAGE = "com.genymobile.gnirehtet"
    const val CLIENT_ACTIVITY = "com.genymobile.gnirehtet/.GnirehtetActivity"
    const val CLIENT_ABSTRACT_SOCKET = "gnirehtet"

    // Direct ADB-over-USB host service requests handled by the connected device's adbd.
    const val ADB_REVERSE_FORWARD = "reverse:forward:localabstract:$CLIENT_ABSTRACT_SOCKET;tcp:$RELAY_PORT"
    const val ADB_REVERSE_REMOVE = "reverse:killforward:localabstract:$CLIENT_ABSTRACT_SOCKET"
    const val CLIENT_START_SHELL = "shell:am start -a $CLIENT_PACKAGE.START -n $CLIENT_ACTIVITY"
    const val CLIENT_STOP_SHELL = "shell:am start -a $CLIENT_PACKAGE.STOP -n $CLIENT_ACTIVITY"
}
