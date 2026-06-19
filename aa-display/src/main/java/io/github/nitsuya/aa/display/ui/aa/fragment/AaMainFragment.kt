package io.github.nitsuya.aa.display.ui.aa.fragment

import android.annotation.SuppressLint
import android.content.*
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.view.InputDeviceCompat
import com.google.android.gms.car.CarFirstPartyManager
import com.topjohnwu.superuser.Shell
import io.github.duzhaokun123.template.bases.BaseFragment
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.databinding.FragmentAaMainBinding
import io.github.nitsuya.aa.display.ui.aa.AaDisplayActivityKt
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.tryOrNull
import io.github.nitsuya.aa.display.util.getGmsCarFirstPartyManager
import io.github.nitsuya.aa.display.util.startCarAaDisplay
import io.github.nitsuya.aa.display.util.startCarTelecom
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import io.github.nitsuya.template.bases.runMain


class AaMainFragment : BaseFragment<FragmentAaMainBinding>(FragmentAaMainBinding::class.java), TextureView.SurfaceTextureListener {

    private var displayId: Int = Display.INVALID_DISPLAY
    private var repairDownTime = Long.MIN_VALUE
    private var isForeground = false

    // VD 尺寸单一权威源 = surface 回调。lastApplied* = 最近一次**真的 resize 过**的尺寸
    // （初始 0，不从 post() 采样预设——避免与 system_server 持久 VD 真实尺寸脱钩误判）。
    private var lastAppliedW = 0
    private var lastAppliedH = 0

    private fun displayDpi(): Int = AADisplayConfig.VirtualDisplayDpi.get(config).let {
        if (it <= 50) resources.displayMetrics.densityDpi else it
    }

    companion object {
        private const val TAG = "AADisplay_AaMainFragment"
    }
    private lateinit var config: SharedPreferences

    private var car:Car? = null
    private var carManager: CarFirstPartyManager? = null
    private val carConnectionCallback = object: CarConnectionCallback(){
        override fun onConnected(car: Car) {
            if(carManager == null) {
                carManager = car.getGmsCarFirstPartyManager()
            }
        }
        override fun onDisconnected(car: Car) {
            carManager = null
        }
    }

