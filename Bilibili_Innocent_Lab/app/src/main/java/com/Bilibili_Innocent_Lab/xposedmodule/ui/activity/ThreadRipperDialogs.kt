package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperCdnPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import android.widget.EditText as NativeEditText
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/*
 * 线程撕裂者（播放加速）的取值弹窗。沿用播放器取值弹窗的模态底座与进退场动画；
 * 只写模块配置，宿主侧在 B 站下次主进程启动时读取。
 */

internal fun MainActivity.threadRipperModeLabel(key: String): String = when (ThreadRipperCdnPolicy.Mode.of(key)) {
    ThreadRipperCdnPolicy.Mode.MAINLAND -> getString(R.string.thread_ripper_mode_mainland)
    ThreadRipperCdnPolicy.Mode.OVERSEAS -> getString(R.string.thread_ripper_mode_overseas)
    ThreadRipperCdnPolicy.Mode.CUSTOM -> getString(R.string.thread_ripper_mode_custom)
}

internal fun MainActivity.threadRipperConcurrencyLabel(): String =
    if (threadRipperAutoConcurrency) getString(R.string.thread_ripper_concurrency_auto)
    else getString(R.string.thread_ripper_concurrency_value, threadRipperConcurrency)

internal fun MainActivity.threadRipperCustomHostsLabel(): String {
    val count = ThreadRipperConfig.decodeCustomHosts(threadRipperCustomHosts).size
    return if (count == 0) getString(R.string.thread_ripper_custom_hosts_none)
    else getString(R.string.thread_ripper_custom_hosts_current, count)
}

internal fun MainActivity.updateThreadRipperSummaries() {
    threadRipperModeSummaryView?.text =
        getString(R.string.thread_ripper_cdn_mode_current, threadRipperModeLabel(threadRipperMode))
    threadRipperConcurrencySummaryView?.text =
        getString(R.string.thread_ripper_concurrency_current, threadRipperConcurrencyLabel())
    threadRipperCustomHostsSummaryView?.text = threadRipperCustomHostsLabel()
}

/** apply() 不阻塞 UI；立即读回只用于发现本次写入是否被当前 bridge 拒绝。 */
private fun MainActivity.saveThreadRipper(change: android.content.SharedPreferences.Editor.() -> Unit, verify: (android.content.SharedPreferences) -> Boolean): Boolean =
    runCatching {
        val preferences = prefs()
        preferences.edit { change() }
        check(verify(preferences))
    }.onFailure { throwable ->
        Log.e("BilibiliInnocentLab", "write thread ripper prefs failed", throwable)
    }.isSuccess

