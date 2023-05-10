package com.github.achenglike.dlna

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.achenglike.dlnaview.DLNAControlView
import com.github.achenglike.dlnaview.VideoData
import org.fourthline.cling.model.meta.RemoteDevice

var useDevice: RemoteDevice? = null

class ControlActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)
        val controlView = findViewById<DLNAControlView>(R.id.controlView)

        controlView.setDevice(useDevice?: throw IllegalStateException("useDevice is null"))

        val videoData: VideoData? = intent.getSerializableExtra("video") as? VideoData

        controlView.playMedia(videoData?: throw IllegalStateException("video data is null"))

        controlView.setChangeDeviceListener {
            finish()
        }
        controlView.setStopListener {
            finish()
        }

    }
}