    fun startActivity(){
        carManager.startCarAaDisplay()
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        val voiceAssistShell by lazy { AADisplayConfig.VoiceAssistShell.get(config) }
        fun startVoiceAssist() {
            voiceAssistShell?.let {
                CoreApi.displayPower(true)
                tryOrNull {
                    Shell.cmd(
                        it.let {
                            it.replace("\${DisplayId}", (if(displayId == Display.INVALID_DISPLAY) Display.DEFAULT_DISPLAY else displayId).toString())
                        }
                    ).exec()
                }
            }
        }
        override fun onReceive(context: Context?, intent: Intent) {
            when(intent.action){
                AABroadcastConst.ACTION_SCREEN_CONTROL -> {
                    when(val action = intent.getIntExtra(AABroadcastConst.EXTRA_ACTION, 0)){
                        KeyEvent.KEYCODE_FEATURED_APP_1 -> carManager.startCarTelecom()
                        KeyEvent.KEYCODE_SEARCH -> startVoiceAssist()
                        KeyEvent.KEYCODE_POWER -> CoreApi.toggleDisplayPower()
                        else -> {
                            if(!isForeground){
                                carManager.startCarAaDisplay()
                                return
                            }
                            when(action){
                                KeyEvent.KEYCODE_DEMO_APP_1 -> CoreApi.moveSecondTaskToFront()
                                KeyEvent.KEYCODE_BACK -> {
                                    // 最近任务浮层在显示时，BACK 先关浮层（否则 BACK 被注入
                                    // 镜像 VirtualDisplay 的 app，浮层关不掉——用户实测 bug）。
                                    val fm = this@AaMainFragment.parentFragmentManager
                                    if (fm.findFragmentByTag("RecentTask") != null) {
                                        runMain { AaDisplayActivityKt.hideRecentTask(fm) }
                                    } else {
                                        CoreApi.pressKey(action)
                                    }
                                }
                                KeyEvent.KEYCODE_HOME -> CoreApi.startLauncher()
                                KeyEvent.KEYCODE_APP_SWITCH -> runMain {
                                    AaDisplayActivityKt.showRecentTask(this@AaMainFragment.parentFragmentManager)
                                }
                            }
                        }
                    }
                }
                AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL -> {
                    val action = intent.getIntExtra(AABroadcastConst.EXTRA_ACTION, 0)
                    when (intent.getIntExtra(AABroadcastConst.EXTRA_TYPE, 0)) {
                        0 -> {
                            when(action){
                                KeyEvent.KEYCODE_SEARCH                /* 84*/ -> startVoiceAssist()
                                KeyEvent.KEYCODE_MEDIA_NEXT            /* 87*/,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS        /* 88*/,
                                KeyEvent.KEYCODE_HEADSETHOOK           /* 79*/,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE      /* 85*/,
                                KeyEvent.KEYCODE_MEDIA_STOP            /* 86*/,
                                KeyEvent.KEYCODE_MEDIA_REWIND          /* 89*/,
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD    /* 90*/,
                                KeyEvent.KEYCODE_MUTE                  /* 91*/,
                                KeyEvent.KEYCODE_MEDIA_PLAY            /*126*/,
                                KeyEvent.KEYCODE_MEDIA_PAUSE           /*127*/,
                                KeyEvent.KEYCODE_MEDIA_RECORD          /*130*/-> CoreApi.pressKey(action)
                                else -> CoreApi.toast("方控[$action]未设置")
                            }
                        }
                        1 -> {
                            when(action){
                                KeyEvent.KEYCODE_SEARCH              /* 84*/ -> startVoiceAssist()
                                KeyEvent.KEYCODE_MEDIA_REWIND         /* 89*/ -> CoreApi.startLauncher()
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD   /* 90*/ -> CoreApi.moveSecondTaskToFront()
                                else -> CoreApi.toast("方控长按[$action]未设置")
                            }
                        }
                        2 -> {
                            CoreApi.toast("方控双击[$action]未设置")
                        }
                    }
                }
            }
        }
    }

