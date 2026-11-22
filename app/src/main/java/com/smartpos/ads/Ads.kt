package com.smartpos.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * AdMob ads (donut4u pos real ad units).
 * Never tap your own ads: Google can ban the AdMob account for that.
 */
object Ads {
    const val APP_OPEN_UNIT = "ca-app-pub-5625332026157890/6813573243"
    const val BANNER_UNIT = "ca-app-pub-5625332026157890/1946717381"

    /** The start-up ad is shown at most once in this many milliseconds (4 hours). */
    private const val MIN_GAP_MS = 4L * 60 * 60 * 1000

    /** If the ad has not loaded within this time after the app opened, skip it. */
    private const val SHOW_WINDOW_MS = 6_000L

    fun init(context: Context) {
        try {
            MobileAds.initialize(context.applicationContext) {}
        } catch (e: Exception) {
            // ads are optional: never crash the POS because of them
        }
    }

    /** Shows one full-screen ad right after the app is opened (not more than once every 4 hours). */
    fun showOnStart(activity: Activity) {
        try {
            val appContext = activity.applicationContext
            val prefs = appContext.getSharedPreferences("ads", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong("last", 0L) < MIN_GAP_MS) return
            val started = SystemClock.elapsedRealtime()
            AppOpenAd.load(
                appContext,
                APP_OPEN_UNIT,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        if (activity.isFinishing || activity.isDestroyed) return
                        if (SystemClock.elapsedRealtime() - started > SHOW_WINDOW_MS) return
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdShowedFullScreenContent() {
                                prefs.edit().putLong("last", System.currentTimeMillis()).apply()
                            }
                        }
                        ad.show(activity)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        // no ad available: just carry on
                    }
                }
            )
        } catch (e: Exception) {
            // ads are optional
        }
    }
}

/** A small banner strip. Put it where it cannot be tapped by accident. */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = Ads.BANNER_UNIT
        }
    }
    DisposableEffect(adView) {
        try {
            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            // ads are optional
        }
        onDispose { adView.destroy() }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { adView })
    }
}
