#!/system/bin/sh
# Magisk install script.
#
# SKIPUNZIP=0 lets Magisk unpack the module tree as-is; everything under
# system/ is then overlaid at boot without touching the real partition, so
# uninstalling the module fully reverses this.

ui_print "- RelayBridge privileged module"

APK="$MODPATH/system/priv-app/RelayBridge/RelayBridge.apk"
if [ ! -f "$APK" ]; then
  ui_print "! RelayBridge.apk is missing from the module."
  ui_print "! Run deploy/magisk/build-module.sh with the APK path first."
  abort "  aborting"
fi

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$APK" 0 0 0644
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-relay.xml" 0 0 0644

ui_print "- Installed. Reboot, then verify with:"
ui_print "    adb shell dumpsys package com.relay.app | grep CAPTURE_AUDIO_OUTPUT"
ui_print "  It must say granted=true."
