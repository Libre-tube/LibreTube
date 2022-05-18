package com.github.libretube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import java.util.*

class MainActivity : AppCompatActivity() {
    val TAG = "MainActivity"
    lateinit var bottomNavigationView: BottomNavigationView
    lateinit var toolbar: Toolbar
    lateinit var navController : NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        RetrofitInstance.url = sharedPreferences.getString("instance", "https://pipedapi.kavin.rocks/")!!
        SponsorBlockSettings.sponsorBlockEnabled = sharedPreferences.getBoolean("sponsorblock_enabled_key", false)
        SponsorBlockSettings.introEnabled = sharedPreferences.getBoolean("intro_category_key", false)
        SponsorBlockSettings.selfPromoEnabled = sharedPreferences.getBoolean("selfpromo_category_key", false)
        SponsorBlockSettings.interactionEnabled = sharedPreferences.getBoolean("interaction_category_key", false)
        SponsorBlockSettings.sponsorsEnabled = sharedPreferences.getBoolean("sponsors_category_key", false)
        SponsorBlockSettings.outroEnabled = sharedPreferences.getBoolean("outro_category_key", false)

        DynamicColors.applyToActivitiesIfAvailable(application)
        val languageName = sharedPreferences.getString("language", "sys")
        if (languageName != "") {
            var locale = if (languageName != "sys" && "$languageName".length < 3 ){
                Locale(languageName)
            } else if ("$languageName".length > 3) {
                Locale(languageName?.substring(0,2), languageName?.substring(4,6))
            } else {
                Locale.getDefault()
            }
            val res = resources
            val dm = res.displayMetrics
            val conf = res.configuration
            conf.setLocale(locale)
            Locale.setDefault(locale)
            res.updateConfiguration(conf, dm)
        }

