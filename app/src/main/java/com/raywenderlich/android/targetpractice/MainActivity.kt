/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright (c) 2017 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.raywenderlich.android.targetpractice

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.Toast
import com.google.ar.core.*
import com.raywenderlich.android.targetpractice.rendering.*
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10


class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

  companion object {
    const val TAG = "MainActivity"
  }

  private var mode: Mode = Mode.VIKING

  lateinit private var surfaceView: GLSurfaceView

  lateinit var defaultConfig: Config
  lateinit var session: Session
  lateinit private var gestureDetector: GestureDetector

  private var loadingMessageSnackbar: Snackbar? = null

  private val backgroundRenderer = BackgroundRenderer()
  private val planeRenderer = PlaneRenderer()
  private val pointCloud = PointCloudRenderer()

  private val vikingObject = ObjectRenderer()
  private val cannonObject = ObjectRenderer()
  private val targetObject = ObjectRenderer()

  private var vikingAttachment: PlaneAttachment? = null
  private var cannonAttachment: PlaneAttachment? = null
  private var targetAttachment: PlaneAttachment? = null

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private val anchorMatrix = FloatArray(16)

  // Tap handling and UI.
  private val queuedSingleTaps = ArrayBlockingQueue<MotionEvent>(16)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    if (!setupSession()) return
    setupTapDetector()
    setupSurfaceView()
  }

  private fun setupSession(): Boolean {
    session = Session(this)

    defaultConfig = Config.createDefaultConfig()
    if (!session.isSupported(defaultConfig)) {
      Toast.makeText(this, getString(R.string.ar_device_support), Toast.LENGTH_LONG).show()
      finish()
      return false
    }

    return true
  }

  private fun setupTapDetector() {
    gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onSingleTapUp(e: MotionEvent): Boolean {
        onSingleTap(e)
        return true
      }

      override fun onDown(e: MotionEvent): Boolean {
        return true
      }
    })
  }

  private fun setupSurfaceView() {
    surfaceView = findViewById(R.id.surfaceView)

    surfaceView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

    // Set up renderer.
    surfaceView.preserveEGLContextOnPause = true
    surfaceView.setEGLContextClientVersion(2)
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
    surfaceView.setRenderer(this)
    surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
  }


  override fun onResume() {
    super.onResume()

    // ARCore requires camera permissions to operate. If we did not yet obtain runtime
    // permission on Android M and above, now is a good time to ask the user for it.
    if (CameraPermissionHelper.hasCameraPermission(this)) {
      showLoadingMessage()
      // Note that order matters - see the note in onPause(), the reverse applies here.
      session.resume(defaultConfig)
      surfaceView.onResume()
    } else {
      CameraPermissionHelper.requestCameraPermission(this)
    }
  }

  public override fun onPause() {
    super.onPause()
    // Note that the order matters - GLSurfaceView is paused first so that it does not try
    // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
    // still call session.update() and get a SessionPausedException.
    surfaceView.onPause()
    session.pause()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
      results: IntArray) {
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, getString(R.string.camera_permission_toast), Toast.LENGTH_LONG).show()
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      // Standard Android full-screen functionality.
      window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
          View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
          View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
          View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
          View.SYSTEM_UI_FLAG_FULLSCREEN or
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  fun onRadioButtonClicked(view: View) {
    val radioButton = view as RadioButton
    when (radioButton.id) {
      R.id.radioViking -> mode = Mode.VIKING
      R.id.radioCannon -> mode = Mode.CANNON
      R.id.radioTarget -> mode = Mode.TARGET
    }
  }

  private fun onSingleTap(e: MotionEvent) {
    // Queue tap if there is space. Tap is lost if queue is full.
    queuedSingleTaps.offer(e)
  }

  private fun showLoadingMessage() {
    runOnUiThread {
      loadingMessageSnackbar = Snackbar.make(this@MainActivity.findViewById(android.R.id.content),
          getString(R.string.searching_for_surfaces), Snackbar.LENGTH_INDEFINITE)
      loadingMessageSnackbar?.view?.setBackgroundColor(0xbf323232.toInt())
      loadingMessageSnackbar?.show()
    }
  }

  private fun hideLoadingMessage() {
    runOnUiThread {
      loadingMessageSnackbar?.dismiss()
      loadingMessageSnackbar = null
    }
  }

  // GLSurfaceView.Renderer

  override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
    GLES20.glViewport(0, 0, width, height)
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    session.setDisplayGeometry(width.toFloat(), height.toFloat())
  }

  override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

    // Create the texture and pass it to ARCore session to be filled during update().
    backgroundRenderer.createOnGlThread(this)
    session.setCameraTextureName(backgroundRenderer.textureId)

    try {
      planeRenderer.createOnGlThread(this, "trigrid.png")
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read plane texture")
    }

    // Prepare the other rendering objects.
    try {
      vikingObject.createOnGlThread(this, "viking.obj", "viking.png")
      vikingObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
      cannonObject.createOnGlThread(this, "cannon.obj", "cannon.png")
      cannonObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
      targetObject.createOnGlThread(this, "target.obj", "target.png")
      targetObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read obj file")
    }


    pointCloud.createOnGlThread(this)
  }

  override fun onDrawFrame(gl: GL10) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

    try {
      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      val frame = session.update()

      handleTaps(frame)

      drawBackground(frame)

      // If not tracking, don't draw 3d objects.
      if (!checkTrackingState(frame)) return

      val projectionMatrix = computeProjectionMatrix()
      val viewMatrix = computeViewMatrix(frame)
      val lightIntensity = computeLightIntensity(frame)

      visualizeTrackedPoints(frame, projectionMatrix, viewMatrix)
      checkPlaneDetected()
      visualizePlanes(frame, projectionMatrix)

      drawObject(vikingObject, vikingAttachment, Mode.VIKING.scaleFactor, projectionMatrix,
          viewMatrix, lightIntensity)
      drawObject(cannonObject, cannonAttachment, Mode.CANNON.scaleFactor, projectionMatrix,
          viewMatrix, lightIntensity)
      drawObject(targetObject, targetAttachment, Mode.TARGET.scaleFactor, projectionMatrix,
          viewMatrix, lightIntensity)

    } catch (t: Throwable) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t)
    }
  }

  private fun checkTrackingState(frame: Frame): Boolean {
    if (frame.trackingState == Frame.TrackingState.NOT_TRACKING) {
      return false
    }
    return true
  }

  private fun handleTaps(frame: Frame) {
    // Handling only one tap per frame, as taps are usually low frequency
    // compared to frame rate.
    val tap = queuedSingleTaps.poll()
    if (tap != null && frame.trackingState == Frame.TrackingState.TRACKING) {
      for (hit in frame.hitTest(tap)) {
        // Check if any plane was hit, and if it was hit inside the plane polygon.
        if (hit is PlaneHitResult && hit.isHitInPolygon) {
          // Hits are sorted by depth. Consider only closest hit on a plane.
          when (mode) {
            Mode.VIKING -> vikingAttachment = addSessionAnchorFromAttachment(vikingAttachment, hit)
            Mode.CANNON -> cannonAttachment = addSessionAnchorFromAttachment(cannonAttachment, hit)
            Mode.TARGET -> targetAttachment = addSessionAnchorFromAttachment(targetAttachment, hit)
          }
          break
        }
      }
    }
  }

  private fun drawBackground(frame: Frame?) {
    backgroundRenderer.draw(frame)
  }

  private fun computeProjectionMatrix(): FloatArray {
    val projectionMatrix = FloatArray(16)
    session.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
    return projectionMatrix
  }

  private fun computeViewMatrix(frame: Frame): FloatArray {
    val viewMatrix = FloatArray(16)
    frame.getViewMatrix(viewMatrix, 0)
    return viewMatrix
  }

  private fun computeLightIntensity(frame: Frame) = frame.lightEstimate.pixelIntensity

  private fun visualizeTrackedPoints(frame: Frame, projectionMatrix: FloatArray,
      viewMatrix: FloatArray) {
    pointCloud.update(frame.pointCloud)
    pointCloud.draw(frame.pointCloudPose, viewMatrix, projectionMatrix)
  }

  private fun checkPlaneDetected() {
    // Check if we detected at least one plane. If so, hide the loading message.
    if (loadingMessageSnackbar != null) {
      for (plane in session.allPlanes) {
        if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.trackingState == Plane.TrackingState.TRACKING) {
          hideLoadingMessage()
          break
        }
      }
    }
  }

  private fun visualizePlanes(frame: Frame, projectionMatrix: FloatArray) {
    planeRenderer.drawPlanes(session.allPlanes, frame.pose, projectionMatrix)
  }

  private fun drawObject(objectRenderer: ObjectRenderer, planeAttachment: PlaneAttachment?,
      scaleFactor: Float,
      projectionMatrix: FloatArray, viewMatrix: FloatArray, lightIntensity: Float) {
    if (planeAttachment?.isTracking == true) {

      planeAttachment.pose.toMatrix(anchorMatrix, 0)

      // Update and draw the model
      objectRenderer.updateModelMatrix(anchorMatrix, scaleFactor)
      objectRenderer.draw(viewMatrix, projectionMatrix, lightIntensity)
    }
  }

  private fun addSessionAnchorFromAttachment(
      previousAttachment: PlaneAttachment?, hit: PlaneHitResult): PlaneAttachment {
    previousAttachment?.let {
      session.removeAnchors(Arrays.asList(previousAttachment.anchor))
    }
    return PlaneAttachment(hit.plane, session.addAnchor(hit.hitPose))
  }
}
