package com.example.pj4test.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.pj4test.ProjectConfiguration
import com.example.pj4test.audioInference.LumosClassifier
import com.example.pj4test.databinding.FragmentAudioBinding

class AudioFragment: Fragment(), LumosClassifier.DetectorListener {
    private val TAG = "AudioFragment"

    private var _fragmentAudioBinding: FragmentAudioBinding? = null

    private val fragmentAudioBinding
        get() = _fragmentAudioBinding!!

    // classifiers
    lateinit var lumosClassifier: LumosClassifier

    // views
    lateinit var onOffView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentAudioBinding = FragmentAudioBinding.inflate(inflater, container, false)

        return fragmentAudioBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onOffView = fragmentAudioBinding.OnOffView

        lumosClassifier = LumosClassifier()
        lumosClassifier.initialize(requireContext())
        lumosClassifier.setDetectorListener(this)
    }

    override fun onPause() {
        super.onPause()
        lumosClassifier.stopInferencing()
    }

    override fun onResume() {
        super.onResume()
        lumosClassifier.startInferencing()
    }

    /* IMPLEMENTED IN PROJECT 4. */
    override fun onResults(command: String?) {
        if (command == "on") {
            Log.d(TAG, "Lumos ON")
            onOffView.text = "Lumos ON"
            onOffView.setBackgroundColor(ProjectConfiguration.activeBackgroundColor)
            onOffView.setTextColor(ProjectConfiguration.activeTextColor)
        } else if (command == "off") {
            Log.d(TAG, "Lumos OFF")
            onOffView.text = "Lumos OFF"
            onOffView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
            onOffView.setTextColor(ProjectConfiguration.idleTextColor)
        }
    }
}