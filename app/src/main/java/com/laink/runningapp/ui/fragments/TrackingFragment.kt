package com.laink.runningapp.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.snackbar.Snackbar
import com.laink.runningapp.R
import com.laink.runningapp.db.Run
import com.laink.runningapp.other.Constants.ACTION_PAUSE_SERVICE
import com.laink.runningapp.other.Constants.ACTION_START_OR_RESUME_SERVICE
import com.laink.runningapp.other.Constants.ACTION_STOP_SERVICE
import com.laink.runningapp.other.Constants.BTN_TOGGLE_RUN_START
import com.laink.runningapp.other.Constants.BTN_TOGGLE_RUN_STOP
import com.laink.runningapp.other.Constants.CANCEL_TRACKING_DIALOG_TAG
import com.laink.runningapp.other.Constants.MAP_ZOOM
import com.laink.runningapp.other.Constants.PADDING_MAP_FOR_SCREENSHOT
import com.laink.runningapp.other.Constants.POLYLINE_COLOR
import com.laink.runningapp.other.Constants.POLYLINE_WIDTH
import com.laink.runningapp.other.Constants.START_TIME
import com.laink.runningapp.other.Constants.SUCCESSFUL_RUN_SAVING_MESSAGE
import com.laink.runningapp.other.TrackingUtility
import com.laink.runningapp.services.Polyline
import com.laink.runningapp.services.TrackingService
import com.laink.runningapp.ui.CancelTrackingDialog
import com.laink.runningapp.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.fragment_tracking.*
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class TrackingFragment : Fragment(R.layout.fragment_tracking) {

    // Use "by viewModels()" and Dagger are going to select correct viewModel for us
    private val viewModel: MainViewModel by viewModels()

    private var isTracking = false
    private var pathPoints = mutableListOf<Polyline>()
    private var map: GoogleMap? = null

    private var currentTimeMillis = 0L

    private var menu: Menu? = null

    @set:Inject
    var weight = 70f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.toolbar_tracking_menu, menu)
        this.menu = menu
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        if (currentTimeMillis > 0L) {
            this.menu?.getItem(0)?.isVisible = true
        }
    }

    private fun showCancelRunDialog() {
        CancelTrackingDialog().apply {
            setPositiveListener {
                stopRun()
            }
        }.show(parentFragmentManager, CANCEL_TRACKING_DIALOG_TAG)
    }

    private fun stopRun() {
        tvTimer.text = START_TIME
        sendCommandServiceToService(ACTION_STOP_SERVICE)
        findNavController().navigate(R.id.action_trackingFragment_to_runFragment)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.miCancelTracking -> {
                showCancelRunDialog()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mapView.onCreate(savedInstanceState)

        // if we clicked on cancel and rotated device
        if (savedInstanceState != null) {
            val cancelTrackingDialog = parentFragmentManager.findFragmentByTag(
                CANCEL_TRACKING_DIALOG_TAG
            ) as CancelTrackingDialog?

            cancelTrackingDialog?.setPositiveListener {
                stopRun()
            }
        }

        btnToggleRun.setOnClickListener {
            toggleRun()
        }

        btnFinishRun.setOnClickListener {
            zoomToSeeWholeTrack()
            finishRunAndSaveToDb()
        }

        mapView.getMapAsync {
            map = it

            addAllPolylines()
        }

        subscribeToObservers()
    }

    private fun subscribeToObservers() {
        TrackingService.isTracking.observe(viewLifecycleOwner, Observer {
            updateTracking(it)
        })

        TrackingService.pathPoints.observe(viewLifecycleOwner, Observer {
            pathPoints = it

            addLatestPolyline()
            moveCameraToUser()
        })

        TrackingService.timeRunInMillis.observe(viewLifecycleOwner, Observer {
            currentTimeMillis = it
            val formattedTime = TrackingUtility.getFormattedStopWatchTime(currentTimeMillis, true)

            tvTimer.text = formattedTime
        })
    }

    private fun toggleRun() {
        if (isTracking) {
            menu?.getItem(0)?.isVisible = true
            sendCommandServiceToService(ACTION_PAUSE_SERVICE)
        } else {
            sendCommandServiceToService(ACTION_START_OR_RESUME_SERVICE)
        }
    }

    private fun updateTracking(isTracking: Boolean) {
        this.isTracking = isTracking

        if (!isTracking && currentTimeMillis > 0) {
            btnToggleRun.text = BTN_TOGGLE_RUN_START
            btnFinishRun.visibility = View.VISIBLE
        } else if (isTracking) {
            btnToggleRun.text = BTN_TOGGLE_RUN_STOP
            menu?.getItem(0)?.isVisible = true
            btnFinishRun.visibility = View.GONE
        }
    }

    private fun moveCameraToUser() {
        if (pathPoints.isNotEmpty() && pathPoints.last().isNotEmpty()) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    pathPoints.last().last(),
                    MAP_ZOOM
                )
            )
        }
    }

    private fun zoomToSeeWholeTrack() {
        val bounds = LatLngBounds.Builder()

        for (polyline in pathPoints) {
            for (position in polyline) {
                bounds.include(position)
            }
        }

        map?.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds.build(),
                mapView.width,
                mapView.height,
                (mapView.height * PADDING_MAP_FOR_SCREENSHOT).toInt()
            )
        )
    }

    private fun finishRunAndSaveToDb() {
        map?.snapshot { bitmap ->
            var distanceInMeters = 0

            for (polyline in pathPoints) {
                distanceInMeters += TrackingUtility.calculatePolylineLength(polyline).toInt()
            }

            val avgSpeed =
                TrackingUtility.roundToDecimal(
                    distanceInMeters / 1000F
                ) / (TrackingUtility.getFloatHoursFromMS(currentTimeMillis))
            val dateTimeStamp = Calendar.getInstance().timeInMillis
            val caloriesBurned = ((distanceInMeters / 1000F) * weight).toInt()
            val run = Run(
                bitmap,
                dateTimeStamp,
                avgSpeed,
                distanceInMeters,
                currentTimeMillis,
                caloriesBurned
            )

            viewModel.insertRun(run)

            Snackbar.make(
                requireView(),
                SUCCESSFUL_RUN_SAVING_MESSAGE,
                Snackbar.LENGTH_LONG
            ).show()

            stopRun()
        }
    }

    private fun addAllPolylines() {
        for (polyline in pathPoints) {
            val polyLineOptions = PolylineOptions()
                .color(POLYLINE_COLOR)
                .width(POLYLINE_WIDTH)
                .addAll(polyline)

            map?.addPolyline(polyLineOptions)
        }
    }

    // use to connect two last points of our path
    private fun addLatestPolyline() {
        if (pathPoints.isNotEmpty() && pathPoints.last().size > 1) {
            val preLastLatLng = pathPoints.last()[pathPoints.last().size - 2]
            val lastLatLng = pathPoints.last().last()
            val polylineOptions = PolylineOptions()
                .color(POLYLINE_COLOR)
                .width(POLYLINE_WIDTH)
                .add(preLastLatLng)
                .add(lastLatLng)

            // drawing that polyline
            map?.addPolyline(polylineOptions)


//            val marker = map?.addMarker(MarkerOptions().position(preLastLatLng))

//            marker?.remove()
//            map?.addMarker(MarkerOptions().position(lastLatLng))
        }
    }

    private fun sendCommandServiceToService(action: String) =
        Intent(requireContext(), TrackingService::class.java).also {
            it.action = action

            requireContext().startService(it)
        }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onStop()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    // For saving some resources
    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState) // For cache our map (we don't need to load it every time when we open the device)
    }
}