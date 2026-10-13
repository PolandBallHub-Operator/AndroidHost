# Third-party source provenance

- **gnirehtet relay Java sources**: https://github.com/Genymobile/gnirehtet, upstream revision `1eb2e58bc91e268dba62561d11e3bebd9b100883` (v2.5.1). Apache-2.0. The Android HostApp uses the `relay-java/.../relay` package and adds a stop hook plus transfer counters.
- **flashbot ADB library / USB channel**: https://github.com/wuxudong/flashbot, upstream revision `8a7ce07002f1b883e34567c6cf155d5c10141e0b`. BSD 3-Clause. The app uses `adblib` classes for direct ADB-over-USB operation.
- **AdbLib origin**: https://github.com/cgutman/AdbLib. BSD license, copyright Cameron Gutman. flashbot README identifies this as its base. Copyright headers in the Java source are retained.

Full license texts are in `licenses/` and in the APK's `res/raw/` resources for the in-app license dialog.

## Platform references

- Android USB host API: https://developer.android.com/develop/connectivity/usb/host
- Android VPN API (receiver/client-side VpnService): https://developer.android.com/develop/connectivity/vpn
- ADB transport/tool behavior: https://developer.android.com/tools/adb
- AOSP `adbd` reverse service dispatch (`reverse_service` / `handle_forward_request`): https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/services.cpp
