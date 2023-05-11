package com.github.achenglike.dlnaview

import android.util.Log
import org.fourthline.cling.model.meta.Device
import org.fourthline.cling.support.contentdirectory.DIDLParser
import org.fourthline.cling.support.model.DIDLContent
import org.fourthline.cling.support.model.ProtocolInfo
import org.fourthline.cling.support.model.Res
import org.fourthline.cling.support.model.container.Container
import org.fourthline.cling.support.model.item.VideoItem
import org.seamless.util.MimeType
import java.time.LocalTime

fun log(info: String) {
    Log.d("dlna", info)
}

fun VideoData.metadata(): String? {
    // container: creator title    item:mediasize url duration resolution id title creator
    val container = Container()
    container.creator = creator
    container.id = "0"
    container.parentID = "-1"
    container.title = title
    val itemRes =
        Res(MimeType(ProtocolInfo.WILDCARD, ProtocolInfo.WILDCARD), mediaSize, url)
    itemRes.duration = duration
    itemRes.resolution = resolution
    container.addItem(VideoItem(id, "0", title, creator, itemRes))
    val didlContent = DIDLContent()
    for (c in container.containers) {
        didlContent.addContainer(c)
    }
    for (item in container.items) {
        didlContent.addItem(item)
    }
    return try {
        DIDLParser().generate(didlContent, true)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}


internal fun getDeviceName(device: Device<*, *, *>): String? {
    val name: String? = if (device.details != null && device.details.friendlyName != null) {
        device.details.friendlyName
    } else {
        device.displayString
    }
    return name
}


// hh:mm:ss -> second
fun String.timeToSeconds(): Long {
    val parts = this.split(":").reversed()
    var totalSeconds: Long = 0
    for ((index, part) in parts.withIndex()) {
        val secondsInPart = part.toLongOrNull() ?: 0
        totalSeconds += when (index) {
            0 -> secondsInPart // seconds
            1 -> secondsInPart * 60 // minutes
            2 -> secondsInPart * 3600 // hours
            else -> 0
        }
    }
    return totalSeconds
}

// second -> hh:mm:ss
fun Long.fmtTime(): String {
    val hours = this / 3600
    val minutes = this % 3600 / 60
    val secs = this % 60
    return String.format("%02d:%02d:%02d", hours, minutes, secs)
}
