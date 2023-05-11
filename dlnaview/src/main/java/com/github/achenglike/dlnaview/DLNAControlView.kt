package com.github.achenglike.dlnaview

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.controlpoint.ActionCallback
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.Service
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.support.avtransport.callback.*
import org.fourthline.cling.support.model.PositionInfo
import org.fourthline.cling.support.model.TransportInfo
import org.fourthline.cling.support.model.TransportState
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume
import org.fourthline.cling.support.renderingcontrol.callback.SetMute
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume
import java.lang.Long.max
import java.lang.Long.min

class DLNAControlView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {
    private val avTransportService = UDAServiceType("AVTransport")
    private val renderControlService = UDAServiceType("RenderingControl")
    private val progressGetTask = Runnable { getProgress() }

    private val deviceName: TextView by lazy { findViewById(R.id.dlna_render_device_name) }
    private val stop: ImageView by lazy { findViewById(R.id.dlna_stop) }
    private val renderChange: TextView by lazy { findViewById(R.id.dlna_render_change) }
    private val mediaName: TextView by lazy { findViewById(R.id.dlna_media_name) }
    private val play: ImageView by lazy { findViewById(R.id.dlna_play) }
    private val volumeAdd: ImageView by lazy { findViewById(R.id.dlna_volume_add) }
    private val volumeReduce: ImageView by lazy { findViewById(R.id.dlna_volume_reduce) }
    private val fastback: ImageView by lazy { findViewById(R.id.dlna_fast_back) }
    private val fastForward: ImageView by lazy { findViewById(R.id.dlna_fast_forward) }
    private val playTime: TextView by lazy { findViewById(R.id.dlna_play_time) }
    private val durationBar: SeekBar by lazy { findViewById(R.id.dlna_duration_bar) }
    private val totalTime: TextView by lazy { findViewById(R.id.dlna_play_total_time) }
    private var curVolume = 10L
    private var curProgress = 0L
    private var totalProgress = 0L
    private var pendingSeek: String? = null
    private var pendingStart: (() -> Unit)? = null
    private var lastVideo: VideoData? = null

    private var controlLayout = R.layout.dlna_control_view
    private var upnpService: AndroidUpnpService? = null
    private var device: RemoteDevice? = null
    private val playing = MutableLiveData(false)
    private val progressValue = MutableLiveData<Pair<String, String>>()


    private var changeDeviceListener: (() -> Unit)? = null
    private var stopListener: (() -> Unit)? = null

    private val playingObserver = Observer<Boolean> {
        val iconRes = if (it == true) {
            R.drawable.dlna_vector_pause
        } else {
            R.drawable.dlna_vector_play
        }
        play.setImageDrawable(AppCompatResources.getDrawable(getContext(), iconRes))
    }

    private val progressValueObserver = Observer<Pair<String, String>> {
        val (cur, tol) = it
        playTime.text = cur
        totalTime.text = tol
        tol.timeToSeconds().let { second ->
            if (second > 0) {
                totalProgress = second
            }
            durationBar.max = second.toInt()
        }
        curProgress = cur.timeToSeconds()

        val newValue = curProgress.toInt()
        val lastValue = durationBar.getTag(R.id.dlna_progress_tag) as? Int ?:0
        durationBar.setTag(R.id.dlna_progress_tag, newValue)
        // 处理seek的时候会先得到progress=0的问题 非0 或者 是0且上一次也是0 进行更新
        if (newValue > 0 || lastValue == 0) {
            durationBar.progress = curProgress.toInt()
        }
    }

