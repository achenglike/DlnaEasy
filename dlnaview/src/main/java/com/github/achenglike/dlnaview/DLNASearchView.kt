package com.github.achenglike.dlnaview

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.model.meta.LocalDevice
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.types.DeviceType
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.registry.RegistryListener


class DLNASearchView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val stopSearching = Runnable { stopRefresh() }
    private val dlnaRenderer: DeviceType = UDADeviceType("MediaRenderer")
    private val imgRefresh by lazy { findViewById<ImageView>(R.id.dlna_refresh_device) }
    private val rvDevices by lazy { findViewById<RecyclerView>(R.id.dlna_device_list) }
    private val pgLoading by lazy { findViewById<ProgressBar>(R.id.dlna_search_loading) }
    private val tvLoading by lazy { findViewById<TextView>(R.id.dlna_search_loading_label) }
    private val tvResult by lazy { findViewById<TextView>(R.id.dlna_search_result) }

    private var searchLayout = R.layout.dlna_search_view

    var deviceSelectCallback: ((device: RemoteDevice) -> Unit)? = null

    private val deviceAdapter by lazy {
        DeviceAdapter {
            deviceSelectCallback?.invoke(it)
        }
    }
    private val searching = MutableLiveData(false)
    private var searchTriggered = false

    private var upnpService: AndroidUpnpService? = null
    private val discoverListener by lazy {
        object : RegistryListener {
            override fun remoteDeviceDiscoveryStarted(
                registry: Registry?,
                device: RemoteDevice?
            ) {
                log("remoteDeviceDiscoveryStarted")
            }

            override fun remoteDeviceDiscoveryFailed(
                registry: Registry?,
                device: RemoteDevice?,
                ex: Exception?
            ) {
                log("remoteDeviceDiscoveryFailed")
            }

            override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
                if (device?.type == dlnaRenderer) {
                    log("remoteDeviceAdded")
                    post {
                        deviceAdapter.addItem(device)
                    }
                }
            }

            override fun remoteDeviceUpdated(registry: Registry?, device: RemoteDevice?) {
            }

            override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
                if (device?.type == dlnaRenderer) {
                    log("remoteDeviceRemoved")
                    post {
                        deviceAdapter.remoteItem(device)
                    }
                }
            }

            override fun localDeviceAdded(registry: Registry?, device: LocalDevice?) {
            }

            override fun localDeviceRemoved(registry: Registry?, device: LocalDevice?) {
            }

            override fun beforeShutdown(registry: Registry?) {
                log("beforeShutdown")
            }

            override fun afterShutdown() {
                log("afterShutdown")
            }
        }
    }

    private val searchingObserver: Observer<Boolean> =
        Observer<Boolean> { t ->
            if (t == true) {
                val animation =
                    AnimationUtils.loadAnimation(this@DLNASearchView.context, R.anim.rotate)
                imgRefresh.startAnimation(animation)
                imgRefresh.isClickable = false
            } else {
                imgRefresh.clearAnimation()
                imgRefresh.isClickable = true
            }

            if (t == true) {
                pgLoading.visibility = View.VISIBLE
                tvLoading.visibility = View.VISIBLE
                tvResult.visibility = View.GONE
            } else {
                pgLoading.visibility = View.GONE
                tvLoading.visibility = View.GONE
                tvResult.visibility = View.VISIBLE

                if (deviceAdapter.itemSize() == 0) {
                    if (searchTriggered) {
                        tvResult.setTextColor(resources.getColor(R.color.dlna_error_color))
                        tvResult.setText(R.string.dlna_searched_fail)
                    }
                } else {
                    tvResult.setTextColor(resources.getColor(R.color.dlna_device_name_color))
                    tvResult.setText(R.string.dlna_searched_success)
                }
            }
        }

    init {
        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.DLNASearchView, 0, 0)
            try {
                searchLayout =
                    a.getResourceId(R.styleable.DLNASearchView_dlna_search_layout_id, searchLayout)
            } finally {
                a.recycle()
            }
        }

        LayoutInflater.from(context).inflate(searchLayout, this)

        rvDevices.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceAdapter
        }

        imgRefresh.setOnClickListener { refresh() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            log("onServiceConnected")
            upnpService = service as? AndroidUpnpService
            upnpService?.registry?.addListener(discoverListener)
            refresh()
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            log("onServiceDisconnected")
            upnpService?.registry?.removeListener(discoverListener)
            upnpService = null
        }

        override fun onBindingDied(name: ComponentName?) {
            log("onBindingDied")
            super.onBindingDied(name)
        }

        override fun onNullBinding(name: ComponentName?) {
            log("onNullBinding")
            super.onNullBinding(name)
        }

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        searching.observeForever(searchingObserver)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(stopSearching)
        stopRefresh()
        log("onDetachedFromWindow")
        searching.removeObserver(searchingObserver)
    }

    fun refresh() {
        log("refresh called")
        deviceAdapter.clear()
        context.applicationContext.bindService(
            Intent(context, AndroidUpnpServiceImpl::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        // mxSeconds: 它设置了 M-SEARCH 请求消息中的 MX（最大等待）头字段，以指示搜索应该等待响应的时间。当 UPnP
        // 控制点发送 M-SEARCH 请求时，它将等待响应，等待的时间是 MX 头字段指定的值。如果在等待时间内未收到响应，则该请求超时
        upnpService?.controlPoint?.search(60)
        searching.postValue(true)
        removeCallbacks(stopSearching)
        postDelayed(stopSearching, 1 * 60 * 1000)
        searchTriggered = true
    }

    private fun stopRefresh() {
        if (searching.value == true) {
            searching.postValue(false)
            log("stopRefresh post false")

            context.applicationContext.unbindService(serviceConnection)
        }
    }
}


private class DeviceAdapter(private val callback: (device: RemoteDevice) -> Unit) :
    Adapter<DeviceAdapter.ViewHolder>() {

    private val items: MutableList<RemoteDevice> = mutableListOf()

    fun itemSize() = items.size

    fun addItem(item: RemoteDevice) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateDevices(items: List<RemoteDevice>) {
        this.items.clear()
        this.items.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.dlna_device_item, parent, false)
        return ViewHolder(view, callback)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int {
        return items.size
    }

    fun remoteItem(remoteDevice: RemoteDevice) {
        val idx = items.indexOf(remoteDevice)
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    fun clear() {
        val size = items.size
        if (size > 0) {
            items.clear()
            notifyItemRangeRemoved(0, size)
        }
    }

    class ViewHolder(itemView: View, callback: (device: RemoteDevice) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val tvName = itemView.findViewById<TextView>(R.id.device_name)
        private var itemNow: RemoteDevice? = null

        init {
            itemView.setOnClickListener {
                callback.invoke(
                    itemNow ?: throw IllegalStateException("item clicked but device is null")
                )
            }
        }

        fun bind(item: RemoteDevice) {
            this.itemNow = item
            tvName.text = getDeviceName(item)
        }
    }
}