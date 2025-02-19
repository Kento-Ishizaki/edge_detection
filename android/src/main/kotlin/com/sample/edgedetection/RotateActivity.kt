package com.sample.edgedetection

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Base64
import com.google.gson.Gson
import com.sample.edgedetection.model.Image
import kotlinx.android.synthetic.main.activity_rotate.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class RotateActivity : AppCompatActivity() {
    private lateinit var sp: SharedPreferences
    private lateinit var decodedImg: Bitmap
    private lateinit var images: ArrayList<Image>
    private var index = 0
    private val matrix = Matrix()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rotate)
        sp = getSharedPreferences(SPNAME, Context.MODE_PRIVATE)
        setImage()
        setBtnListener()
    }

    private fun setImage() {
        // タップされた画像のインデックスを取得
        index = intent.getIntExtra(INDEX, 0)

        val json = sp.getString(IMAGE_ARRAY, null)
        images = jsonToImageArray(json!!)
        val b64Image = images[index].b64
        val imageBytes = Base64.decode(b64Image, Base64.DEFAULT)
        decodedImg = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        imageView.setImageBitmap(decodedImg)
    }

    private fun setBtnListener() {
        matrix.setRotate(90F, decodedImg.width/2F, decodedImg.height/2F)
        rotateBtn.setOnClickListener {
            decodedImg = Bitmap.createBitmap(decodedImg, 0, 0, decodedImg.width, decodedImg.height, matrix, true)
            imageView.setImageBitmap(decodedImg)
        }

        cancelBtn.setOnClickListener {
            toDisableBtns()
            navToImageListScrn()
        }

        decisionBtn.setOnClickListener {
            toDisableBtns()
            thread {
                setUpdatedImage()
                navToImageListScrn()
            }
        }
    }

    private fun toDisableBtns() {
        cancelBtn.isEnabled = false
        decisionBtn.isEnabled = false
    }

    private fun setUpdatedImage() {
        val baos = ByteArrayOutputStream()
        decodedImg.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val b = baos.toByteArray()
        val updatedImg = Base64.encodeToString(b, Base64.DEFAULT)
        val image = Image(updatedImg)
        images[index] = image
        val editor = sp.edit()
        editor.putString(IMAGE_ARRAY, gson.toJson(images)).apply()
    }

    private fun navToImageListScrn() {
        val intent = Intent(this, ImageListActivity::class.java)
        intent.putExtra(INDEX, index)
        startActivityForResult(intent, 100)
        finish()
    }
}