    init {
        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.DLNAControlView, 0, 0)
            try {
                controlLayout =
                    a.getResourceId(
                        R.styleable.DLNAControlView_dlna_control_layout_id,
                        controlLayout
                    )
            } finally {
                a.recycle()
            }
        }

        LayoutInflater.from(context).inflate(controlLayout, this)

        volumeAdd.setOnClickListener {
            curVolume = min(curVolume + 5L, 100L)
            setVolume(curVolume)
        }

        volumeReduce.setOnClickListener {
            curVolume = max(curVolume - 5L, 0L)
            setVolume(curVolume)
        }

        fastback.setOnClickListener {
            curProgress = max(curProgress - 5L, 0L)
            seekTo(curProgress.fmtTime())
        }

        fastForward.setOnClickListener {
            curProgress = min(totalProgress, curProgress + 5)
            seekTo(curProgress.fmtTime())
        }

        durationBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                progressValue.postValue(progress.toLong().fmtTime() to totalProgress.fmtTime())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { bar ->
                    curProgress = bar.progress.toLong()
                    progressValue.postValue(curProgress.fmtTime() to totalProgress.fmtTime())
                    seekTo(curProgress.fmtTime())
                }
            }
        })

        play.setOnClickListener {
            if (playing.value == true) {
                pause()
            } else {
                if (curProgress == 0L && lastVideo != null) {
                    start(lastVideo!!.pureClone())
                } else {
                    play()
                }

            }
        }

        renderChange.setOnClickListener {
            changeDeviceListener?.invoke()
        }
        stop.setOnClickListener {
            stop()
            stopListener?.invoke()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            log("onServiceConnected")
            upnpService = service as? AndroidUpnpService
            pendingStart?.invoke()
            pendingStart = null
            this@DLNAControlView.postDelayed(progressGetTask, 1000)
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            log("onServiceDisconnected")
            upnpService = null
            this@DLNAControlView.removeCallbacks(progressGetTask)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.applicationContext.bindService(
            Intent(context, AndroidUpnpServiceImpl::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        playing.observeForever(playingObserver)
        progressValue.observeForever(progressValueObserver)

    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressValue.removeObserver(progressValueObserver)
        playing.removeObserver(playingObserver)
        context.applicationContext.unbindService(serviceConnection)
        upnpService?.get()?.shutdown()
    }

    private fun execute(actionCallback: ActionCallback) {
        upnpService?.controlPoint?.execute(actionCallback)
    }

    private fun getProgress() {
        removeCallbacks(progressGetTask)
        postDelayed(progressGetTask, 1000)
        getPositionInfo { success, positionInfo ->
            val progressNow = if (success && positionInfo != null) {
                positionInfo.absTime to positionInfo.trackDuration
            } else "00:00:00" to "00:00:00"
            progressValue.postValue(progressNow)
            if ("00:00:00" == positionInfo?.absTime && playing.value == true) {
                getTransportInfo { suc, transportInfo ->
                    if (suc && transportInfo?.currentTransportState == TransportState.STOPPED) {
                        // 已经停止了播放
                        playing.postValue(false)
                    }
                }
            }
        }
    }

    private fun play() {
        device?.findService(avTransportService)?.let { avtService ->
            execute(object : Play(avtService) {
                override fun success(invocation: ActionInvocation<*>?) {
                    super.success(invocation)
                    playing.postValue(true)
                    log("$avTransportService play success")
                    pendingSeek?.let {
                        seekTo(it)
                        pendingSeek = null
                    }
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$avTransportService play fail: $defaultMsg")
                }
            })
        } ?: kotlin.run {
            log("$avTransportService play fail device is null or findService get null")
        }
    }

    private fun pause() {
        device?.findService(avTransportService)?.let { avtService ->
            execute(object : Pause(avtService) {
                override fun success(invocation: ActionInvocation<*>?) {
                    super.success(invocation)
                    playing.postValue(false)
                    log("$avTransportService pause success")
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$avTransportService pause fail: $defaultMsg")
                }
            })
        } ?: kotlin.run {
            log("$avTransportService pause fail device is null or findService get null")
        }
    }


    private fun stop() {
        device?.findService(avTransportService)?.let { avtService ->
            execute(object : Stop(avtService) {
                override fun success(invocation: ActionInvocation<*>?) {
                    super.success(invocation)
                    playing.postValue(false)
                    log("$avTransportService stop success")
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$avTransportService stop fail: $defaultMsg")
                }
            })
        } ?: kotlin.run {
            log("$avTransportService stop fail device is null or findService get null")
        }
    }

    private fun seekTo(time: String) {
        device?.findService(avTransportService)?.let { avtService ->
            execute(object : Seek(avtService, time) {
                override fun success(invocation: ActionInvocation<*>?) {
                    super.success(invocation)
                    progressValue.postValue(time to (progressValue.value?.second ?: "00:00:00"))
                    log("$avTransportService seek success $time")
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$avTransportService seek fail: $defaultMsg")
                }
            })
        } ?: kotlin.run {
            log("$avTransportService seek fail device is null or findService get null")
        }
    }

    private fun setVolume(volume: Long) {
        device?.findService(renderControlService)?.let { renderService ->
            log(renderService.getAction("SetVolume").name)
            execute(object : SetVolume(renderService, volume) {
                override fun success(invocation: ActionInvocation<*>?) {
                    super.success(invocation)
                    log("$renderControlService volume success $volume")
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$renderControlService volume fail: $defaultMsg")
                }
            })
        } ?: kotlin.run {
            log("$renderControlService volume fail device is null or findService get null")
        }
    }

    private fun mute(desiredMute: Boolean) {
        device?.findService(renderControlService)?.let { avtService ->
            execute(object : SetMute(avtService, desiredMute) {
                override fun success(invocation: ActionInvocation<*>?) {
                    super.success(invocation)
                    log("$renderControlService mute($desiredMute) success")
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$renderControlService mute($desiredMute) fail: $defaultMsg")
                }
            })
        } ?: kotlin.run {
            log("$renderControlService mute($desiredMute) fail device is null or findService get null")
        }
    }

    private fun getTransportInfo(callback: (success: Boolean, transportInfo: TransportInfo?) -> Unit) {
        device?.findService(avTransportService)?.let { avtService ->
            execute(object : GetTransportInfo(avtService) {
                override fun received(
                    invocation: ActionInvocation<out Service<*, *>>?,
                    transportInfo: TransportInfo?
                ) {
                    log("$avTransportService get transport info success: ${transportInfo?.currentTransportStatus} ${transportInfo?.currentTransportState}")
                    callback.invoke(true, transportInfo)
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$avTransportService get transport info fail: $defaultMsg")
                    callback.invoke(false, null)
                }
            })
        } ?: kotlin.run {
            callback.invoke(false, null)
            log("$avTransportService get transport info fail device is null or findService get null")
        }
    }

    private fun getPositionInfo(callback: (success: Boolean, positionInfo: PositionInfo?) -> Unit) {
        device?.findService(avTransportService)?.let { avtService ->
            execute(object : GetPositionInfo(avtService) {
                override fun received(
                    invocation: ActionInvocation<*>?,
                    positionInfo: PositionInfo
                ) {
//                    log("$avTransportService get position success: ${positionInfo.absTime}")
                    callback.invoke(true, positionInfo)
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$avTransportService get position fail: $defaultMsg")
                    callback.invoke(false, null)
                }
            })
        } ?: kotlin.run {
            callback.invoke(false, null)
            log("$avTransportService get position fail device is null or findService get null")
        }
    }


    private fun getVolume(callback: (success: Boolean, volume: Int) -> Unit) {
        device?.findService(renderControlService)?.let { avtService ->
            execute(object : GetVolume(avtService) {
                override fun received(invocation: ActionInvocation<*>?, currentVolume: Int) {
                    log("$renderControlService get volume success: $currentVolume")
                    callback.invoke(true, currentVolume)
                }

                override fun failure(
                    invocation: ActionInvocation<*>?,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    log("$renderControlService get volume fail: $defaultMsg")
                    callback.invoke(false, 0)
                }
            })
        } ?: kotlin.run {
            callback.invoke(false, 0)
            log("$renderControlService get volume fail device is null or findService get null")
        }
    }

    private fun start(videoData: VideoData) {
        lastVideo = videoData
        log("called start video data: $videoData")
        device?.let {
            it.findService(avTransportService)?.let { avtService ->
                log("called start execute")
                execute(object : SetAVTransportURI(
                    avtService,
                    videoData.url,
                    videoData.metadata() ?: ""
                ) {
                    override fun success(invocation: ActionInvocation<out Service<*, *>>?) {
                        log("$avTransportService start success")
                        play()
                    }

                    override fun failure(
                        invocation: ActionInvocation<*>?,
                        operation: UpnpResponse,
                        defaultMsg: String
                    ) {
                        log("$avTransportService start fail: $defaultMsg")
                    }
                })
            } ?: kotlin.run {
                log("$avTransportService get volume fail device is null or findService get null")
            }

        } ?: kotlin.run {
            log("$device is null could not start")
        }
    }

    /**
     * 设置播放设备
     */
    fun setDevice(device: RemoteDevice) {
        this.device?.let { throw IllegalStateException("setDevice should only call once") }
            ?: kotlin.run {
                this.device = device
                deviceName.text = getDeviceName(device) ?: "unknow device"
            }
    }

    /**
     * 设置播放的媒体信息
     */
    fun playMedia(videoData: VideoData) {
        mediaName.text = videoData.title
        progressValue.postValue(videoData.startPoint to videoData.duration)
        curProgress = videoData.startPoint.timeToSeconds()
        totalProgress = videoData.duration.timeToSeconds()
        if (upnpService == null) {
            pendingStart = {
                start(videoData)
                if ("00:00:00" != videoData.startPoint) {
                    pendingSeek = videoData.startPoint
                }
            }
        } else {
            start(videoData)
            if ("00:00:00" != videoData.startPoint) {
                pendingSeek = videoData.startPoint
            }
        }
    }

    fun setChangeDeviceListener(listener: () -> Unit) {
        this.changeDeviceListener = listener
    }

    fun setStopListener(listener: () -> Unit) {
        this.stopListener = listener
    }
}