package com.strikelines.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.strikelines.app.domain.models.Chart
import com.strikelines.app.domain.models.Charts
import com.strikelines.app.ui.MainActivity
import com.strikelines.app.utils.GetRequestAsync
import com.strikelines.app.utils.OnRequestResultListener
import org.json.JSONObject
import java.io.IOException

class StrikeLinesApplication : Application() {

	private val uiHandler = Handler()
	lateinit var osmandHelper: OsmandHelper private set

    private lateinit var sp: SharedPreferences
    private val gson by lazy { GsonBuilder().setLenient().create() }

    private var isUpdatedInThisSession = false


	override fun onCreate() {
		super.onCreate()
		osmandHelper = OsmandHelper(this)
        sp  = this.getSharedPreferences(spName, 0)
        loadCharts()
	}

    private fun loadCharts() {
        if(sp.contains(chartsDataKey) && sp.getString(chartsDataKey, "")!!.isNotEmpty()) {
            chartsList.clear()
            chartsList.addAll(parseJson(sp.getString(chartsDataKey, "{}")))
            isDataReadyFlag = true
            listener?.isDataReady(true)
            if(!isUpdatedInThisSession) GetRequestAsync(url, onRequestResultListener).execute()
        } else {
            GetRequestAsync(url, onRequestResultListener).execute()
        }

    }

    @SuppressLint("ApplySharedPref")
    fun updateData(result:String) {
        sp.edit()
            .putString(chartsDataKey, result)
            .putInt(chartsTimeKey, (System.currentTimeMillis()/1000L).toInt())
            .commit()
        isUpdatedInThisSession = true
        loadCharts()
    }

    fun cleanupResources() {
		osmandHelper.cleanupResources()
	}

	fun runInUI(action: (() -> Unit)) {
		uiHandler.post(action)
	}

	fun runInUI(action: (() -> Unit), delay: Long) {
		uiHandler.postDelayed(action, delay)
	}

	fun showToastMessage(msg: String) {
		uiHandler.post { Toast.makeText(this@StrikeLinesApplication, msg, Toast.LENGTH_LONG).show() }
	}

    private fun parseJson(response: String?): List<Chart> {
        val charts: Charts = gson.fromJson(response, Charts::class.java)
        return charts.charts
    }

    //mock, delete later
    private fun loadJSONFromAsset(): String? {
        var json: String? = null
        try {
            val inputStream = this.assets.open("json/strike.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            json = String(buffer)
        } catch (ex: IOException) {
            Log.w(ex.message,ex)
            return null
        }
        return json
    }

    private val onRequestResultListener = object : OnRequestResultListener {
        override fun onResult(result: String) {
            when{
                result.startsWith("{") -> updateData(result) //need more sophisticated check for Json validity
                else -> {
                    listener?.isDataReady(false)
                }
            }
        }
    }

    interface AppListener{
        fun isDataReady(status:Boolean)
    }

    companion object {

        private const val spName = "StrikeLinesSP"
        private const val chartsDataKey = "storedCharts"
        private const val chartsTimeKey = "updateTimestamp"
        private const val url = "https://strikelines.com/api/charts/?key=A3dgmiOM1ul@IG1N=*@q"

        var isDataReadyFlag = false
        var listener: AppListener? = null
        val chartsList = mutableListOf<Chart>()

        private var instance: StrikeLinesApplication? = null
        fun applicationContext(): Context = instance!!.applicationContext
    }

	init {
		instance = this
	}
}
