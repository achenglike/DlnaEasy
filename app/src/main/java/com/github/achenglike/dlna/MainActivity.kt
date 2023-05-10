package com.github.achenglike.dlna

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.github.achenglike.dlnaview.DLNASearchView
import com.github.achenglike.dlnaview.VideoData

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val searchView = findViewById<DLNASearchView>(R.id.searchView)
        searchView.deviceSelectCallback = {
            useDevice = it
            startActivity(Intent(this, ControlActivity::class.java).apply {
                putExtra("video", VideoData(5510872, "http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4", "1920x1080", "1", "big_buck_bunny", "vorwaerts", duration = "00:01:00", "00:00:00"))
            })
        }
    }
}