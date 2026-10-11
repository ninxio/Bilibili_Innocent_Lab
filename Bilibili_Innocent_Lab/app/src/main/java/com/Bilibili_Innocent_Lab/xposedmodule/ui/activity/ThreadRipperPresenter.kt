package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperCdnPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout as HikageLinearLayout
import com.highcapable.hikage.widget.android.widget.TextView as HikageTextView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch as HikageMaterialSwitch

/*
 * 「增强 → 播放」里的线程撕裂者设置行。小标题留在 MainActivity（它的样式函数是私有的），
 * 这里只放两个开关与三行弹窗取值，样式与上方的解码设置一致。
 */

internal fun MainActivity.loadThreadRipperSettings(uiSettings: ModuleUiSettings) {
    threadRipperMode = ThreadRipperCdnPolicy.Mode.of(uiSettings.string(FeaturePreferences.THREAD_RIPPER_CDN_MODE)).key
    threadRipperConcurrency = ThreadRipperSettings.normalizeConcurrency(uiSettings.int(FeaturePreferences.THREAD_RIPPER_CONCURRENCY))
    threadRipperAutoConcurrency = uiSettings.bool(FeaturePreferences.THREAD_RIPPER_AUTO_CONCURRENCY)
    threadRipperCustomHosts = uiSettings.string(FeaturePreferences.THREAD_RIPPER_CUSTOM_HOSTS)
}

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.threadRipperRows(performer: Hikage.Performer<LinearLayout.LayoutParams>, uiSettings: ModuleUiSettings) {
    with(performer) {
        listOf(
            Triple(FeaturePreferences.THREAD_RIPPER_ENABLED, R.string.thread_ripper_enabled, R.string.thread_ripper_tip),
            Triple(FeaturePreferences.THREAD_RIPPER_MULTITHREAD, R.string.thread_ripper_multithread,
                R.string.thread_ripper_multithread_tip)
        ).forEach { (key, label, tip) ->
            HikageMaterialSwitch(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                bindFavoriteSwitch(this, key)
                text = stringResource(label)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = uiSettings.bool(key)
                setOnCheckedChangeListener { _, checked ->
                    runCatching { prefs().edit { putBoolean(key, checked) } }.onFailure {
                        Log.e("BilibiliInnocentLab", "write thread ripper prefs failed", it)
                    }
                }
            }
            HikageTextView(lparams = LayoutParams(widthMatchParent = true)) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
        listOf(
            Triple(R.string.thread_ripper_cdn_mode, FeaturePreferences.THREAD_RIPPER_CDN_MODE,
                { anchor: View -> showThreadRipperModeDialog(anchor) }),
            Triple(R.string.thread_ripper_concurrency, FeaturePreferences.THREAD_RIPPER_CONCURRENCY,
                { anchor: View -> showThreadRipperConcurrencyDialog(anchor) }),
            Triple(R.string.thread_ripper_custom_hosts, FeaturePreferences.THREAD_RIPPER_CUSTOM_HOSTS,
                { anchor: View -> showThreadRipperCustomHostsDialog(anchor) })
        ).forEachIndexed { index, (titleRes, key, open) ->
            HikageLinearLayout(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = if (index == 0) 12.dp else 8.dp
                },
                init = {
                    orientation = LinearLayout.VERTICAL
                    background = selfRippleBackground(10f)
                    updatePadding(horizontal = 0.dp, vertical = 9.dp)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { open(this) }
                    bindSettingDestination(this, key)
                }
            ) {
                HikageTextView(lparams = LayoutParams(widthMatchParent = true)) {
                    text = stringResource(titleRes)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                }
                HikageTextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                    alpha = 0.6f
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                    setLineSpacing(6f, 1f)
                    when (index) {
                        0 -> threadRipperModeSummaryView = this
                        1 -> threadRipperConcurrencySummaryView = this
                        else -> threadRipperCustomHostsSummaryView = this
                    }
                }
            }
        }
        updateThreadRipperSummaries()
    }
}
