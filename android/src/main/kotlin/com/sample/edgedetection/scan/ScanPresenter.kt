package com.sample.edgedetection.scan

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.hardware.Camera.ShutterCallback
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import com.google.gson.Gson
import com.sample.edgedetection.*
import com.sample.edgedetection.model.Image
import com.sample.edgedetection.processor.Corners
import com.sample.edgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class ScanPresenter constructor(private val context: Context, private val iView: IScanView.Proxy, private val scanActv: ScanActivity) :
    SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val TAG: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var isBusy: Boolean = false
    private var soundSilence: MediaPlayer = MediaPlayer()
    private var sp: SharedPreferences
    var images = mutableListOf<Image>()
    private var matrix: Matrix
    private val gson = Gson()


    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
        soundSilence = MediaPlayer.create(this.context, R.raw.silence)
        sp = context.getSharedPreferences(SPNAME, Context.MODE_PRIVATE)
        matrix = Matrix()
        matrix.postRotate(90F)
    }

    // SharedPrefに画像がある場合、変数に初期値として代入
    fun initImageArray() {
        Log.i(TAG, "initImageArray")
        val json = sp.getString(IMAGE_ARRAY, null)
        if (json != null) {
            images = jsonToImageArray(json)
        }
    }

    fun start() {
        println("start")
        mCamera?.startPreview() ?: Log.i(TAG, "camera null")
    }

    fun stop() {
        mCamera?.stopPreview() ?: Log.i(TAG, "camera null")
    }

    fun shut() {
        isBusy = true
        Log.i(TAG, "try to focus")
        mCamera?.takePicture(ShutterCallback {
            soundSilence.start()
        }, null, this)
        mCamera?.enableShutterSound(false)
        //MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
    }

    fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    fun initCamera() {
        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT)
                .show()
            return
        }


        val param = mCamera?.parameters
        val size = getMaxResolution()
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)
        val display = iView.getDisplay()
        val point = Point()
        display.getRealSize(point)
        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio = displayWidth.div(displayHeight.toFloat())
        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
        if (displayRatio > previewRatio) {
            val surfaceParams = iView.getSurfaceView().layoutParams
            surfaceParams.height = (displayHeight / displayRatio * previewRatio).toInt()
            iView.getSurfaceView().layoutParams = surfaceParams
        }

        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find {
            it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01
        }

        if (null == pictureSize) {
            pictureSize = supportPicSize?.get(0)
        }

        if (null == pictureSize) {
            Log.e(TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.d(TAG, "enabling autofocus")
        } else {
            Log.d(TAG, "autofocus not available")
        }
        param?.flashMode = Camera.Parameters.FLASH_MODE_AUTO

        try {
            mCamera?.parameters = param
        } catch (e: RuntimeException) {
            try {
                mCamera?.parameters?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            } catch (e: RuntimeException) {
            }
        }
        mCamera?.setDisplayOrientation(90)
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        }
    }

    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(TAG, "on picture taken")
        Observable.just(p0)
            .subscribeOn(proxySchedule)
            .subscribe {
                val pictureSize = p1?.parameters?.pictureSize
                Log.i(TAG, "picture size: " + pictureSize.toString())
                val mat = Mat(
                    Size(
                        pictureSize?.width?.toDouble() ?: 1920.toDouble(),
                        pictureSize?.height?.toDouble() ?: 1080.toDouble()
                    ), CvType.CV_8U
                )
                var bitmap = BitmapFactory.decodeByteArray(p0, 0, p0!!.size)
                // リサイズ
                val matrix = Matrix()
                matrix.postScale(0.5f, 0.5f)
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                // グレースケール処理
                grayScale(mat, bitmap)
                mat.put(0, 0, p0)

                val pic = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
                Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                mat.release()
                SourceManager.corners = processPicture(pic)
                mat.release()

                SourceManager.pic = pic

                // 矩形編集画面に遷移
//                (context as Activity).startActivityForResult(
//                    Intent(
//                        context,
//                        CropActivity::class.java
//                    ), REQUEST_CODE
//                )

                saveImage(bitmap)
                isBusy = false
                start()
            }
    }

    private fun grayScale(mat: Mat, bm: Bitmap) {
        Utils.bitmapToMat(bm, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY)

        // 記事ではこの記述も必要と書かれているが、クラッシュする
//        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2RGBA, 4)
        Utils.matToBitmap(mat, bm)
    }

    private fun saveImage(bm: Bitmap) {
        // 画像を回転
        val rotatedBm = Bitmap.createBitmap(
            bm,
            0,
            0,
            bm.width,
            bm.height,
            matrix,
            true
        )

        val baos = ByteArrayOutputStream()
        rotatedBm.compress(Bitmap.CompressFormat.JPEG, 50, baos)
        val b = baos.toByteArray()
        // Base64形式でSharedPrefに保存
        // 取り出す時->Base64.decode(image, Base64.DEFAULT)
        val b64 = Base64.encodeToString(b, Base64.DEFAULT)
        val image = Image(b64)

        // 画像の配列に追加
        images.add(image)
        val json = gson.toJson(images)

        val editor = sp.edit()
        editor.putString(IMAGE_ARRAY, json).apply()

        scanActv.updateCount()
    }



    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (isBusy) {
            return
        }
//        Log.i(TAG, "on process start")
        isBusy = true
        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    Log.i(TAG, "start prepare paper")
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0, parameters?.previewFormat ?: 0, width ?: 320, height
                            ?: 480, null
                    )
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 320, height ?: 480), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    Observable.create<Corners> {
                        val corner = processPicture(img)
                        isBusy = false
                        if (null != corner && corner.corners.size == 4) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            iView.getPaperRect().onCornersDetected(it)

                        }, {
                            iView.getPaperRect().onCornersNotDetected()
                        })
                }, { throwable -> Log.e(TAG, throwable.message!!) })
        } catch (e: Exception) {
            print(e.message)
        }

    }

    private fun getMaxResolution(): Camera.Size? =
        mCamera?.parameters?.supportedPreviewSizes?.maxBy { it.width }
}