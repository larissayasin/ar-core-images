package br.com.yasin.arimages

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private var mSession: Session? = null
    private var arFragment: ArFragment? = null
    private var arSceneView: ArSceneView? = null
    private var modelAdded = false // add model once

    private var sessionConfigured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        arFragment = ar_fragment as ArFragment

        // hiding the plane discovery
        arFragment!!.planeDiscoveryController.hide()
        arFragment!!.planeDiscoveryController.setInstructionView(null)
        arFragment!!.arSceneView.scene.addOnUpdateListener { this.onUpdateFrame(it) }

        arSceneView = arFragment!!.arSceneView

    }

    private fun setupAugmentedImageDb(config: Config): Boolean {
        val augmentedImageDatabase = AugmentedImageDatabase(mSession!!)

        val augmentedImageBitmap = loadAugmentedImage() ?: return false

        augmentedImageDatabase.addImage("car", augmentedImageBitmap)

        config.augmentedImageDatabase = augmentedImageDatabase
        return true
    }


    private fun loadAugmentedImage(): Bitmap? {
        try {
            assets.open("car.jpg").use { `is` -> return BitmapFactory.decodeStream(`is`) }
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

                if (augmentedImage.name.contains("car") && !modelAdded) {
                    renderObject(arFragment!!,
                            augmentedImage.createAnchor(augmentedImage.centerPose),
                            Uri.parse("model.sfb"))
                    modelAdded = true
                }
            }
        }

    }

    private fun renderObject(fragment: ArFragment, anchor: Anchor, model: Uri) {
        ModelRenderable.builder()
                .setSource(this, model)
                .build()
                .thenAccept { renderable -> addNodeToScene(fragment, anchor, renderable) }
                .exceptionally { throwable ->
                    val builder = AlertDialog.Builder(this)
                    builder.setMessage(throwable.message)
                            .setTitle("Error!")
                    val dialog = builder.create()
                    dialog.show()
                    null
                }

    }

    private fun addNodeToScene(fragment: ArFragment, anchor: Anchor, renderable: Renderable) {
        val anchorNode = AnchorNode(anchor)
        val node = TransformableNode(fragment.transformationSystem)
        node.renderable = renderable
        node.setParent(anchorNode)
        fragment.arSceneView.scene.addChild(anchorNode)
        node.select()
    }

    public override fun onPause() {
        super.onPause()
        if (mSession != null) {

            arSceneView!!.pause()
            mSession!!.pause()
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