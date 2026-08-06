package com.bilicraft.handheld.power

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级「界面是否可见」信号，供后台省电策略判断。
 *
 * 用 ActivityLifecycleCallbacks 而不是 lifecycle-process：本项目只有两个 Activity，
 * 计数已经足够，不值得为此多引一个依赖。
 */
class AppVisibilityTracker private constructor() : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        _isForeground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        _isForeground.value = startedActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        fun install(application: Application): AppVisibilityTracker =
            AppVisibilityTracker().also(application::registerActivityLifecycleCallbacks)
    }
}
