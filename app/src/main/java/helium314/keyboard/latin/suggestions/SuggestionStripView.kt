/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.kitchen.KitchenImageCache
import helium314.keyboard.latin.kitchen.KitchenSticker
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.addPinnedKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.isToolbarVoiceKeyVisible
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removeFirst
import helium314.keyboard.latin.utils.removePinnedKey
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onKitchenStickerPicked(sticker: KitchenSticker, emojiText: String)
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys)
    private val kitchenSlot: View = findViewById(R.id.kitchen_slot)
    private val kitchenImage: ImageView = findViewById(R.id.kitchen_sticker)
    private lateinit var kitchenPlaceholder: GradientDrawable
    private var kitchenSticker: KitchenSticker? = null
    private var kitchenEmojiText: String = ""
    private var kitchenGeneration = 0
    private var kitchenReady = false
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val toolbarExpandKey = findViewById<ImageButton>(R.id.suggestions_strip_toolbar_key)
    private val toolbarMenuIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val toolbarCloseIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.CLOSE_HISTORY.name, context)
    private var toolbarPanelOpen = false
    private val defaultToolbarBackground: Drawable = toolbarExpandKey.background
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    // must be declared before init, which already updates the strip slots
    private var suggestedWords = SuggestedWords.getEmptyInstance()

    // pinned keys share the whole strip equally, like the three suggestion slots
    private val pinnedKeyLayoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

    init {
        val colors = Settings.getValues().mColors

        // menu key (opens the toolbar panel; was the sliding-toolbar chevron)
        // weird way of setting size (default is config_suggestions_strip_edge_key_width)
        // but better not change it or people will complain
        val toolbarHeight = min(toolbarExpandKey.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
        toolbarExpandKey.layoutParams.height = toolbarHeight
        toolbarExpandKey.layoutParams.width = toolbarHeight // we want it square
        colors.setBackground(toolbarExpandKey, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
        colors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(toolbarExpandKey.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS) {
            setToolbarVisibility(true)
        }

        // toolbar keys setup (no need to hide them any more when locked, because then suggestion strip is gone anyway
        for (key in getEnabledToolbarKeys(context.prefs())) {
            val button = createToolbarKey(context, key)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            toolbar.addView(button)
        }
        refreshPinnedKeys()
        kitchenPlaceholder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8.dpToPx(resources).toFloat()
            val ink = colors.get(ColorType.KEY_TEXT)
            setColor((ink and 0x00FFFFFF) or 0x33000000)
        }
        kitchenImage.clipToOutline = true
        kitchenImage.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 8.dpToPx(resources).toFloat())
            }
        }
        kitchenImage.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        kitchenSlot.contentDescription = resources.getString(R.string.emoji_kitchen_sticker)
        kitchenSlot.setOnClickListener { onKitchenStickerClick() }
        kitchenSlot.isSoundEffectsEnabled = false
        kitchenSlot.isHapticFeedbackEnabled = false
        toolbarContainer.doOnNextLayout {
            // set min with of the toolbar so the weight of the toolbar keys actually does something
            // todo: results in requestLayout() improperly called by android.widget.LinearLayout during layout: running second layout pass
            toolbar.minimumWidth = toolbarContainer.width
        }

        updateKeys()
    }

    private lateinit var listener: Listener
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)

    init {
        kitchenReady = true
    }
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    fun setToolbarVisibility(toolbarVisible: Boolean) {
        // The menu panel replaces the keyboard; don't slide the old toolbar over the strip while it is open.
        if (toolbarPanelOpen && toolbarVisible) return
        toolbarContainer.isVisible = toolbarVisible
        if (toolbarVisible) {
            pinnedKeys.isVisible = false
            suggestionsStrip.isVisible = false
        } else updateStripSlots()

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = suggestionsStrip.visibility
            }
        }
        updateKitchenVisibility()
    }

    /** Menu icon becomes a close icon while the toolbar panel replaces the keyboard. */
    fun setToolbarPanelOpen(open: Boolean) {
        if (toolbarPanelOpen == open) return
        toolbarPanelOpen = open
        if (open) {
            dismissMoreSuggestionsPanel()
            if (Settings.getValues().mToolbarMode != ToolbarMode.TOOLBAR_KEYS && toolbarContainer.isVisible)
                setToolbarVisibility(false)
        }
        updateMenuButton()
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        clear()
        layoutHelper.setActiveSuggestionSlots(if (kitchenSticker != null) 2 else 3)
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false
        updateKeys()
    }

    /** Sticker in the first strip slot. The other two slots stay suggestions or pinned keys. */
    fun setKitchenSticker(sticker: KitchenSticker?, emojiText: String) {
        val same = sticker != null && sticker.cacheFileName == kitchenSticker?.cacheFileName
        if (sticker == null && kitchenSticker == null) {
            updateKitchenVisibility()
            return
        }
        kitchenSticker = sticker
        kitchenEmojiText = if (sticker == null) "" else emojiText
        if (kitchenReady) layoutHelper.setActiveSuggestionSlots(if (sticker != null) 2 else 3)
        if (!same) {
            kitchenGeneration++
            if (sticker == null) {
                kitchenImage.setImageDrawable(null)
            } else {
                kitchenImage.setImageDrawable(kitchenPlaceholder)
                val generation = kitchenGeneration
                val height = 36.dpToPx(resources).coerceAtLeast(1)
                KitchenImageCache.loadThumbnail(context, sticker, height) { bitmap ->
                    if (generation != kitchenGeneration) return@loadThumbnail
                    if (bitmap != null) kitchenImage.setImageBitmap(bitmap)
                }
            }
        }
        updateKitchenVisibility()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }

        if (Settings.getValues().mAutoHideToolbar) setToolbarVisibility(false)
        updateKitchenVisibility()
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_PINNED_TOOLBAR_KEYS)
            refreshPinnedKeys()
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            suggestionsStrip.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            onClickToolbarKey(view) { listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false) }
            return
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (view === toolbarExpandKey) {
            KeyboardSwitcher.getInstance().toggleToolbarPanel()
            return
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size()) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view === toolbarExpandKey) return true
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        if (!Settings.getValues().mQuickPinToolbarKeys || view.parent === pinnedKeys) {
            onLongClickToolbarKey(view) { code, isRepeat -> listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat) }
        } else if (view.parent === toolbar) {
            // The pinned-keys pref listener refreshes the strip. Don't also edit the views here.
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
            if (pinnedKeys.findViewWithTag<View>(tag) == null) addPinnedKey(context.prefs(), tag)
            else removePinnedKey(context.prefs(), tag)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // no need for View#performClick, we only return false mostly anyway
    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        var showIcon = true
        if (wordView.tag is Int) {
            val index = wordView.tag as Int
            val type = suggestedWords.getInfo(index).mSourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        if (showIcon) {
            val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)!!
            Settings.getValues().mColors.setColor(icon, ColorType.REMOVE_SUGGESTION_ICON)
            val w = icon.intrinsicWidth
            val h = icon.intrinsicHeight
            wordView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            wordView.ellipsize = TextUtils.TruncateAt.END
            val downOk = AtomicBoolean(false)
            wordView.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP && downOk.get()) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        removeSuggestion(wordView)
                        wordView.cancelLongPress()
                        wordView.isPressed = false
                        return@setOnTouchListener true
                    }
                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        downOk.set(true)
                    }
                }
                false
            }
        }
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        return showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (toolbarPanelOpen || suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        var text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (index == 1 && suggestedWords.typedWordInfo.mSourceDict.mDictType != Dictionary.TYPE_USER_TYPED) {
            val dict = suggestedWords.typedWordInfo.mSourceDict
            text += "\n${suggestedWords.typedWordInfo.word}: ${dict.mDictType}:${dict.mLocale}"
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(wordView: TextView) {
        val word = wordView.text.toString()
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info)
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }

        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            suggestedWords.mWillAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (!toolbarContainer.isVisible)
            suggestionsStrip.isVisible = true
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        // TODO: TEKLADO voice input — visibility is isToolbarVoiceKeyVisible(), not "Google voice IME present".
        val show = isToolbarVoiceKeyVisible()
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
    }

    private fun updateKeys() {
        updateVoiceKey()
        updateMenuButton()
        updateStripSlots()
        isExternalSuggestionVisible = false
    }

    /**
     * The strip beside the menu key has one set of slots: suggestions while the user is typing a word,
     * otherwise the pinned keys (clipboard, voice, undo by default).
     */
    private fun updateStripSlots() {
        if (toolbarContainer.isVisible || isExternalSuggestionVisible) {
            updateKitchenVisibility()
            return
        }
        val words = suggestedWords
        val typing = !words.isEmpty && !words.isPunctuationSuggestions
            && words.mInputStyle != SuggestedWords.INPUT_STYLE_PREDICTION
            && words.mInputStyle != SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION
        suggestionsStrip.isVisible = typing
        pinnedKeys.isVisible = !typing
        updateKitchenVisibility()
    }

    private fun updateKitchenVisibility() {
        val show = kitchenSticker != null && !toolbarContainer.isVisible
        val wasShown = kitchenSlot.isVisible
        kitchenSlot.isVisible = show
        val slotWeight = if (show) 1f else 0f
        val slotParams = kitchenSlot.layoutParams as LinearLayout.LayoutParams
        if (slotParams.width != 0 || slotParams.weight != slotWeight) {
            slotParams.width = 0
            slotParams.weight = slotWeight
            kitchenSlot.layoutParams = slotParams
        }
        val contentWeight = if (show) 2f else 1f
        val suggestionParams = suggestionsStrip.layoutParams as LinearLayout.LayoutParams
        val pinnedParams = pinnedKeys.layoutParams as LinearLayout.LayoutParams
        if (suggestionParams.weight != contentWeight || pinnedParams.weight != contentWeight) {
            suggestionParams.weight = contentWeight
            pinnedParams.weight = contentWeight
            suggestionsStrip.layoutParams = suggestionParams
            pinnedKeys.layoutParams = pinnedParams
        }
        // Word widths are computed from the strip width, which changes on the next layout pass.
        if (kitchenReady && wasShown != show && suggestionsStrip.isVisible) {
            suggestionsStrip.doOnNextLayout { relayoutOpenSuggestions() }
        }
    }

    private fun relayoutOpenSuggestions() {
        if (!kitchenReady || isExternalSuggestionVisible || toolbarContainer.isVisible || !suggestionsStrip.isVisible) return
        if (suggestedWords.isEmpty || suggestedWords.isPunctuationSuggestions) return
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        dismissMoreSuggestionsPanel()
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
    }

    private fun onKitchenStickerClick() {
        val sticker = kitchenSticker ?: return
        if (!::listener.isInitialized) return
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS
        )
        listener.onKitchenStickerPicked(sticker, kitchenEmojiText)
    }

    private fun updateMenuButton() {
        val showMenu = Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN
        toolbarExpandKey.scaleX = 1f
        toolbarExpandKey.setImageDrawable(if (toolbarPanelOpen) toolbarCloseIcon else toolbarMenuIcon)
        toolbarExpandKey.contentDescription = resources.getString(
            if (toolbarPanelOpen) R.string.dialog_close else R.string.toolbar_menu
        )
        toolbarExpandKey.isVisible = showMenu
        toolbarExpandKey.setOnClickListener(if (showMenu) this else null)
        Settings.getValues().mColors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
    }

    private fun refreshPinnedKeys() {
        pinnedKeys.removeAllViews()
        val colors = Settings.getValues().mColors
        val pinned = getPinnedToolbarKeys(context.prefs())
        val quickPin = Settings.getValues().mQuickPinToolbarKeys
        // Only the quick-pin radial highlight is swapped. The menu key's circle is not a toolbar-key background.
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            val key = child.tag as? ToolbarKey
            val shouldHighlight = quickPin && key != null && key in pinned
            val highlighted = isQuickPinHighlight(child.background)
            if (shouldHighlight && !highlighted) {
                enabledToolKeyBackground.constantState?.newDrawable(resources)?.mutate()?.let { child.background = it }
            } else if (!shouldHighlight && highlighted) {
                defaultToolbarBackground.constantState?.newDrawable(resources)?.let { child.background = it }
            }
        }
        for (pinnedKey in pinned) {
            val button = createToolbarKey(context, pinnedKey)
            button.layoutParams = LinearLayout.LayoutParams(pinnedKeyLayoutParams)
            setupKey(button, colors)
            pinnedKeys.addView(button)
        }
        updateVoiceKey()
    }

    private fun isQuickPinHighlight(background: Drawable?) =
        background is GradientDrawable && background.gradientType == GradientDrawable.RADIAL_GRADIENT

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        (view.layoutParams as LinearLayout.LayoutParams).weight = 1f
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}