private fun MainActivity.showThreadRipperChoiceDialog(
    title: String,
    options: List<Triple<String, String, Boolean>>,
    anchor: View?,
    onSelected: (Int) -> Boolean,
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = title
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
    val rows = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    options.forEachIndexed { index, (label, subtitle, selected) ->
        rows.addView(
            createGitHubMenuRow(label, subtitle, selected) {
                if (onSelected(index)) dismissWithAnimation(dialog, container) {}
            },
            NativeLinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = (4 * density).toInt()
            }
        )
    }
    val listCap = minOf((420 * density).toInt(), (resources.displayMetrics.heightPixels * 0.5f).toInt())
    container.addView(MaxHeightScrollView(this, listCap).apply {
        addView(rows, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2))
    container.addView(closeButton(dialog, container), NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (18 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}

private fun MainActivity.closeButton(dialog: Dialog, container: NativeLinearLayout): NativeTextView {
    val density = resources.displayMetrics.density
    return NativeTextView(this).apply {
        text = getString(R.string.dialog_close)
        textColor = getColor(R.color.colorTextGray)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding((20 * density).toInt(), (11 * density).toInt(), (20 * density).toInt(), (11 * density).toInt())
        background = selfRippleBackground(14f)
        isClickable = true
        isFocusable = true
        setOnClickListener { dismissWithAnimation(dialog, container) {} }
    }
}

internal fun MainActivity.showThreadRipperModeDialog(anchor: View? = null) {
    val modes = ThreadRipperCdnPolicy.Mode.entries
    val options = modes.map { mode ->
        Triple(
            threadRipperModeLabel(mode.key),
            getString(when (mode) {
                ThreadRipperCdnPolicy.Mode.MAINLAND -> R.string.thread_ripper_mode_mainland_tip
                ThreadRipperCdnPolicy.Mode.OVERSEAS -> R.string.thread_ripper_mode_overseas_tip
                ThreadRipperCdnPolicy.Mode.CUSTOM -> R.string.thread_ripper_mode_custom_tip
            }),
            mode.key == threadRipperMode
        )
    }
    showThreadRipperChoiceDialog(getString(R.string.thread_ripper_cdn_mode), options, anchor) { index ->
        val next = modes[index].key
        if (!saveThreadRipper({ putString(FeaturePreferences.THREAD_RIPPER_CDN_MODE, next) },
                { it.getString(FeaturePreferences.THREAD_RIPPER_CDN_MODE, null) == next })) {
            toast(getString(R.string.thread_ripper_save_failed))
            return@showThreadRipperChoiceDialog false
        }
        threadRipperMode = next
        updateThreadRipperSummaries()
        true
    }
}

/** "自动"与具体档位放在同一张单选表里，对应原项目线程数下拉框里的"自动"。 */
internal fun MainActivity.showThreadRipperConcurrencyDialog(anchor: View? = null) {
    val choices = listOf<Int?>(null) + ThreadRipperConfig.CONCURRENCY_CHOICES
    val options = choices.map { value ->
        if (value == null) Triple(getString(R.string.thread_ripper_concurrency_auto),
            getString(R.string.thread_ripper_concurrency_auto_tip), threadRipperAutoConcurrency)
        else Triple(getString(R.string.thread_ripper_concurrency_value, value),
            getString(R.string.thread_ripper_concurrency_value_tip),
            !threadRipperAutoConcurrency && value == threadRipperConcurrency)
    }
    showThreadRipperChoiceDialog(getString(R.string.thread_ripper_concurrency), options, anchor) { index ->
        val value = choices[index]
        val auto = value == null
        val threads = value ?: threadRipperConcurrency
        if (!saveThreadRipper({
                putBoolean(FeaturePreferences.THREAD_RIPPER_AUTO_CONCURRENCY, auto)
                putInt(FeaturePreferences.THREAD_RIPPER_CONCURRENCY, threads)
            }, {
                it.getBoolean(FeaturePreferences.THREAD_RIPPER_AUTO_CONCURRENCY, !auto) == auto &&
                    it.getInt(FeaturePreferences.THREAD_RIPPER_CONCURRENCY, Int.MIN_VALUE) == threads
            })) {
            toast(getString(R.string.thread_ripper_save_failed))
            return@showThreadRipperChoiceDialog false
        }
        threadRipperAutoConcurrency = auto
        threadRipperConcurrency = threads
        updateThreadRipperSummaries()
        true
    }
}

/** 每行一个主机名；保存前按原项目规则规范化，非 B 站视频服务器一律丢弃。 */
internal fun MainActivity.showThreadRipperCustomHostsDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.thread_ripper_custom_hosts)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.thread_ripper_custom_hosts_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    val editor = NativeEditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = false
        minLines = 3
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
        filters = arrayOf(android.text.InputFilter.LengthFilter(ThreadRipperConfig.MAX_CUSTOM_HOSTS_LENGTH))
        hint = getString(R.string.thread_ripper_custom_hosts_hint)
        setText(ThreadRipperConfig.decodeCustomHosts(threadRipperCustomHosts).joinToString("\n"))
        setSelection(text.length)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(getColor(R.color.colorTextGray))
        textSize = 14f
        setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(monetColors.surfaceVariant)
        }
    }
    container.addView(editor, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * density).toInt() })
    // 已知节点：点一下追加到编辑框，不重复。
    val known = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    ThreadRipperCdnPolicy.KNOWN_HOSTS.forEach { host ->
        known.addView(NativeTextView(this).apply {
            text = host
            textColor = getColor(R.color.colorTextGray)
            textSize = 13f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val current = ThreadRipperConfig.decodeCustomHosts(editor.textToString())
                if (host !in current) {
                    editor.setText((current + host).joinToString("\n"))
                    editor.setSelection(editor.text.length)
                }
            }
        }, NativeLinearLayout.LayoutParams(-1, -2))
    }
    val knownCap = minOf((180 * density).toInt(), (resources.displayMetrics.heightPixels * 0.25f).toInt())
    container.addView(MaxHeightScrollView(this, knownCap).apply {
        addView(known, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
    val buttons = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    listOf(R.string.dialog_cancel, R.string.dialog_confirm).forEach { label ->
        buttons.addView(NativeTextView(this).apply {
            text = getString(label)
            textColor = if (label == R.string.dialog_confirm) monetColors.primary else getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (label == R.string.dialog_cancel) {
                    dismissWithAnimation(dialog, container) {}
                    return@setOnClickListener
                }
                val encoded = ThreadRipperConfig.encodeCustomHosts(ThreadRipperConfig.decodeCustomHosts(editor.textToString()))
                if (saveThreadRipper({ putString(FeaturePreferences.THREAD_RIPPER_CUSTOM_HOSTS, encoded) },
                        { it.getString(FeaturePreferences.THREAD_RIPPER_CUSTOM_HOSTS, null) == encoded })) {
                    threadRipperCustomHosts = encoded
                    updateThreadRipperSummaries()
                    dismissWithAnimation(dialog, container) {}
                } else {
                    editor.error = getString(R.string.thread_ripper_save_failed)
                }
            }
        })
    }
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}