        when (sharedPreferences.getString("theme_togglee", "A")!!) {
            "A" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            "L" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "D" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        val connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo=connectivityManager.activeNetworkInfo
        val isConnected = networkInfo != null && networkInfo.isConnected

        if (isConnected == false) {
            setContentView(R.layout.activity_nointernet)
            findViewById<Button>(R.id.retry_button).setOnClickListener() {
                recreate()
            }
        } else {
            setContentView(R.layout.activity_main)

            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            bottomNavigationView = findViewById(R.id.bottomNav)
            navController = findNavController(R.id.fragment)
            bottomNavigationView.setupWithNavController(navController)

            when (sharedPreferences.getString("default_tab", "home")!!) {
                "home" -> navController.navigate(R.id.home2)
                "subscriptions" -> navController.navigate(R.id.subscriptions)
                "library" -> navController.navigate(R.id.library)
            }

            bottomNavigationView.setOnItemSelectedListener {
                when (it.itemId) {
                    R.id.home2 -> {
                        navController.backQueue.clear()
                        navController.navigate(R.id.home2)
                        true
                    }
                    R.id.subscriptions -> {
                        //navController.backQueue.clear()
                        navController.navigate(R.id.subscriptions)
                        true
                    }
                    R.id.library -> {
                        //navController.backQueue.clear()
                        navController.navigate(R.id.library)
                        true
                    }
                }
                false
            }

            toolbar = findViewById(R.id.toolbar)
            val typedValue = TypedValue()
            this.theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true)
            val hexColor = String.format("#%06X", (0xFFFFFF and typedValue.data))
            val appName = HtmlCompat.fromHtml(
                "Libre<span  style='color:$hexColor';>Tube</span>",
                HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            toolbar.title = appName

        toolbar.setNavigationOnClickListener{
            //settings activity stuff
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            true
        }

            toolbar.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_search -> {
                        navController.navigate(R.id.searchFragment)
                        true
                    }
                }
                false
            }
        }

    }

    override fun onStart() {
        super.onStart()
        val action: String? = intent?.action
        val data: Uri? = intent?.data
        Log.d(TAG, "dafaq"+data.toString())

        if (data != null) {
            Log.d("dafaq",data.host+" ${data.path} ")
            if(data.host != null){
                    if(data.path != null){
                        //channel
                        if(data.path!!.contains("/channel/") || data.path!!.contains("/c/") || data.path!!.contains("/user/")){
                            var channel = data.path
                            channel = channel!!.replace("/c/","")
                            channel = channel!!.replace("/user/","")
                            val bundle = bundleOf("channel_id" to channel)
                            navController.navigate(R.id.channel,bundle)
                        }else if(data.path!!.contains("/playlist")){
                            var playlist = data.query!!
                            if (playlist.contains("&"))
                            {
                                var playlists = playlist.split("&")
                                for (v in playlists){
                                    if (v.contains("list=")){
                                        playlist = v
                                        break
                                    }
                                }
                            }
                            playlist = playlist.replace("list=","")
                            val bundle = bundleOf("playlist_id" to playlist)
                            navController.navigate(R.id.playlistFragment,bundle)
                        }else if(data.path!!.contains("/shorts/") || data.path!!.contains("/embed/") || data.path!!.contains("/v/")){
                            var watch = data.path!!.replace("/shorts/","").replace("/v/","").replace("/embed/","")
                            var bundle = Bundle()
                            bundle.putString("videoId",watch)
                            var frag = PlayerFragment()
                            frag.arguments = bundle
                            supportFragmentManager.beginTransaction()
                                .remove(PlayerFragment())
                                .commit()
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.container, frag)
                                .commitNow()
                            Handler().postDelayed({
                                val motionLayout = findViewById<MotionLayout>(R.id.playerMotionLayout)
                                motionLayout.transitionToEnd()
                                motionLayout.transitionToStart()
                            }, 100)
                        }else if(data.path!!.contains("/watch") && data.query != null){
                            Log.d("dafaq",data.query!!)
                            var watch = data.query!!
                            if (watch.contains("&"))
                            {
                                var watches = watch.split("&")
                                for (v in watches){
                                    if (v.contains("v=")){
                                        watch = v
                                        break
                                    }
                                }
                            }
                            var bundle = Bundle()
                            bundle.putString("videoId",watch.replace("v=",""))
                            var frag = PlayerFragment()
                            frag.arguments = bundle
                            supportFragmentManager.beginTransaction()
                                .remove(PlayerFragment())
                                .commit()
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.container, frag)
                                .commitNow()
                            Handler().postDelayed({
                                val motionLayout = findViewById<MotionLayout>(R.id.playerMotionLayout)
                                motionLayout.transitionToEnd()
                                motionLayout.transitionToStart()
                            }, 100)

                        }else{
                            var watch = data.path!!.replace("/","")
                            var bundle = Bundle()
                            bundle.putString("videoId",watch)
                            var frag = PlayerFragment()
                            frag.arguments = bundle
                            supportFragmentManager.beginTransaction()
                                .remove(PlayerFragment())
                                .commit()
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.container, frag)
                                .commitNow()
                            Handler().postDelayed({
                                val motionLayout = findViewById<MotionLayout>(R.id.playerMotionLayout)
                                motionLayout.transitionToEnd()
                                motionLayout.transitionToStart()
                            }, 100)
                        }
                    }

            }

        }
    }

    override fun onBackPressed() {
        try{
            val mainMotionLayout = findViewById<MotionLayout>(R.id.mainMotionLayout)
            if (mainMotionLayout.progress == 0.toFloat()){
                mainMotionLayout.transitionToEnd()
                findViewById<ConstraintLayout>(R.id.main_container).isClickable=false
                val motionLayout = findViewById<MotionLayout>(R.id.playerMotionLayout)
                motionLayout.transitionToEnd()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                with(motionLayout) {
                    getConstraintSet(R.id.start).constrainHeight(R.id.player, 0)
                    enableTransition(R.id.yt_transition,true)
                }
                findViewById<LinearLayout>(R.id.linLayout).visibility=View.VISIBLE
                isFullScreen=false
            }else{
                navController.popBackStack()
                if (navController.currentBackStackEntry == null && (parent as View).id != R.id.settings){
                    super.onBackPressed()
                }
            }
        }catch (e: Exception){
            navController.popBackStack()
            moveTaskToBack(true)
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = newConfig.orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            println("Portrait")
            unsetFullscreen()
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            println("Landscape")
            setFullscreen()
        }
    }
    private fun setFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
    private fun unsetFullscreen(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.apply {
                show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        supportFragmentManager.fragments.forEach { fragment ->
            (fragment as? PlayerFragment)?.onUserLeaveHint()
        }
    }

}
fun Fragment.hideKeyboard() {
    view?.let { activity?.hideKeyboard(it) }
}

fun Activity.hideKeyboard() {
    hideKeyboard(currentFocus ?: View(this))
}

fun Context.hideKeyboard(view: View) {
    val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
}
