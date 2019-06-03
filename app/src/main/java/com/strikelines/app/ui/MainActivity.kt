package com.strikelines.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.design.widget.BottomNavigationView
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import com.strikelines.app.*
import com.strikelines.app.OsmandCustomizationConstants.PLUGIN_RASTER_MAPS
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_AIRCRAFT
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_BICYCLE
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_BOAT
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_BUS
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_CAR
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_PEDESTRIAN
import com.strikelines.app.OsmandHelper.Companion.APP_MODE_TRAIN
import com.strikelines.app.OsmandHelper.Companion.METRIC_CONST_NAUTICAL_MILES
import com.strikelines.app.OsmandHelper.Companion.SPEED_CONST_NAUTICALMILES_PER_HOUR
import com.strikelines.app.OsmandHelper.OsmandHelperListener
import com.strikelines.app.StrikeLinesApplication.Companion.DOWNLOAD_REQUEST_CODE
import com.strikelines.app.StrikeLinesApplication.Companion.IMPORT_REQUEST_CODE
import com.strikelines.app.ui.adapters.LockableViewPager
import com.strikelines.app.utils.AndroidUtils
import com.strikelines.app.utils.DownloadHelperListener
import com.strikelines.app.utils.PlatformUtil
import com.strikelines.app.utils.clearTitleForWrecks
import kotlinx.android.synthetic.main.activity_main.*
import java.lang.ref.WeakReference


class MainActivity : AppCompatActivity(), OsmandHelperListener, ImportHelperListener, DownloadHelperListener {

	private val log = PlatformUtil.getLog(MainActivity::class.java)

	private val app get() = application as StrikeLinesApplication
	private val osmandHelper get() = app.osmandHelper
	private val importHelper get() = app.importHelper
	private val downloadHelper get() = app.downloadHelper

	private val listeners = mutableListOf<WeakReference<OsmandHelperListener>>()

	private var mapsTabFragment: MapsTabFragment? = null
	private var purchasesTabFragment: PurchasesTabFragment? = null

	private lateinit var bottomNav: BottomNavigationView
	private lateinit var progressBar: ProgressBar
	private var snackView: View? = null

	var regionList: MutableSet<String> = mutableSetOf()
	var regionToFilter: String = ""
	var isActivityVisible = false

	private var importUri: Uri? = null

	private val osmandHelperInitListener = object : OsmandHelper.OsmandAppInitCallback {
		override fun onOsmandInitialized() {
			log.debug("Osmand Initialized!")
			setupOsmand()
		}
	}

	override fun fileCopyStarted(fileName: String?) {
		if (isActivityVisible) {
			showProgressBar(true)
		}
	}

	override fun fileCopyProgressUpdated(fileName: String?, progress: Int) {
		if (isActivityVisible) {
			showProgressBar(true)
			progressBar.progress = progress
		}
	}

	override fun fileCopyFinished(fileName: String?, success: Boolean) {
		if (isActivityVisible) {
			showProgressBar(false)
			if (success) {
				updateMapsList()
				showSnackBar(getString(R.string.importFileSuccess).format(fileName), action = 2)
			} else {
				showSnackBar(getString(R.string.importFileError).format(fileName), action = 2)
			}
		}
	}

	override fun onDownloadStarted(title: String, path: String) {

	}

	override fun onDownloadProgressUpdate(title: String, path: String, progress: Int) {
		if (isActivityVisible) {
			showProgressBar(true)
			progressBar.progress = progress
		}
	}

	override fun onDownloadCompleted(title: String, path: String, isSuccess: Boolean) {
		if (isActivityVisible) {
			showProgressBar(false)
			val message = getString(if (isSuccess) R.string.download_success_msg else R.string.download_failed_msg).format(clearTitleForWrecks(title))
			val action = if (isSuccess) 3 else 2
			showSnackBar(message, findViewById(android.R.id.content), action = action, path = path)
		}
	}

