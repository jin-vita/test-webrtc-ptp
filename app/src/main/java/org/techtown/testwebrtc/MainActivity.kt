package org.techtown.testwebrtc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.techtown.testwebrtc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val recordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (camera == PackageManager.PERMISSION_GRANTED && recordAudio == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, PartnerDetectorActivity::class.java))
            finish()
        } else {
            binding.permission.setOnClickListener {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    1
                )
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val permissionDenied = grantResults.any { it == PackageManager.PERMISSION_DENIED }
            if (permissionDenied) {
                Toast.makeText(this, R.string.strange, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, PartnerDetectorActivity::class.java))
                finish()
            }
        } else {
            Toast.makeText(this, R.string.strange, Toast.LENGTH_SHORT).show()
        }
    }
}