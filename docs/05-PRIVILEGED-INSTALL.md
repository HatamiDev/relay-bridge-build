# Privileged install — what it takes, and whether it is worth it

The gateway currently bridges audio acoustically, because Android does not give
an ordinary app the cellular voice stream. The three telephony capture sources —
`VOICE_CALL`, `VOICE_DOWNLINK`, `VOICE_UPLINK` — are gated behind
`android.permission.CAPTURE_AUDIO_OUTPUT`, whose `protectionLevel` is
`signature|privileged`. No amount of runtime permission granting reaches it: the
app has to *be* privileged.

This document is what that means in practice on a Galaxy Note 10+, and the
honest odds.

---

## What "privileged" means

An app is privileged when its APK sits in `/system/priv-app/` (or
`/product/priv-app/`) **and** an XML file in `/system/etc/permissions/` names it
as allowed to hold the privileged permissions it requests. Both halves are
required — placement alone gets the app installed but the permission still
denied, which is a common way to lose an evening.

The allowlist entry looks like this:

```xml
<!-- /system/etc/permissions/privapp-permissions-relay.xml -->
<permissions>
    <privapp-permissions package="com.relay.app">
        <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
    </privapp-permissions>
</permissions>
```

Android reads it at boot. A missing or malformed entry makes the device refuse
to boot past the splash on some builds, so it is edited on a device you can
recover.

---

## What it takes on a Note 10+

1. **An unlockable bootloader.** Exynos international models (`SM-N975F`) can be
   unlocked. Snapdragon models sold in the US (`SM-N975U`) **cannot** — the
   bootloader is locked by the carrier and there is no path. Check the model in
   Settings → About phone before anything else; if it ends in `U` this document
   stops here.

2. **Unlocking, which wipes the device and trips Knox permanently.** Knox is a
   one-way efuse. Once tripped: Samsung Pay, Secure Folder, Samsung Health data,
   Knox-dependent work profiles and some banking apps stop working *forever*,
   including after re-locking and reflashing stock firmware. There is no undo.

3. **Root, via Magisk.** Patch the stock `AP` firmware image with Magisk, flash
   it with Odin, and let the phone boot.

4. **A Magisk module that places the APK and the allowlist.** Systemless, so
   `/system` is not actually modified:

   ```
   relay-priv/
     module.prop
     system/priv-app/RelayBridge/RelayBridge.apk
     system/etc/permissions/privapp-permissions-relay.xml
   ```

   Install the module, reboot, and confirm with
   `adb shell dumpsys package com.relay.app | grep CAPTURE_AUDIO_OUTPUT` —
   it should say `granted=true`.

---

## The part that decides it

**Even with the permission granted, Samsung's audio HAL blocks the telephony
capture sources on most retail firmware.** The permission check passes,
`AudioRecord` constructs, and the buffers come back as digital silence. This is
a vendor decision below the framework, and root does not move it. Whether a
given Note 10+ firmware allows it is not knowable without trying.

So the trade is: a permanently Knox-tripped phone, a wiped device, and a
maintenance burden on every firmware update — against a capture path that may
still be silent for reasons no privilege level can fix.

---

## The alternative that costs nothing

A wired headset plugged into the gateway, with the earpiece resting against the
headset's own inline microphone.

* Nothing is audible in the room. The call does not go to the loudspeaker.
* The coupling is centimetres instead of metres, so the far end arrives far
  cleaner than speakerphone loopback.
* No root, no unlock, no Knox, no wipe.

The app detects the headset and leaves the audio route alone when one is
present, instead of forcing the speaker. The receiver's call screen shows
`headset on sender` when this is what is happening.

This is the configuration to try first. Privileged install is worth considering
only after it has been tried and the audio quality is genuinely unacceptable —
and even then, only on an Exynos model whose owner has accepted losing Knox.