	override fun onOsmandConnectionStateChanged(connected: Boolean) {
		if (connected) {
			osmandHelper.registerForOsmandInitialization()
		}
		listeners.forEach {
			it.get()?.onOsmandConnectionStateChanged(connected)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		osmandHelper.onOsmandInitCallbacks.add(osmandHelperInitListener)
		initChartsList()

		snackView = findViewById(android.R.id.content)
		val viewPager = findViewById<LockableViewPager>(R.id.view_pager).apply {
			swipeLocked = true
			offscreenPageLimit = 2
			adapter = ViewPagerAdapter(supportFragmentManager)
		}

		bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation).apply {
			setOnNavigationItemSelectedListener {
				var pos = -1
				when (it.itemId) {
					R.id.action_maps -> pos = MAPS_TAB_POS
					R.id.action_downloads -> pos = DOWNLOADS_TAB_POS
				}
				if (pos != -1 && pos != viewPager.currentItem) {
					viewPager.currentItem = pos
					return@setOnNavigationItemSelectedListener true
				}
				false
			}
		}
		fab.setOnClickListener { view ->
			isOsmandFABWasClicked = true
			osmandHelper.openOsmand {
				installOsmandDialog()
				Toast.makeText(view.context, getString(R.string.osmandIsMissing), Toast.LENGTH_SHORT).show()
			}
		}
		fab.setOnTouchListener { v, event ->
			when (event?.action) {
				MotionEvent.ACTION_UP -> {
					big_fab_icon.setColorFilter(
						ContextCompat.getColor(this@MainActivity, R.color.osmand_pressed_btn_bg),
						android.graphics.PorterDuff.Mode.MULTIPLY
					)
					big_fab_label.setTextColor(resources.getColor(R.color.osmand_pressed_btn_bg))
				}
				MotionEvent.ACTION_DOWN -> {
					big_fab_icon.setColorFilter(
						ContextCompat.getColor(this@MainActivity, R.color.osmand_pressed_btn_icon),
						android.graphics.PorterDuff.Mode.MULTIPLY
					)
					big_fab_label.setTextColor(resources.getColor(R.color.osmand_pressed_btn_text))
				}
			}
			v?.onTouchEvent(event) ?: true
		}
		progressBar = findViewById<ProgressBar>(R.id.horizontal_progress).apply {
			val bgColor = ContextCompat.getColor(this@MainActivity, R.color.osmand_pressed_btn_bg)
			val progressColor = ContextCompat.getColor(this@MainActivity, R.color.osmand_pressed_btn_icon)
			progressDrawable = AndroidUtils.createProgressDrawable(bgColor, progressColor)
			indeterminateDrawable.setColorFilter(progressColor, android.graphics.PorterDuff.Mode.SRC_IN)
		}
		if (osmandHelper.isOsmandBound() && !osmandHelper.isOsmandConnected()) {
			osmandHelper.connectOsmand()
		}
	}

