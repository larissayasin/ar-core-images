package br.com.yasin.arimages

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.toast
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private var mSession: Session? = null
    private var arFragment: ArFragment? = null
    private var arSceneView: ArSceneView? = null
    private var modelCarAdded = false // add model once
    private var modelDaftAdded = false // add model once

    // Controls the height of the video in world space.
    private val VIDEO_HEIGHT_METERS = 0.5f

    private var sessionConfigured = false

    private var videoRenderable: ModelRenderable? = null
    private var mediaPlayer: MediaPlayer? = null

    private val texture by lazy {
        ExternalTexture()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        arFragment = ar_fragment as ArFragment

        // hiding the plane discovery
        arFragment!!.planeDiscoveryController.hide()
        arFragment!!.planeDiscoveryController.setInstructionView(null)
        arFragment!!.arSceneView.scene.addOnUpdateListener { this.onUpdateFrame(it) }

        arSceneView = arFragment!!.arSceneView

        mediaPlayer = MediaPlayer.create(this, R.raw.googlepixel)
        mediaPlayer!!.setSurface(texture.surface)
        mediaPlayer!!.isLooping = false

    }

    private fun setupAugmentedImageDb(config: Config): Boolean {
        val augmentedImageDatabase = AugmentedImageDatabase(mSession!!)

        augmentedImageDatabase.addImage("car", loadAugmentedImage("car.jpg") ?: return false)
        augmentedImageDatabase.addImage("daft", loadAugmentedImage("daft.jpg") ?: return false)

        config.augmentedImageDatabase = augmentedImageDatabase
        return true
    }


    private fun loadAugmentedImage(file: String): Bitmap? {
        try {
            assets.open(file).use { `is` -> return BitmapFactory.decodeStream(`is`) }
        } catch (e: IOException) {
            Log.e("ImageLoad", "IO Exception while loading", e)
        }

        return null
    }

    private fun onUpdateFrame(frameTime: FrameTime) {
        val frame = arFragment!!.arSceneView.arFrame

        val augmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

        for (augmentedImage in augmentedImages) {
            if (augmentedImage.trackingState == TrackingState.TRACKING) {

                if (augmentedImage.name.contains("car") && !modelCarAdded) {
                    renderView(arFragment!!,
                            augmentedImage.createAnchor(augmentedImage.centerPose))
                    modelCarAdded = true
                }
                if (augmentedImage.name.contains("daft") && !modelDaftAdded) {
                    renderObject(arFragment!!,
                            augmentedImage.createAnchor(augmentedImage.centerPose),
                            Uri.parse("Convertible.sfb"))
                    modelDaftAdded = true
                }
            }
        }

    }

    private fun renderObject(fragment: ArFragment, anchor: Anchor, model: Uri) {
        ModelRenderable.builder()
                .setSource(this, model)
                .build()
                .thenAccept { renderable -> addNodeToScene(fragment, anchor, renderable, Vector3(0f, 0f, 0f)) }
                .exceptionally { throwable ->
                    val builder = AlertDialog.Builder(this)
                    builder.setMessage(throwable.message)
                            .setTitle("Error!")
                    val dialog = builder.create()
                    dialog.show()
                    null
                }

    }

    private val CHROMA_KEY_COLOR = Color(0.1843f, 1.0f, 0.098f)

    private fun renderView(fragment: ArFragment, anchor: Anchor) {

        ModelRenderable.builder()
                .setSource(this, Uri.parse("chroma_key_video.sfb"))
                .build()
                .thenAccept { renderable ->
                    videoRenderable = renderable
                    renderable.material.setExternalTexture("videoTexture", texture)
                    renderable.material.setFloat4("keyColor", CHROMA_KEY_COLOR)
                    createVideoDisplay(anchor, Vector3(0f, 0.5f, 0f))

                }
                .exceptionally { throwable ->
                    toast("erro")
                    null
                }

        ViewRenderable.builder()
                .setView(this, R.layout.text_info)
                .build()
                .thenAccept { renderable ->
                    (renderable.view as TextView).text = "Example"
                    addNodeToScene(fragment, anchor, renderable, Vector3(0f, 0.2f, 0f))

                }
                .exceptionally { throwable ->
                    toast(throwable.message ?: "Error")
                    null
                }


    }

    private fun createVideoDisplay(anchor: Anchor, vector3: Vector3) {
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(arFragment!!.arSceneView.scene)
        val videoNode = Node()
        videoNode.setParent(anchorNode)
        videoNode.localPosition = vector3
        // Set the scale of the node so that the aspect ratio of the video is correct.
        val videoWidth = mediaPlayer!!.videoWidth.toFloat()
        val videoHeight = mediaPlayer!!.videoHeight.toFloat()
        videoNode.localScale = Vector3(
                VIDEO_HEIGHT_METERS * (videoWidth / videoHeight), VIDEO_HEIGHT_METERS, 1.0f)

        // Start playing the video when the first node is placed.
        if (!mediaPlayer!!.isPlaying()) {
            mediaPlayer!!.start()

            // Wait to set the renderable until the first frame of the  video becomes available.
            // This prevents the renderable from briefly appearing as a black quad before the video
            // plays.
            texture
                    .surfaceTexture
                    .setOnFrameAvailableListener { surfaceTexture: SurfaceTexture ->
                        videoNode.renderable = videoRenderable
                        texture.surfaceTexture.setOnFrameAvailableListener(null)
                    }
        } else {
            videoNode.renderable = videoRenderable
        }

    }

    private fun addNodeToScene(fragment: ArFragment, anchor: Anchor, renderable: Renderable, vector3: Vector3): Node {
        val anchorNode = AnchorNode(anchor)
        val node = TransformableNode(fragment.transformationSystem)
        node.renderable = renderable
        node.setParent(anchorNode)
        node.localPosition = vector3
        fragment.arSceneView.scene.addChild(anchorNode)
        // node.select()

        return node
    }

    public override fun onPause() {
        super.onPause()
        if (mSession != null) {

            arSceneView!!.pause()
            mSession!!.pause()
        }
    }


    public override fun onDestroy() {
        super.onDestroy()

        if (mediaPlayer != null) {
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (mSession == null) {
            var message: String? = null
            var exception: Exception? = null
            try {
                mSession = Session(this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update android"
                exception = e
            } catch (e: Exception) {
                message = "AR is not supported"
                exception = e
            }

            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Exception creating session", exception)
                return
            }
            sessionConfigured = true

        }
        if (sessionConfigured) {
            configureSession()
            sessionConfigured = false

            arSceneView!!.setupSession(mSession)
        }

    }

    private fun configureSession() {
        val config = Config(mSession!!)
        if (!setupAugmentedImageDb(config)) {
            Toast.makeText(this, "Unable to setup augmented", Toast.LENGTH_SHORT).show()
        }
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        mSession!!.configure(config)
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}