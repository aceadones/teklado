// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.addPinnedKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.getToolbarPanelKeys
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removePinnedKey
import helium314.keyboard.latin.utils.toolbarKeyStrings
import helium314.keyboard.latin.utils.toolbarToggleEnabled

/**
 * Grid of toolbar keys that replaces the keyboard body, the same way
 * [ClipboardHistoryView] and the emoji palettes do. Height matches the current keyboard.
 */
class ToolbarPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle),
    View.OnClickListener,
    View.OnLongClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val pinIconId: Int
    private lateinit var editButton: View
    private lateinit var editIcon: ImageView
    private lateinit var editLabel: TextView
    private lateinit var doneButton: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var keysContainer: LinearLayout
    private var keyboardActionListener: KeyboardActionListener? = null
    private var editMode = false
    private var keysReady = false

    init {
        val styled = context.obtainStyledAttributes(
            null, R.styleable.ClipboardHistoryView, R.attr.clipboardHistoryViewStyle, R.style.ClipboardHistoryView)
        pinIconId = styled.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
            .takeIf { it != 0 } ?: R.drawable.ic_clipboard_pin_lxx
        styled.recycle()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        editButton = findViewById(R.id.toolbar_panel_edit_pins)
        editIcon = findViewById(R.id.toolbar_panel_edit_icon)
        editLabel = findViewById(R.id.toolbar_panel_edit_label)
        doneButton = findViewById(R.id.toolbar_panel_done)
        scrollView = findViewById(R.id.toolbar_panel_scroll)
        keysContainer = findViewById(R.id.toolbar_panel_keys)
        editButton.isSoundEffectsEnabled = false
        editButton.isHapticFeedbackEnabled = false
        doneButton.isSoundEffectsEnabled = false
        doneButton.isHapticFeedbackEnabled = false
        editButton.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(editButton, HapticEvent.KEY_PRESS)
            setEditMode(true)
        }
        doneButton.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(doneButton, HapticEvent.KEY_PRESS)
            setEditMode(false)
        }
        applyColors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = keyboardAreaWidth()
        val height = keyboardAreaHeight()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    fun startToolbarPanel() {
        editMode = false
        applyColors()
        rebuildTiles()
        editButton.isVisible = true
        doneButton.isVisible = false
        scrollView.scrollTo(0, 0)
    }

    fun stopToolbarPanel() {
        editMode = false
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (!keysReady || key != Settings.PREF_PINNED_TOOLBAR_KEYS) return
        updatePinBadges()
    }

    override fun onClick(view: View) {
        val key = view.tag as? ToolbarKey ?: return
        if (editMode) {
            togglePin(key)
            return
        }
        onClickToolbarKey(view) { code ->
            keyboardActionListener?.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            closeIfStillOpen()
        }
    }

    override fun onLongClick(view: View): Boolean {
        val key = view.tag as? ToolbarKey ?: return false
        if (editMode) return true
        // Arrow keys repeat while held; leave the panel up so the repeat isn't cut off.
        val repeats = getCodeForToolbarKeyLongClick(key) == KeyCode.KEY_REPEAT
        onLongClickToolbarKey(view) { code, isRepeat ->
            keyboardActionListener?.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isRepeat)
            if (!repeats) closeIfStillOpen()
        }
        return true
    }

    private fun closeIfStillOpen() {
        val switcher = KeyboardSwitcher.getInstance()
        if (switcher.isShowingToolbarPanel()) switcher.hideToolbarPanel()
    }

    private fun setEditMode(editing: Boolean) {
        if (editMode == editing && keysReady) {
            updatePinBadges()
            return
        }
        editMode = editing
        if (this::editButton.isInitialized) {
            editButton.isVisible = !editing
            doneButton.isVisible = editing
        }
        updatePinBadges()
    }

    private fun togglePin(key: ToolbarKey) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        val prefs = context.prefs()
        if (key in getPinnedToolbarKeys(prefs)) removePinnedKey(prefs, key)
        else addPinnedKey(prefs, key)
        // SuggestionStripView refreshes the pinned strip from this pref change.
        updatePinBadges()
    }

    private fun applyColors() {
        if (!this::editLabel.isInitialized) return
        val colors = Settings.getValues().mColors
        editLabel.setTextColor(colors.get(ColorType.KEY_TEXT))
        doneButton.setTextColor(colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND))
        editIcon.drawable?.mutate()?.let { drawable ->
            DrawableCompat.setTint(drawable, colors.get(ColorType.KEY_TEXT))
        }
    }

    private fun rebuildTiles() {
        keysContainer.removeAllViews()
        val prefs = context.prefs()
        val pinned = getPinnedToolbarKeys(prefs).toSet()
        val margin = 4.dpToPx(resources)
        for (rowKeys in getToolbarPanelKeys(prefs).chunked(COLUMN_COUNT)) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            for (key in rowKeys) {
                val tile = createTile(key, key in pinned)
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(margin, margin, margin, margin)
                })
            }
            repeat(COLUMN_COUNT - rowKeys.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply {
                    setMargins(margin, margin, margin, margin)
                })
            }
            keysContainer.addView(row)
        }
        keysReady = true
    }

    private fun createTile(key: ToolbarKey, pinned: Boolean): View {
        val colors = Settings.getValues().mColors
        // Locale.US: Turkish locale would turn ASCII I into dotless ı and miss the string resource.
        val label = toolbarKeyStrings.getValue(key).getStringResourceOrName("", context)
        val tile = FrameLayout(context).apply {
            tag = key
            minimumHeight = 76.dpToPx(resources)
            setPadding(4.dpToPx(resources), 10.dpToPx(resources), 4.dpToPx(resources), 8.dpToPx(resources))
            background = newTileBackground()
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            isFocusable = false
            contentDescription = label
            setOnClickListener(this@ToolbarPanelView)
            setOnLongClickListener(this@ToolbarPanelView)
        }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(28.dpToPx(resources), 28.dpToPx(resources))
        }
        bindIcon(icon, key)
        val text = TextView(context).apply {
            this.text = label
            gravity = Gravity.CENTER
            textAlignment = TEXT_ALIGNMENT_CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(colors.get(ColorType.KEY_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx(resources) }
        }
        content.addView(icon)
        content.addView(text)
        tile.addView(content)
        if (pinIconId != 0) {
            val pin = ImageView(context).apply {
                tag = PIN_BADGE
                setImageResource(pinIconId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                isVisible = editMode && pinned
                layoutParams = FrameLayout.LayoutParams(
                    16.dpToPx(resources), 16.dpToPx(resources), Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = 4.dpToPx(resources)
                    marginEnd = 4.dpToPx(resources)
                }
            }
            colors.setColor(pin, ColorType.CLIPBOARD_PIN)
            tile.addView(pin)
        }
        return tile
    }

    private fun bindIcon(iconView: ImageView, key: ToolbarKey) {
        val drawable = KeyboardIconsSet.instance.getNewDrawable(key.name, context) ?: return
        iconView.setImageDrawable(drawable)
        val colors = Settings.getValues().mColors
        if (toolbarToggleEnabled(key, context) == true) {
            DrawableCompat.setTint(drawable, colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND))
        } else {
            colors.setColor(drawable, ColorType.KEY_ICON)
        }
    }

    private fun updatePinBadges() {
        if (!keysReady) return
        val pinned = getPinnedToolbarKeys(context.prefs()).toSet()
        for (r in 0 until keysContainer.childCount) {
            val row = keysContainer.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until row.childCount) {
                val tile = row.getChildAt(c)
                val key = tile.tag as? ToolbarKey ?: continue
                tile.findViewWithTag<View>(PIN_BADGE)?.isVisible = editMode && key in pinned
            }
        }
    }

    private fun newTileBackground(): Drawable {
        val colors = Settings.getValues().mColors
        val normal = colors.get(ColorType.KEY_BACKGROUND)
        val pressed = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        return GradientDrawable().apply {
            cornerRadius = 12.dpToPx(resources).toFloat()
            setColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(pressed, normal)
            ))
        }
    }

    private fun keyboardAreaWidth(): Int {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView
        val keyboard = keyboardView?.keyboard
        if (keyboardView != null && keyboard != null) {
            val width = keyboard.mOccupiedWidth + keyboardView.paddingLeft + keyboardView.paddingRight
            if (width > 0) return width
        }
        return ResourceUtils.getKeyboardWidth(context, Settings.getValues())
    }

    private fun keyboardAreaHeight(): Int {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView
        val keyboard = keyboardView?.keyboard
        if (keyboardView != null && keyboard != null) {
            val height = keyboard.mOccupiedHeight + keyboardView.paddingTop + keyboardView.paddingBottom
            if (height > 0) return height
        }
        return ResourceUtils.getKeyboardHeight(resources, Settings.getValues())
    }

    companion object {
        private const val COLUMN_COUNT = 4
        private const val PIN_BADGE = "pin"
    }
}
