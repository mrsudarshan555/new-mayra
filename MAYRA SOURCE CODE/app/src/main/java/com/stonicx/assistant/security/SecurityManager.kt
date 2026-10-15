package com.stonicx.assistant.security

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * StonicX ASSISTANT — SECURITY MODULE
 * Developer       : StonicX
 * Copyright © 2026: StonicX. All Rights Reserved.
 */
object SecurityManager {

    const val DEVELOPER_NAME    = "StonicX"
    const val COPYRIGHT_NOTICE  = "Copyright © 2026 StonicX. All Rights Reserved."
    const val LICENSE_NAME      = "TAMCL v1.0 — Content creators MUST credit StonicX"

    private const val EXPECTED_PACKAGE = "com.stonicx.assistant"

    fun verify(context: Context) {
        checkPackageName(context)
    }

    private fun checkPackageName(context: Context) {
        if (context.packageName != EXPECTED_PACKAGE) {
            showTamperAlert(
                context,
                "Clone Detected",
                "This is an unauthorised clone of the StonicX app by $DEVELOPER_NAME.\n\n" +
                "Original: $EXPECTED_PACKAGE\n" +
                "Found   : ${context.packageName}\n\n" +
                "The app will now close. Please install the official version from Google Play."
            )
        }
    }

    @Suppress("unused")
    private fun checkSignature(context: Context) {
        val EXPECTED_SHA256 = "PASTE_YOUR_APK_SHA256_FINGERPRINT_HERE"

        val actual = getApkSignatureSha256(context) ?: run {
            showTamperAlert(context, "Verification Failed", "Unable to verify app authenticity. Closing.")
            return
        }
        if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            showTamperAlert(
                context,
                "Tampered APK Detected",
                "This APK has been re-signed or modified.\n\n" +
                "$DEVELOPER_NAME only distributes APKs signed with the original certificate.\n\n" +
                "Please install the official version from Google Play."
            )
        }
    }

    private fun getApkSignatureSha256(context: Context): String? {
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNATURES
                )
                info.signatures
            }
            val firstSig = sigs?.firstOrNull() ?: return null
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(firstSig.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun showTamperAlert(context: Context, title: String, message: String) {
        try {
            AlertDialog.Builder(context)
                .setTitle("⚠️ $title")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitProcess(1) }
                .show()
        } catch (_: Exception) {
            exitProcess(1)
        }
    }
}