    override fun initViews() {
        config = this.requireContext().getSharedPreferences(AADisplayConfig.ConfigName, Context.MODE_PRIVATE)
        baseBinding.tvDisplay.post  {
            // 仅这里建 adapter + 全套 setup（触摸/接收器/Car）。**不**预设 lastApplied，
            // 把 VD 尺寸的权威来源交给 surface 回调（syncVd）—— post() 采样可能过早/过时，
            // 不可信。lastApplied 留 0 → 首个 Available 必定把 VD resize 到 surface 真实尺寸，
            // 纠正持久 VD（system_server 跨重启残留）与 post() 误采样。
            Log.i(TAG, "onCreateDisplay(init): tvDisplay ${baseBinding.tvDisplay.width}x${baseBinding.tvDisplay.height} dpi=${displayDpi()}")
            CoreApi.onCreateDisplay(
                baseBinding.tvDisplay.width,
                baseBinding.tvDisplay.height,
                displayDpi(),
                object : IVirtualDisplayCreatedListener.Stub() {
                    @SuppressLint("ClickableViewAccessibility")
                    override fun onAvailableDisplay(displayId: Int, create: Boolean) {
                        this@AaMainFragment.displayId = displayId
                        runMain {
                            if (baseBinding.tvDisplay.isAvailable) {
                                CoreApi.setDisplaySurface(Surface(baseBinding.tvDisplay.surfaceTexture))
                            }
                            baseBinding.tvDisplay.surfaceTextureListener = this@AaMainFragment
                            baseBinding.tvDisplay.setOnTouchListener { _, e ->
                                val uptimeMillis = SystemClock.uptimeMillis()
                                if (e.action === MotionEvent.ACTION_DOWN) {
                                    repairDownTime = uptimeMillis
                                }
                                val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(e.pointerCount)
                                val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(e.pointerCount)
                                for (i in 0 until e.pointerCount) {
                                    pointerCoords[i] = MotionEvent.PointerCoords().apply {
                                        e.getPointerCoords(i, this)
                                    }
                                    pointerProperties[i] = MotionEvent.PointerProperties().apply {
                                        e.getPointerProperties(i, this)
                                    }
                                }
                                //val newEvent = MotionEvent.obtain(repairDownTime, uptimeMillis, e.action, e.pointerCount, pointerProperties, pointerCoords, e.metaState, e.buttonState, e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags)
                                val newEvent = MotionEvent.obtain(repairDownTime, uptimeMillis, e.action, e.pointerCount, pointerProperties, pointerCoords,0,0,1.0f,1.0f,0,0,0,0)
                                newEvent.source = InputDeviceCompat.SOURCE_TOUCHSCREEN
                                CoreApi.touch(newEvent)
                                newEvent.recycle()
                                true
                            }

                            ContextCompat.registerReceiver(this@AaMainFragment.requireContext(), broadcastReceiver, IntentFilter().apply {
                                addAction(AABroadcastConst.ACTION_SCREEN_CONTROL)
                                addAction(AABroadcastConst.ACTION_STEERING_WHEEL_CONTROL)
                            }, ContextCompat.RECEIVER_EXPORTED)

                        }
                    }
                }
            )
        }

        car?.disconnect()
        car = Car.createCar(this.requireContext(), carConnectionCallback).apply {
            connect()
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        CoreApi.onDestroyDisplay()
        tryOrNull {
            this@AaMainFragment.requireContext().unregisterReceiver(broadcastReceiver)
        }
        car?.disconnect()
        car = null
    }

    // VD 尺寸单一权威源 = surface 回调（覆盖 Available 重建 + SizeChanged 两条路径）。
    // - displayId 未就绪（initViews 全套 onCreateDisplay 还没回调）→ 只 setSurface，不抢着
    //   建/resize（否则可能用 slim listener 抢先建 VD 导致触摸/接收器没注册）。
    // - 尺寸 == lastApplied（上次真 resize 过的值）→ 只 setSurface（无冗余 resize）。
    // - 否则（含首个有效回调 lastApplied=0）→ **必定** resize VD 到 surface 权威尺寸，
    //   纠正 system_server 持久 VD 残留 / post() 误采样。竖屏：surface 恒为竖向尺寸，
    //   首回调把 VD 收敛到竖向后即稳定，结果 == 验收的竖屏（VD==竖向 surface，无黑边）。
    private fun syncVd(surface: SurfaceTexture, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || displayId == Display.INVALID_DISPLAY) {
            CoreApi.setDisplaySurface(Surface(surface)); return
        }
        if (width == lastAppliedW && height == lastAppliedH) {
            CoreApi.setDisplaySurface(Surface(surface)); return
        }
        Log.i(TAG, "syncVd resize VD -> ${width}x${height} (was ${lastAppliedW}x${lastAppliedH})")
        lastAppliedW = width
        lastAppliedH = height
        // adapter 已存在 → CoreManagerService.onCreateDisplay 走 onReconnected(resize VD)
        // + DisplayWindow.onResume；slim listener 只重设 surface，不重注册触摸/接收器/Car。
        CoreApi.onCreateDisplay(width, height, displayDpi(), object : IVirtualDisplayCreatedListener.Stub() {
            override fun onAvailableDisplay(displayId: Int, create: Boolean) {
                this@AaMainFragment.displayId = displayId
                runMain { CoreApi.setDisplaySurface(Surface(surface)) }
            }
        })
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = syncVd(surface, width, height)
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = syncVd(surface, width, height)
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean  = false
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
