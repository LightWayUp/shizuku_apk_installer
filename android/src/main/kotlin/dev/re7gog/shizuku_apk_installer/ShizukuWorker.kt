package dev.re7gog.shizuku_apk_installer

import android.content.Context
import android.content.IIntentReceiver
import android.content.IIntentSender
import android.content.Intent
import android.content.IntentSender
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ShizukuWorker(private val appContext: Context) {
    private var isBinderAvailable = false
    private val requestPermissionCode = (1000..2000).random()
    private val requestPermissionMutex by lazy { Mutex(locked = true) }
    private var permissionGranted: Boolean? = null
    private var isRoot: Boolean? = null

    fun init() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {  // 9
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
    }

    fun exit() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        isBinderAvailable = true
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isBinderAvailable = false
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode: Int, grantResult: Int ->
            if (requestCode == requestPermissionCode) {
                permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
                requestPermissionMutex.unlock()
            }
        }

    suspend fun checkPermission(): String {
        return if (!isBinderAvailable) {
            "binder_not_found"
        } else if (Shizuku.isPreV11()) {
            "old_shizuku"
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (!registerUidObserverPermissionLimitedCheck()) "granted" else "old_android_with_adb"
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {  // "Deny and don't ask again"
            "denied"
        } else {
            Shizuku.requestPermission(requestPermissionCode)
            requestPermissionMutex.lock()
            if (!registerUidObserverPermissionLimitedCheck()) {
                if (permissionGranted!!) "granted" else "denied"
            } else "old_android_with_adb"
        }
    }

    /**
     * Android 8.0 with ADB lacks IActivityManager#registerUidObserver permission, so we can't install apps without activity
     */
    private fun registerUidObserverPermissionLimitedCheck(): Boolean {
        isRoot = Shizuku.getUid() == 0
        return !isRoot!! and (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1)
    }

    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    private val iPackageManager: IPackageManager by lazy {
        IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package").wrap())
    }

    private val iPackageInstaller: IPackageInstaller by lazy {
        IPackageInstaller.Stub.asInterface(iPackageManager.packageInstaller.asShizukuBinder())
    }

    private val packageInstaller: PackageInstaller by lazy {
        // The reason for use "com.android.shell" as installer package under adb is that getMySessions will check installer package's owner
        val installerPackageName = if (isRoot!!) appContext.packageName else "com.android.shell"
        val userId = if (!isRoot!!) Process.myUserHandle().hashCode() else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, installerPackageName, appContext.attributionTag, userId))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, installerPackageName, userId))
        } else {
            val application = appContext.applicationContext
            Refine.unsafeCast(PackageInstallerHidden(application, application.packageManager, iPackageInstaller, installerPackageName, userId))
        }
    }

    private fun createPackageInstallerSession(params: PackageInstaller.SessionParams): PackageInstaller.Session {
        val sessionId = packageInstaller.createSession(params)
        val iSession = IPackageInstallerSession.Stub.asInterface(iPackageInstaller.openSession(sessionId).asShizukuBinder())
        return Refine.unsafeCast(PackageInstallerHidden.SessionHidden(iSession))
    }

    suspend fun installAPKs(apkURIs: List<String>): Pair<Int, String?> {
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        val contentResolver = appContext.contentResolver
        withContext(Dispatchers.IO) {
            runCatching {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                var flags = Refine.unsafeCast<PackageInstallerHidden.SessionParamsHidden>(params).installFlags
                flags = flags or PackageManagerHidden.INSTALL_ALLOW_TEST or PackageManagerHidden.INSTALL_REPLACE_EXISTING
                Refine.unsafeCast<PackageInstallerHidden.SessionParamsHidden>(params).installFlags = flags
                createPackageInstallerSession(params).use { session ->
                    apkURIs.forEachIndexed { index, uriString ->
                        val uri = Uri.parse(uriString)
                        val stream = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open input stream")
                        stream.use {
                            session.openWrite("$index.apk", 0, stream.available().toLong()).use { output ->
                                stream.copyTo(output)
                                session.fsync(output)
                            }
                        }
                    }
                    var result: Intent? = null
                    suspendCoroutine { cont ->
                        val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                            result = intent
                            cont.resume(Unit)
                        }
                        val intentSender = IntentSenderHelper.newIntentSender(adapter)
                        session.commit(intentSender)
                    }
                    result?.let {
                        status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    } ?: throw IOException("Intent is null")
                }
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = it.message + "\n" + it.stackTraceToString()
            }
        }
        return Pair(status, message)
    }
}


object IntentSenderHelper {
    fun newIntentSender(binder: IIntentSender): IntentSender {
        return IntentSender::class.java.getConstructor(IIntentSender::class.java).newInstance(binder)
    }

    class IIntentSenderAdaptor(private val listener: (Intent) -> Unit) : IIntentSender.Stub() {
        override fun send(
            code: Int,
            intent: Intent,
            resolvedType: String?,
            finishedReceiver: IIntentReceiver?,
            requiredPermission: String?,
            options: Bundle?
        ): Int {
            listener(intent)
            return 0
        }

        override fun send(
            code: Int,
            intent: Intent,
            resolvedType: String?,
            whitelistToken: IBinder?,
            finishedReceiver: IIntentReceiver?,
            requiredPermission: String?,
            options: Bundle?
        ) {
            listener(intent)
        }
    }
}