	override fun onResume() {
		super.onResume()
		isActivityVisible = true
		osmandHelper.listener = this
		importHelper.listener = this
		downloadHelper.listener = this
		StrikeLinesApplication.listener = appListener
		osmandHelper.onOsmandInitCallbacks.add(osmandHelperInitListener)
		showProgressBar(importHelper.isCopying() || downloadHelper.isDownloading())
		val intent: Intent? = intent
		if (Intent.ACTION_VIEW == intent?.action) {
			if (intent.data != null) {
				val uri = intent.data
				intent.action = null
				setIntent(null)
				val scheme = uri?.scheme
				if (uri != null && scheme != null && ("file" == scheme || "content" == scheme)) {
					if (importHelper.isCopying()) {
						showToastMessage(getString(R.string.copy_file_in_progress_alert))
					} else {
						processFileImport(uri)
					}
				}
			}
		}
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		if (requestCode == IMPORT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
			if (data != null) {
				val uri = data.data
				if (uri != null) {
					processFileImport(uri)
				}
			}
		} else {
			super.onActivityResult(requestCode, resultCode, data)
		}
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			if (requestCode == DOWNLOAD_REQUEST_CODE) {
				val uri = importUri
				if (uri != null) {
					importHelper.importFile(uri)
				}
			} else if (requestCode == IMPORT_REQUEST_CODE) {
				selectFileForImport()
			}
		}
	}

	override fun onRestart() {
		super.onRestart()
		setupOsmand()
	}

	override fun onPause() {
		super.onPause()
		isActivityVisible = false
		if (!isOsmandFABWasClicked) {
			osmandHelper.restoreOsmand()
		}
		osmandHelper.onOsmandInitCallbacks.clear()
		osmandHelper.listener = null
		importHelper.listener = null
		downloadHelper.listener = null
		StrikeLinesApplication.listener = null
	}

	override fun onDestroy() {
		super.onDestroy()
		app.cleanupResources()
	}

	override fun onAttachFragment(fragment: Fragment?) {
		if (fragment is OsmandHelperListener) {
			listeners.add(WeakReference(fragment))
		}
		if (fragment is MapsTabFragment) {
			mapsTabFragment = fragment
		} else if (fragment is PurchasesTabFragment) {
			purchasesTabFragment = fragment
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		if (intent.getBooleanExtra(OPEN_DOWNLOADS_TAB_KEY, false)) {
			AndroidUtils.dismissAllDialogs(supportFragmentManager)
			bottomNav.selectedItemId = R.id.action_maps
		}
	}

	fun selectFileForImport() {
		if (osmandHelper.isOsmandAvailiable()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if (AndroidUtils.hasPermissionToWriteExternalStorage(this)) {
					importFile()
				} else {
					requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), IMPORT_REQUEST_CODE)
				}
			} else {
				importFile()
			}
		} else {
			installOsmandDialog()
		}
	}

	private fun showProgressBar(isVisible: Boolean) {
		val visibility = if (isVisible) View.VISIBLE else View.GONE
		if (progressBar.visibility != visibility) {
			progressBar.visibility = visibility
		}
	}

	private fun updateMapsList() {
		for (fragment in supportFragmentManager.fragments) {
			if (fragment is MapsTabFragment) {
				fragment.fetchListItems()
			}
		}
	}

	private fun importFile() {
		val intent = Intent().apply {
			action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				Intent.ACTION_OPEN_DOCUMENT
			} else {
				Intent.ACTION_GET_CONTENT
			}
			type = "*/*"
		}
		if (AndroidUtils.isIntentSafe(this, intent)) {
			startActivityForResult(intent, IMPORT_REQUEST_CODE)
		}
	}

	private fun processFileImport(uri: Uri) {
		if (osmandHelper.isOsmandAvailiable()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if (AndroidUtils.hasPermissionToWriteExternalStorage(this)) {
					importHelper.importFile(uri)
				} else {
					importUri = uri
					requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), DOWNLOAD_REQUEST_CODE)
				}
			} else {
				importHelper.importFile(uri)
			}
		} else {
			installOsmandDialog()
		}
	}

	private fun setupOsmand() {
		val logoUri = AndroidUtils.resourceToUri(
			this@MainActivity, R.drawable.img_strikelines_nav_drawer_logo
		)

		val exceptDefault = listOf(
			APP_MODE_CAR,
			APP_MODE_PEDESTRIAN,
			APP_MODE_BICYCLE,
			APP_MODE_BOAT,
			APP_MODE_AIRCRAFT,
			APP_MODE_BUS,
			APP_MODE_TRAIN
		)
		val exceptPedestrianAndDefault = listOf(
			APP_MODE_CAR,
			APP_MODE_BICYCLE,
			APP_MODE_BOAT,
			APP_MODE_AIRCRAFT,
			APP_MODE_BUS,
			APP_MODE_TRAIN
		)
		val exceptAirBoatDefault = listOf(APP_MODE_CAR, APP_MODE_BICYCLE, APP_MODE_PEDESTRIAN)
		val pedestrian = listOf(APP_MODE_PEDESTRIAN)
		val pedestrianBicycle = listOf(APP_MODE_PEDESTRIAN, APP_MODE_BICYCLE)
		val all = null
		val none = emptyList<String>()

		osmandHelper.apply {

			setNavDrawerLogoWithParams(logoUri, packageName, "strike_lines_app://main_activity")
			setNavDrawerFooterParams(
				packageName,
				"strike_lines_app://main_activity",
				resources.getString(R.string.app_name)
			)
			setNavDrawerItems(
				packageName,
				listOf(getString(R.string.aidl_menu_item_download_charts)),
				listOf("strike_lines_app://main_activity"),
				listOf("ic_type_archive"),
				listOf(-1)
			)

			setDisabledPatterns(
				listOf(
					OsmandCustomizationConstants.DRAWER_DASHBOARD_ID,
					OsmandCustomizationConstants.DRAWER_MY_PLACES_ID,
					OsmandCustomizationConstants.DRAWER_SEARCH_ID,
					OsmandCustomizationConstants.DRAWER_DIRECTIONS_ID,
					OsmandCustomizationConstants.DRAWER_CONFIGURE_SCREEN_ID,
					OsmandCustomizationConstants.DRAWER_OSMAND_LIVE_ID,
					OsmandCustomizationConstants.DRAWER_TRAVEL_GUIDES_ID,
					OsmandCustomizationConstants.DRAWER_PLUGINS_ID,
					OsmandCustomizationConstants.DRAWER_SETTINGS_ID,
					OsmandCustomizationConstants.DRAWER_HELP_ID,
					OsmandCustomizationConstants.DRAWER_BUILDS_ID,
					OsmandCustomizationConstants.DRAWER_DIVIDER_ID,
					OsmandCustomizationConstants.DRAWER_DOWNLOAD_MAPS_ID,
					OsmandCustomizationConstants.MAP_CONTEXT_MENU_ACTIONS,
					OsmandCustomizationConstants.CONFIGURE_MAP_ITEM_ID_SCHEME
				)
			)

			setEnabledIds(
				listOf(
					OsmandCustomizationConstants.MAP_CONTEXT_MENU_MEASURE_DISTANCE,
					OsmandCustomizationConstants.GPX_FILES_ID,
					OsmandCustomizationConstants.MAP_SOURCE_ID,
					OsmandCustomizationConstants.OVERLAY_MAP,
					OsmandCustomizationConstants.UNDERLAY_MAP,
					OsmandCustomizationConstants.CONTOUR_LINES
				)
			)

			setDisabledIds(
				listOf(
					OsmandCustomizationConstants.ROUTE_PLANNING_HUD_ID,
					OsmandCustomizationConstants.QUICK_SEARCH_HUD_ID
				)
			)

			changePluginState(PLUGIN_RASTER_MAPS, 1)

			// left
			regWidgetVisibility("next_turn", exceptPedestrianAndDefault)
			regWidgetVisibility("next_turn_small", pedestrian)
			regWidgetVisibility("next_next_turn", exceptPedestrianAndDefault)
			regWidgetAvailability("next_turn", exceptDefault)
			regWidgetAvailability("next_turn_small", exceptDefault)
			regWidgetAvailability("next_next_turn", exceptDefault)

			// right
			regWidgetVisibility("intermediate_distance", all)
			regWidgetVisibility("distance", all)
			regWidgetVisibility("time", all)
			regWidgetVisibility("intermediate_time", all)
			regWidgetVisibility("speed", exceptPedestrianAndDefault)
			regWidgetVisibility("max_speed", listOf(APP_MODE_CAR))
			regWidgetVisibility("altitude", pedestrianBicycle)
			regWidgetVisibility("gps_info", listOf(APP_MODE_BOAT))
			regWidgetAvailability("intermediate_distance", all)
			regWidgetAvailability("distance", all)
			regWidgetAvailability("time", all)
			regWidgetAvailability("intermediate_time", all)
			regWidgetAvailability("map_marker_1st", none)
			regWidgetAvailability("map_marker_2nd", none)
			regWidgetVisibility("bearing", listOf(APP_MODE_BOAT))
			regWidgetVisibility("ruler", all)

			// top
			regWidgetVisibility("config", none)
			regWidgetVisibility("layers", none)
			regWidgetVisibility("compass", none)
			regWidgetVisibility("street_name", exceptAirBoatDefault)
			regWidgetVisibility("back_to_location", all)
			regWidgetVisibility("monitoring_services", none)
			regWidgetVisibility("bgService", none)

			val bundle = Bundle()
			bundle.apply {
				putString("available_application_modes", "$APP_MODE_BOAT,")
				putString("application_mode", APP_MODE_BOAT)
				putString("default_application_mode_string", APP_MODE_BOAT)
				putBoolean("driving_region_automatic", false)
				putBoolean("show_osmand_welcome_screen", false)
				putBoolean("show_coordinates_widget", true)
				putBoolean("show_compass_ruler", true)
				putString("map_info_controls", "ruler;")
				putString("default_metric_system", METRIC_CONST_NAUTICAL_MILES)
				putString("default_speed_system", SPEED_CONST_NAUTICALMILES_PER_HOUR)
				if (!isOsmandCustomized()) {
					putBoolean("map_online_data", true)
				}
			}
			customizeOsmandSettings("strikelines", bundle)
		}
	}

	inner class ViewPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {
		private val fragments = listOf<Fragment>(MapsTabFragment(), PurchasesTabFragment())
		override fun getItem(position: Int) = fragments[position]
		override fun getCount() = fragments.size
	}

	private fun showToastMessage(msg: String) {
		Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
	}

	private fun showSnackBar(
		msg: String,
		parentLayout: View? = snackView,
		lengths: Int = Snackbar.LENGTH_LONG,
		action: Int,
		path: String = ""
	) {
		val snackbar = Snackbar.make(parentLayout!!, msg, lengths)
		when (action) {
			1 -> snackbar.setAction(getString(R.string.snack_update_btn)) { app.loadCharts() }
			2 -> snackbar.setAction(getString(R.string.snack_ok_btn)) { snackbar.dismiss() }
			3 -> snackbar.setAction(getString(R.string.open_action)) {
				if (path.isNotEmpty()) app.openFile(path)
			}
		}
		snackbar.show()
	}

	fun initChartsList() {
		if (StrikeLinesApplication.isDataReadyFlag) {
			chartsDataIsReady = true
			regionList.clear()
			regionList.add(resources.getString(R.string.all_regions))
			StrikeLinesApplication.chartsList.forEach { regionList.add(it.region) }
			notifyFragmentsOnDataChange()
		}
	}

	fun notifyFragmentsOnDataChange() {
		fragmentNotifier.forEach { (_, v) -> v?.onDataReady(true) }
	}

	private var appListener = object : StrikeLinesApplication.AppListener {
		override fun isDataReady(status: Boolean) {
			if (status) {
				initChartsList()
				snackView?.let {
					showSnackBar(
						getString(R.string.snack_msg_update_successful),
						snackView!!,
						action = 2
					)
				}
			} else
				snackView?.let { showSnackBar(getString(R.string.snack_msg_update_failed), snackView!!, action = 1) }
		}
	}

	interface FragmentDataNotifier {
		fun onDataReady(status: Boolean)
	}

	@SuppressLint("InflateParams")
	private fun installOsmandDialog() {
		val appPackageName = "net.osmand"
		val builder = AlertDialog.Builder(this)
		val dialogLayout = layoutInflater.inflate(R.layout.dialog_download_osmand, null)
		builder.setPositiveButton("OK", null)
		builder.setView(dialogLayout)
		builder.setPositiveButton("INSTALL") { dialog, _ ->
			try {
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")))
			} catch (anfe: android.content.ActivityNotFoundException) {
				startActivity(
					Intent(
						Intent.ACTION_VIEW,
						Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")
					)
				)
			}
			dialog.dismiss()
		}
		builder.setNegativeButton("CANCEL") { dialog, _ -> dialog.dismiss() }
		builder.show()
	}

	companion object {
		const val OPEN_DOWNLOADS_TAB_KEY = "open_downloads_tab_key"

		private const val MAPS_TAB_POS = 0
		private const val DOWNLOADS_TAB_POS = 1

		val fragmentNotifier = mutableMapOf<Int, FragmentDataNotifier?>()
		var isOsmandFABWasClicked = false
		var chartsDataIsReady = false
	}
}
