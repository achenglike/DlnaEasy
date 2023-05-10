package com.github.achenglike.dlnaview

data class VideoData(
    val mediaSize: Long,
    val url: String,
    val resolution: String,
    val id: String,
    val title: String,
    val creator: String,
    val duration: String = "00:00:00",
    val startPoint: String = "00:00:00",
) : java.io.Serializable

fun VideoData.pureClone(): VideoData {
    return VideoData(mediaSize, url, resolution, id, title, creator, duration)
}
