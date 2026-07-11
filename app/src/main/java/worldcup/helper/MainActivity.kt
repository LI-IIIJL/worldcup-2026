package worldcup.helper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import worldcup.helper.ui.live.LiveFragment
import worldcup.helper.ui.ai.AiChatFragment
import worldcup.helper.ui.schedule.ScheduleFragment
import worldcup.helper.ui.data.DataFragment

class MainActivity : AppCompatActivity() {

    private var bottomNav: BottomNavigationView? = null
    private val liveFragment = LiveFragment()
    private val aiFragment = AiChatFragment()
    private val scheduleFragment = ScheduleFragment()
    private val dataFragment = DataFragment()
    private var activeFragment: Fragment = scheduleFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mainContainer = findViewById<android.view.View>(R.id.main_container)
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = insets.top)
            WindowInsetsCompat.CONSUMED
        }

        bottomNav = findViewById(R.id.bottom_nav)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, dataFragment, "data").hide(dataFragment)
                .add(R.id.fragment_container, aiFragment, "ai").hide(aiFragment)
                .add(R.id.fragment_container, liveFragment, "live").hide(liveFragment)
                .add(R.id.fragment_container, scheduleFragment, "schedule")
                .commit()
        }

        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_live -> switchTo(liveFragment)
                R.id.nav_ai -> switchTo(aiFragment)
                R.id.nav_schedule -> switchTo(scheduleFragment)
                R.id.nav_data -> switchTo(dataFragment)
                else -> false
            }
        }

        // 支持 adb shell 启动时指定 Tab
        val launchTab = intent.getStringExtra("launch_tab") ?: "schedule"
        val tabId = when (launchTab) {
            "live" -> R.id.nav_live
            "ai" -> R.id.nav_ai
            "data" -> R.id.nav_data
            else -> R.id.nav_schedule
        }
        bottomNav?.selectedItemId = tabId
    }

    private fun switchTo(fragment: Fragment): Boolean {
        if (fragment == activeFragment) return true
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(fragment)
            .commit()
        activeFragment = fragment
        return true
    }
}
