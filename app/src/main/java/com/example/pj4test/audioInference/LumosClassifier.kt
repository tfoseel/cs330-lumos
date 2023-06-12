package com.example.pj4test.audioInference

import android.content.Context
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.pj4test.fragment.CameraFragment
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate


class LumosClassifier {
    // Libraries for audio classification
    lateinit var classifier: AudioClassifier
    lateinit var recorder: AudioRecord
    lateinit var tensor: TensorAudio

    // Listener that will be handle the result of this classifier
    private var detectorListener: DetectorListener? = null

    // TimerTask
    private var task: TimerTask? = null

    /**
     * initialize
     *
     * Create SPEECH COMMAND classifier from tflite model file saved in speech model,
     * initialize the audio recorder, and make recorder start recording.
     * Set TimerTask for periodic inferences by REFRESH_INTERVAL_MS milliseconds.
     *
     * @param   context Context of the application
     */
    fun initialize(context: Context) {
        /* IMPLEMENTED IN PROJECT 4. */
        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setScoreThreshold(THRESHOLD)
            .setMaxResults(DEFAULT_NUM_OF_RESULTS )
            .setBaseOptions(BaseOptions.builder().build())
            .build()
        classifier = AudioClassifier.createFromFileAndOptions(context, "speech.tflite", options)

        Log.d(TAG, "Model loaded from: $SPEECH_COMMAND_MODEL")
        audioInitialize()
        startRecording()

        startInferencing()
    }

    /**
     * audioInitialize
     *
     * Create the instance of TensorAudio and AudioRecord from the AudioClassifier.
     */
    private fun audioInitialize() {
        tensor = classifier.createInputTensorAudio()

        val format = classifier.requiredTensorAudioFormat
        val recorderSpecs = "Number Of Channels: ${format.channels}\n" +
                "Sample Rate: ${format.sampleRate}"
        Log.d(TAG, recorderSpecs)
        Log.d(TAG, classifier.requiredInputBufferSize.toString())

        recorder = classifier.createAudioRecord()
    }

    /**
     * startRecording
     *
     * This method make recorder start recording.
     * After this function, the microphone is ready for reading.
     */
    private fun startRecording() {
        recorder.startRecording()
        Log.d(TAG, "record started!")
    }

    /**
     * stopRecording
     *
     * This method make recorder stop recording.
     * After this function, the microphone is unavailable for reading.
     */
    private fun stopRecording() {
        recorder.stop()
        Log.d(TAG, "record stopped.")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    /**
     * inference
     *
     * Catches on/off speech command.
     *
     * @return  A command which has higher score than THRESHOLD.
     */
    fun inference(): String? {
        /* IMPLEMENTED IN PROJECT 4. */
        tensor.load(recorder)
        // Log.d(TAG, tensor.tensorBuffer.shape.joinToString(","))
        val output = classifier.classify(tensor)

        // Command can be checked through "output[0].categories[0].label
        if (output.isEmpty()) return null
        val detectedCommandsScores = output[0].categories.map {Pair (it.label, it.score)}
        val maxPair: Pair<String, Float> = detectedCommandsScores.maxByOrNull { it.second }
            ?: return null

        return if (maxPair.first == "on" || maxPair.first == "off") maxPair.first else null
    }


    fun startInferencing() {
        if (task == null) {
            task = Timer().scheduleAtFixedRate(0, REFRESH_INTERVAL_MS) {
                val command = inference()
                detectorListener?.onResults(command)
            }
        }
    }

    fun stopInferencing() {
        task?.cancel()
        task = null
    }

    /**
     * interface DetectorListener
     *
     * This is an interface for listener.
     * To get result from this classifier, inherit this interface
     * and set itself to this' detector listener
     */
    interface DetectorListener {
        fun onResults(command: String?)
    }

    /**
     * setDetectorListener
     *
     * Set detector listener for this classifier.
     */
    fun setDetectorListener(listener: DetectorListener) {
        detectorListener = listener
    }

    /**
     * companion object
     *
     * This includes useful constants for scheduling and this classifier.
     *
     */
    /* IMPLEMENTED IN PROJECT 4. */
    companion object {
        const val REFRESH_INTERVAL_MS = 500L  // For scheduling

        const val TAG = "LumosClassifier"
        const val THRESHOLD = 0.7f
        const val DEFAULT_NUM_OF_RESULTS = 5
        const val SPEECH_COMMAND_MODEL = "speech.tflite"
    }
}