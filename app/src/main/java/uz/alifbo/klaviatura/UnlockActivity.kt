package uz.alifbo.klaviatura

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast

/**
 * Ko'rinmas oraliq oyna: parollar omborini ochish uchun telefon qulfini (barmoq izi, PIN, grafik kalit)
 * tasdiqlatadi. Tasdiqdan keyin ombor kaliti [VaultCrypto.VALIDITY_S] soniya ishlaydi.
 */
class UnlockActivity : Activity() {
    private var done = false
    private val cancel = CancellationSignal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            Toast.makeText(this, "Parollar uçun avval telefonda ekran qulfini (PIN yoki grafik kalit) qöying", Toast.LENGTH_LONG).show()
            finishWith(false)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Söz+ parollari")
                .setSubtitle("Parollarni oçiş uçun tasdiqlang")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(
                cancel,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        finishWith(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        finishWith(false)
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Söz+ parollari", "Parollarni oçiş uçun tasdiqlang")
            if (i == null) {
                finishWith(false)
            } else {
                startActivityForResult(i, REQ)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) finishWith(resultCode == RESULT_OK)
    }

    private fun finishWith(ok: Boolean) {
        if (done) return
        done = true
        VaultBridge.finish(ok)
        finish()
    }

    override fun onDestroy() {
        if (!done && isFinishing) {
            cancel.cancel()
            finishWith(false)
        }
        super.onDestroy()
    }

    private companion object {
        const val REQ = 51
    }
}
