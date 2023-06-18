/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.pj4test.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.pj4test.cameraInference.PersonClassifier
import com.example.pj4test.databinding.FragmentCameraBinding
import org.tensorflow.lite.task.vision.detector.Detection

import android.speech.tts.TextToSpeech
import com.example.pj4test.ProjectConfiguration
import com.example.pj4test.audioInference.LumosClassifier
import java.util.Locale

class CameraFragment : Fragment(), PersonClassifier.DetectorListener, LumosClassifier.DetectorListener {
    private val TAG = "CameraFragment"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!
    
    private lateinit var personView: TextView

    // Classfiers
    private lateinit var personClassifier: PersonClassifier
    private lateinit var lumosClassifier: LumosClassifier

    private var lumos: Boolean = false // If true, detect and read the object in camera.

    private lateinit var bitmapBuffer: Bitmap
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var tts: TextToSpeech
    private var timeToSpeak: Int = 0

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lumosClassifier = LumosClassifier()
        lumosClassifier.initialize(requireContext())
        lumosClassifier.setDetectorListener(this)

        personClassifier = PersonClassifier()
        personClassifier.initialize(requireContext())
        personClassifier.setDetectorListener(this)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        /* IMPLEMENTED IN PROJECT 4. */
        tts = TextToSpeech(requireContext(), TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Language is not supported, handle the error
                    Log.e(TAG, "Language is not supported.")
                } else {
                    // TTS initialization successful
                    Log.d(TAG, "TTS initialized successfully.")
                }
            } else {
                // TTS initialization failed, handle the error
                Log.e(TAG, "TTS initialization failed.")
            }
        })

        personView = fragmentCameraBinding.PersonView
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                val cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases(cameraProvider)
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()
        // Attach the viewfinder's surface provider to preview use case
        preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)


        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
        // The analyzer can then be assigned to the instance
        imageAnalyzer!!.setAnalyzer(cameraExecutor) { image -> detectObjects(image) }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    private fun detectObjects(image: ImageProxy) {
        if (!::bitmapBuffer.isInitialized) {
            // The image rotation and RGB image buffer are initialized only once
            // the analyzer has started running
            bitmapBuffer = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
        }
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
        val imageRotation = image.imageInfo.rotationDegrees

        // Pass Bitmap and rotation to the object detector helper for processing and detection
        personClassifier.detect(bitmapBuffer, imageRotation)
    }

    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onObjectDetectionResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        if (lumos) {
            activity?.runOnUiThread {
                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    results ?: LinkedList<Detection>(),
                    imageHeight,
                    imageWidth
                )

                /* IMPLEMENTED IN PROJECT 4. */
                val detectObjectList: List<String> = listOf(
                    "person",
                    "chair",
                    "couch",
                    "bench",
                    "bicycle",
                    "motorcycle",
                    "bus",
                    "car",
                    "train"
                )

                var speech: String = "Nothing detected."
                if (results != null) {
                    val detectedObjects: List<String> = results.map {
                        it.categories[0].label
                    }

                    // 'objectCounts' is a list of Pair<String, Int>,
                    // where the first element is one of "person", "chair", "vehicle"
                    // and the second element is the count of each objects.
                    val detectedTargets = detectedObjects.filter { it in detectObjectList }

                    val totalTargetCount = detectedTargets.size

                    val objectCounts: List<Pair<String, Int>> = detectedTargets.map { it ->
                        if (it == "couch" || it == "bench") "chair"
                        else if (it in listOf(
                                "bicycle",
                                "motorcycle",
                                "bus",
                                "car",
                                "train"
                            )
                        ) "vehicle"
                        else it
                    }.groupingBy { it }
                        .eachCount()
                        .toList()
                        .map { it.first to it.second }

                    if (totalTargetCount == 0) {
                        speech = "Nothing detected."
                    } else {
                        speech = ""
                        objectCounts.forEachIndexed { idx, it ->
                            if (idx == objectCounts.size - 1) {
                                speech += it.second.toString() + " " + it.first + "."
                            } else {
                                speech += it.second.toString() + " " + it.first + ", "
                            }
                        }
                    }

                    /* READ THE SPEECH WITH TTS */
                    tts.speak(speech, TextToSpeech.QUEUE_FLUSH, null, null)
                    timeToSpeak = 1

                }

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()

                // Show result to a view.
                personView.text = speech
            }
        }
    }

    override fun onObjectDetectionError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    /* IMPLEMENTED IN PROJECT 4. */
    override fun onResults(command: String?) {
        if (command == "on") {
            Log.d(TAG, "Lumos ON")
            lumos = true
            personView.setBackgroundColor(ProjectConfiguration.activeBackgroundColor)
            personView.setTextColor(ProjectConfiguration.activeTextColor)
        } else if (command == "off") {
            Log.d(TAG, "Lumos OFF")
            lumos = false
            personView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
            personView.setTextColor(ProjectConfiguration.idleTextColor)
        }
    }
}
