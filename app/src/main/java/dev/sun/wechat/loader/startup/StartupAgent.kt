package dev.sun.wechat.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.sun.wechat.R
import dev.sun.wechat.loader.abc.IHookBridge
import dev.sun.wechat.loader.abc.ILoaderService
import dev.sun.wechat.loader.entry.zygisk.ArtHookBridge
import dev.sun.wechat.loader.entry.zygisk.ZygiskLoaderService
import dev.sun.wechat.loader.utils.HybridClassLoader
import dev.sun.wechat.loader.utils.NativeLoader
import dev.sun.wechat.data.JsonDataMigration
import dev.sun.wechat.data.LegacyDocumentMigration
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.TargetProcess
import dev.sun.wechat.utils.TargetProcesses
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.fs.LegacyStorageMigration
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private const val TAG = "StartupAgent"

    private val startupLock = Any()

    @Volatile
    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) = synchronized(startupLock) {
        if (initialized) {
            return@synchronized
        }

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader

        if (R.string.res_inject_success ushr 24 == 0x7f) {
            throw AssertionError("module resource package ID must not be 0x7f")
        }

        StartupInfo.modulePath = modulePath
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        if (loaderService !is ZygiskLoaderService) {
            checkWxForModulePath(modulePath)
        }

        HostInfo.init(application)
        NativeLoader.init(application)
        // 旧存储/文档/JSON 迁移都是一次性写入，只有主进程负责。
        // 缺少这一步会让 KvStore/FeatureStore 在启动时抛「JSON migration has not completed」，
        // 抢红包、收转账、群成员实名尾字等功能会直接启用失败。
        if (TargetProcesses.isInMain) {
            LegacyStorageMigration.run(application)
            LegacyDocumentMigration.run(application)
            JsonDataMigration.run()
        }
        if (hookBridge is ArtHookBridge) {
            hideModuleLibraries(hookBridge)
        }
        // 隔离进程没有功能模块，也不能碰共享的 Room 文件：
        // 否则主进程还在搬旧库时，FeaturesLoader 就可能抢先初始化 DexCache/Room。
        if (TargetProcesses.currentType != TargetProcess.ISOLATED) {
            WeLauncher.init(application)
        }

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }

        // Only commit after every required startup phase completes. The caller
        // already logs a thrown failure, and a later lifecycle callback can retry.
        initialized = true
    }

    private fun hideModuleLibraries(hookBridge: ArtHookBridge) {
        runCatching { hookBridge.hideLoadedModuleLibraries() }
            .onSuccess { hidden ->
                WeLogger.i(
                    TAG,
                    "hid loaded module libraries"
                )
                if (!hidden) WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure {
                WeLogger.e(TAG, "failed to hide module libraries", it)
            }
    }

    private fun checkWxForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nthis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "hidden api is not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = runCatching {
            Class.forName("android.app.ContextImpl")
        }.getOrElse { return false }

        var mActivityToken: Field? = null
        var mToken: Field? = null

        try {
            mActivityToken = kContextImpl.getDeclaredField("mActivityToken")
        } catch (_: NoSuchFieldException) {
        }
        try {
            mToken = kContextImpl.getDeclaredField("mToken")
        } catch (_: NoSuchFieldException) {
        }

        return mActivityToken != null || mToken != null
    }
}
