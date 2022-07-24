/***************************************************************************************
 * Copyright (c) 2009 Nicolas Raoul <nicolas.raoul@gmail.com>                           *
 * Copyright (c) 2009 Edu Zamora <edu.zasu@gmail.com>                                   *
 * Copyright (c) 2010 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 * Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>                          *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/
package com.ichi2.anki

import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import android.webkit.URLUtil
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.annotation.XmlRes
import androidx.appcompat.app.ActionBar
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.*
import com.afollestad.materialdialogs.MaterialDialog
import com.ichi2.anim.ActivityTransitionAnimation
import com.ichi2.anki.UIUtils.showSimpleSnackbar
import com.ichi2.anki.UIUtils.showThemedToast
import com.ichi2.anki.analytics.UsageAnalytics
import com.ichi2.anki.cardviewer.GestureProcessor
import com.ichi2.anki.contextmenu.AnkiCardContextMenu
import com.ichi2.anki.contextmenu.CardBrowserContextMenu
import com.ichi2.anki.exception.ConfirmModSchemaException
import com.ichi2.anki.exception.StorageAccessException
import com.ichi2.anki.preferences.AboutFragment
import com.ichi2.anki.preferences.HeaderFragment
import com.ichi2.anki.preferences.setOnPreferenceChangeListener
import com.ichi2.anki.provider.CardContentProvider
import com.ichi2.anki.reviewer.AutomaticAnswerAction
import com.ichi2.anki.reviewer.FullScreenMode
import com.ichi2.anki.services.BootService.Companion.scheduleNotification
import com.ichi2.anki.web.CustomSyncServer
import com.ichi2.anki.web.CustomSyncServer.handleSyncServerPreferenceChange
import com.ichi2.compat.CompatHelper
import com.ichi2.libanki.Collection
import com.ichi2.libanki.Utils
import com.ichi2.libanki.backend.exception.BackendNotSupportedException
import com.ichi2.libanki.utils.TimeManager
import com.ichi2.preferences.*
import com.ichi2.preferences.ControlPreference.Companion.addAllControlPreferencesToCategory
import com.ichi2.themes.Theme
import com.ichi2.themes.Themes
import com.ichi2.themes.Themes.currentTheme
import com.ichi2.themes.Themes.setThemeLegacy
import com.ichi2.themes.Themes.systemIsInNightMode
import com.ichi2.themes.Themes.updateCurrentTheme
import com.ichi2.utils.AdaptionUtil.isRestrictedLearningDevice
import com.ichi2.utils.LanguageUtil
import net.ankiweb.rsdroid.BackendFactory
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.HashSet

/**
 * Preferences dialog.
 */
class Preferences : AnkiActivity() {
    /** The collection path when Preferences was opened   */
    private var mOldCollectionPath: String? = null

    private val mOnBackStackChangedListener: FragmentManager.OnBackStackChangedListener = FragmentManager.OnBackStackChangedListener {
        updateActionBarTitle(supportFragmentManager, supportActionBar)
    }
    // ----------------------------------------------------------------------------
    // Overridden methods
    // ----------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.preferences)
        setThemeLegacy(this)

        val actionBar = enableToolbar().apply {
            setHomeButtonEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }

        // onRestoreInstanceState takes priority, this is only set on init.
        mOldCollectionPath = CollectionHelper.getCollectionPath(this)

        // Load initial fragment if activity is being first created.
        // If activity is being recreated (i.e. savedInstanceState != null),
        // which could happen on configuration changes as screen rotation and theme changes,
        // don't replace the previous opened fragments
        if (savedInstanceState == null) {
            loadInitialFragment()
        }
        updateActionBarTitle(supportFragmentManager, actionBar)
        supportFragmentManager.addOnBackStackChangedListener(mOnBackStackChangedListener)
    }

    /**
     * Starts the first fragment for the [Preferences] activity,
     * which by default is [HeaderFragment].
     * The initial fragment may be overridden by putting the java class name
     * of the fragment on an intent extra with the key [INITIAL_FRAGMENT_EXTRA]
     */
    private fun loadInitialFragment() {
        val fragmentClassName = intent?.getStringExtra(INITIAL_FRAGMENT_EXTRA)
        val initialFragment = if (fragmentClassName == null) {
            HeaderFragment()
        } else {
            try {
                Class.forName(fragmentClassName).newInstance() as Fragment
            } catch (e: Exception) {
                throw RuntimeException("Failed to load $fragmentClassName", e)
            }
        }
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, initialFragment, initialFragment::class.java.name)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        supportFragmentManager.removeOnBackStackChangedListener(mOnBackStackChangedListener)
    }

    private fun updateActionBarTitle(fragmentManager: FragmentManager, actionBar: ActionBar?) {
        val fragment = fragmentManager.findFragmentById(R.id.settings_container)

        actionBar?.title = when (fragment) {
            is SettingsFragment -> fragment.preferenceScreen.title
            is AboutFragment -> getString(R.string.pref_cat_about_title)
            else -> getString(R.string.settings)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return false
    }

    override fun onBackPressed() {
        // If the collection path has changed, we want to move back to the deck picker immediately
        // This performs the move when back is pressed on the "Advanced" screen
        if (!Utils.equals(CollectionHelper.getCollectionPath(this), mOldCollectionPath)) {
            restartWithNewDeckPicker()
        } else {
            super.onBackPressed()
        }
    }

    fun restartWithNewDeckPicker() {
        // PERF: DB access on foreground thread
        val helper = CollectionHelper.getInstance()
        helper.closeCollection(true, "Preference Modification: collection path changed")
        helper.discardBackend()
        val deckPicker = Intent(this, DeckPicker::class.java)
        deckPicker.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityWithAnimation(deckPicker, ActivityTransitionAnimation.Direction.DEFAULT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mOldCollectionPath", mOldCollectionPath)
    }

    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state)
        mOldCollectionPath = state.getString("mOldCollectionPath")
    }

    // ----------------------------------------------------------------------------
    // Class methods
    // ----------------------------------------------------------------------------

    /** Sets the hour that the collection rolls over to the next day  */
    @VisibleForTesting
    fun setDayOffset(hours: Int) {
        when (getSchedVer(col)) {
            2 -> {
                col.set_config("rollover", hours)
                col.flush()
            }
            else -> { // typically "1"
                val date: Calendar = col.crtGregorianCalendar()
                date[Calendar.HOUR_OF_DAY] = hours
                col.crt = date.timeInMillis / 1000
                col.setMod()
            }
        }
        scheduleNotification(TimeManager.time, this)
    }

    private fun closePreferences() {
        finishWithAnimation(ActivityTransitionAnimation.Direction.FADE)
        if (col != null && !col.dbClosed) {
            col.save()
        }
    }

    // ----------------------------------------------------------------------------
    // Inner classes
    // ----------------------------------------------------------------------------

    abstract class SettingsFragment : PreferenceFragmentCompat() {
        /** @return The XML file which defines the preferences displayed by this PreferenceFragment
         */
        @get:XmlRes
        abstract val preferenceResource: Int

        /**
         * Refreshes all values on the screen
         * Call if a large number of values are changed from one preference.
         */
        protected fun refreshScreen() {
            preferenceScreen.removeAll()
            addPreferencesFromResource(preferenceResource)
            initSubscreen()
        }

        protected val col: Collection?
            get() = CollectionHelper.getInstance().getCol(requireContext())

        abstract fun initSubscreen()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            UsageAnalytics.sendAnalyticsScreenView(analyticsScreenNameConstant)
            addPreferencesFromResource(preferenceResource)
            initSubscreen()
        }

        /** Obtains a non-null reference to the preference defined by the key, or throws  */
        @Suppress("UNCHECKED_CAST")
        protected fun <T : Preference?> requirePreference(key: String): T {
            val preference = findPreference<Preference>(key)
                ?: throw IllegalStateException("missing preference: '$key'")
            return preference as T
        }

        @Suppress("UNCHECKED_CAST")
        /**
         * Obtains a non-null reference to the preference whose
         * key is defined with given [resId] or throws
         * e.g. `requirePreference(R.string.day_theme_key)` returns
         * the preference whose key is `@string/day_theme_key`
         * The resource IDs with preferences keys can be found on `res/values/preferences.xml`
         */
        protected fun <T : Preference?> requirePreference(@StringRes resId: Int): T {
            return requirePreference(getString(resId)) as T
        }

        protected abstract val analyticsScreenNameConstant: String

        @Suppress("deprecation") // setTargetFragment
        override fun onDisplayPreferenceDialog(preference: Preference) {
            val dialogFragment = when (preference) {
                is IncrementerNumberRangePreferenceCompat -> IncrementerNumberRangePreferenceCompat.IncrementerNumberRangeDialogFragmentCompat.newInstance(preference.getKey())
                is NumberRangePreferenceCompat -> NumberRangePreferenceCompat.NumberRangeDialogFragmentCompat.newInstance(preference.getKey())
                is SeekBarPreferenceCompat -> SeekBarPreferenceCompat.SeekBarDialogFragmentCompat.newInstance(preference.getKey())
                else -> null
            }

            if (dialogFragment != null) {
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }

        companion object {
            @JvmStatic
            protected fun getSubscreenIntent(context: Context?, javaClassName: String): Intent {
                return Intent(context, Preferences::class.java)
                    .putExtra(INITIAL_FRAGMENT_EXTRA, javaClassName)
            }
        }
    }

    class GeneralSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_general
        override val analyticsScreenNameConstant: String
            get() = "prefs.general"

        override fun initSubscreen() {
            val col = col!!
            if (isRestrictedLearningDevice) {
                val switchPrefVibrate = requirePreference<SwitchPreference>("widgetVibrate")
                val switchPrefBlink = requirePreference<SwitchPreference>("widgetBlink")
                val category = requirePreference<PreferenceCategory>("category_general_notification_pref")
                category.removePreference(switchPrefVibrate)
                category.removePreference(switchPrefBlink)
            }
            // Build languages
            initializeLanguageDialog()

            // Deck for new cards
            // Represents in the collections pref "addToCur": i.e.
            // if true, then add note to current decks, otherwise let the note type's configuration decide
            // Note that "addToCur" is a boolean while USE_CURRENT is "0" or "1"
            requirePreference<ListPreference>(R.string.deck_for_new_cards_key).apply {
                setValueIndex(if (col.get_config("addToCur", true)!!) 0 else 1)
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("addToCur", "0" == newValue)
                }
            }
            // Paste PNG
            // Represents in the collection's pref "pastePNG" , i.e.
            // whether to convert clipboard uri to png format or not.
            requirePreference<SwitchPreference>(R.string.paste_png_key).apply {
                isChecked = col.get_config("pastePNG", false)!!
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("pastePNG", newValue)
                }
            }
            // Error reporting mode
            requirePreference<ListPreference>(R.string.error_reporting_mode_key).setOnPreferenceChangeListener { newValue ->
                CrashReportService.onPreferenceChanged(requireContext(), newValue as String)
            }
        }

        private fun initializeLanguageDialog() {
            val languageSelection = requirePreference<ListPreference>(R.string.pref_language_key)

            val items: MutableMap<String, String> = TreeMap(java.lang.String.CASE_INSENSITIVE_ORDER)
            for (localeCode in LanguageUtil.APP_LANGUAGES) {
                val loc = LanguageUtil.getLocale(localeCode)
                items[loc.getDisplayName(loc)] = loc.toString()
            }
            val languageDialogLabels = arrayOfNulls<CharSequence>(items.size + 1)
            val languageDialogValues = arrayOfNulls<CharSequence>(items.size + 1)
            languageDialogLabels[0] = resources.getString(R.string.language_system)
            languageDialogValues[0] = ""
            val itemsList = items.toList()
            for (i in 1..itemsList.size) {
                languageDialogLabels[i] = itemsList[i - 1].first
                languageDialogValues[i] = itemsList[i - 1].second
            }

            languageSelection.entries = languageDialogLabels
            languageSelection.entryValues = languageDialogValues

            // It's only possible to change the language by recreating the activity,
            // so do it if the language has changed.
            // TODO recreate the activity and keep its previous state instead of just closing it
            languageSelection.setOnPreferenceChangeListener { _, newValue ->
                LanguageUtil.setDefaultBackendLanguages(newValue as String)
                val helper = CollectionHelper.getInstance()
                helper.closeCollection(true, "language change")
                helper.discardBackend()
                (requireActivity() as Preferences).closePreferences()
                true
            }
        }
    }

    class ReviewingSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_reviewing
        override val analyticsScreenNameConstant: String
            get() = "prefs.reviewing"

        override fun initSubscreen() {
            val col = col!!

            // New cards position
            // Represents the collections pref "newSpread": i.e.
            // whether the new cards are added at the end of the queue or randomly in it.
            requirePreference<ListPreference>(R.string.new_spread_preference).apply {
                setValueIndex(col.get_config_int("newSpread"))
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("newSpread", ((newValue as String).toInt()))
                }
            }

            // Learn ahead limit
            // Represents the collections pref "collapseTime": i.e.
            // if there are no card to review now, but there are learning cards remaining for today, we show those learning cards if they are due before LEARN_CUTOFF minutes
            // Note that "collapseTime" is in second while LEARN_CUTOFF is in minute.
            requirePreference<NumberRangePreferenceCompat>(R.string.learn_cutoff_preference).apply {
                setValue(col.get_config_int("collapseTime") / 60)
                setFormattedSummary(R.string.pref_summary_minutes)
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("collapseTime", ((newValue as String).toInt() * 60))
                }
            }
            // Timebox time limit
            // Represents in Android preferences the collections configuration "timeLim": i.e.
            // the duration of a review timebox in minute. Each TIME_LIMIT minutes, a message appear suggesting to halt and giving the number of card reviewed
            // Note that "timeLim" is in seconds while TIME_LIMIT is in minutes.
            requirePreference<NumberRangePreferenceCompat>(R.string.time_limit_preference).apply {
                setValue(col.get_config_int("timeLim") / 60)
                setFormattedSummary(R.string.pref_summary_minutes)
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("timeLim", ((newValue as String).toInt() * 60))
                }
            }
            // Start of next day
            // Represents the collection pref "rollover"
            // in sched v2, and crt in sched v1. I.e. at which time of the day does the scheduler reset
            requirePreference<SeekBarPreferenceCompat>(R.string.day_offset_preference).apply {
                value = getDayOffset(col)
                setFormattedSummary(R.string.day_offset_summary)
                setOnPreferenceChangeListener { newValue ->
                    (requireActivity() as Preferences).setDayOffset(newValue as Int)
                }
            }
            // Automatic display answer
            requirePreference<SwitchPreference>(R.string.timeout_answer_preference).setOnPreferenceChangeListener { newValue ->
                // Enable `Keep screen on` along with the automatic display answer preference
                if (newValue == true) {
                    requirePreference<SwitchPreference>(R.string.keep_screen_on_preference).isChecked = true
                }
            }

            /**
             * Timeout answer
             * An integer representing the action when "Automatic Answer" flips a card from answer to question
             * 0 represents "bury", 1-4 represents the named buttons
             * @see com.ichi2.anki.reviewer.AutomaticAnswerAction
             * We use the same key in the collection config
             * @see com.ichi2.anki.reviewer.AutomaticAnswerAction.CONFIG_KEY
             * */
            requirePreference<ListPreference>(R.string.automatic_answer_action_preference).apply {
                setValueIndex(col.get_config(AutomaticAnswerAction.CONFIG_KEY, 0.toInt())!!)
                setOnPreferenceChangeListener { newValue ->
                    col.set_config(AutomaticAnswerAction.CONFIG_KEY, (newValue as String).toInt())
                }
            }
            // Time to show answer
            requirePreference<SeekBarPreferenceCompat>(R.string.timeout_answer_seconds_preference)
                .setFormattedSummary(R.string.pref_summary_seconds)
            // Time to show question
            requirePreference<SeekBarPreferenceCompat>(R.string.timeout_question_seconds_preference)
                .setFormattedSummary(R.string.pref_summary_seconds)

            // New timezone handling
            requirePreference<SwitchPreference>(R.string.new_timezone_handling_preference).apply {
                isChecked = col.sched._new_timezone_enabled()
                isEnabled = col.schedVer() > 1
                setOnPreferenceChangeListener { newValue ->
                    if (newValue == true) {
                        try {
                            col.sched.set_creation_offset()
                        } catch (e: BackendNotSupportedException) {
                            throw e.alreadyUsingRustBackend()
                        }
                    } else {
                        col.sched.clear_creation_offset()
                    }
                }
            }
        }
    }

    /**
     * Fragment with preferences related to syncing
     */
    class SyncSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_sync
        override val analyticsScreenNameConstant: String
            get() = "prefs.sync"

        override fun initSubscreen() {
            // AnkiWeb Account
            updateSyncAccountSummary()

            // Configure force full sync option
            requirePreference<Preference>(R.string.force_full_sync_key).setOnPreferenceClickListener {
                MaterialDialog(requireContext()).show {
                    title(R.string.force_full_sync_title)
                    message(R.string.force_full_sync_summary)
                    positiveButton(R.string.dialog_ok) {
                        if (col == null) {
                            showThemedToast(
                                requireContext(),
                                R.string.directory_inaccessible,
                                false
                            )
                            return@positiveButton
                        }
                        col!!.modSchemaNoCheck()
                        showThemedToast(
                            requireContext(),
                            R.string.force_full_sync_confirmation,
                            true
                        )
                    }
                    negativeButton(R.string.dialog_cancel)
                }
                true
            }
            // Custom sync server
            requirePreference<Preference>(R.string.custom_sync_server_key).setSummaryProvider {
                val preferences = AnkiDroidApp.getSharedPrefs(requireContext())
                if (!CustomSyncServer.isEnabled(preferences)) {
                    getString(R.string.disabled)
                } else {
                    CustomSyncServer.getSyncBaseUrlOrDefault(preferences, "")
                }
            }
        }

        private fun updateSyncAccountSummary() {
            requirePreference<Preference>(R.string.sync_account_key)
                .summary = preferenceManager.sharedPreferences!!.getString("username", "")!!
                .ifEmpty { getString(R.string.sync_account_summ_logged_out) }
        }

        // TODO trigger the summary change from MyAccount.kt once it is migrated to a fragment
        override fun onResume() {
            // Trigger a summary update in case the user logged in/out on MyAccount activity
            updateSyncAccountSummary()
            super.onResume()
        }
    }

    class AppearanceSettingsFragment : SettingsFragment() {
        private var mBackgroundImage: SwitchPreference? = null
        override val preferenceResource: Int
            get() = R.xml.preferences_appearance
        override val analyticsScreenNameConstant: String
            get() = "prefs.appearance"

        override fun initSubscreen() {
            val col = col!!
            // Card browser font scaling
            requirePreference<SeekBarPreferenceCompat>(R.string.pref_card_browser_font_scale_key)
                .setFormattedSummary(R.string.pref_summary_percentage)

            // Show error toast if the user tries to disable answer button without gestures on
            requirePreference<Preference>(R.string.answer_buttons_position_preference).setOnPreferenceChangeListener() { _, newValue: Any ->
                val prefs = AnkiDroidApp.getSharedPrefs(requireContext())
                if (prefs.getBoolean(GestureProcessor.PREF_KEY, false) || newValue != "none") {
                    true
                } else {
                    showThemedToast(requireContext(), R.string.full_screen_error_gestures, false)
                    false
                }
            }
            requirePreference<ListPreference>(FullScreenMode.PREF_KEY).setOnPreferenceChangeListener { _, newValue: Any ->
                val prefs = AnkiDroidApp.getSharedPrefs(requireContext())
                if (prefs.getBoolean(GestureProcessor.PREF_KEY, false) || FullScreenMode.FULLSCREEN_ALL_GONE.getPreferenceValue() != newValue) {
                    true
                } else {
                    showThemedToast(requireContext(), R.string.full_screen_error_gestures, false)
                    false
                }
            }
            // Configure background
            mBackgroundImage = requirePreference<SwitchPreference>("deckPickerBackground")
            mBackgroundImage!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (mBackgroundImage!!.isChecked) {
                    try {
                        mBackgroundImageResultLauncher.launch("image/*")
                        mBackgroundImage!!.isChecked = true
                    } catch (ex: Exception) {
                        Timber.e("%s", ex.localizedMessage)
                    }
                } else {
                    mBackgroundImage!!.isChecked = false
                    val currentAnkiDroidDirectory = CollectionHelper.getCurrentAnkiDroidDirectory(requireContext())
                    val imgFile = File(currentAnkiDroidDirectory, "DeckPickerBackground.png")
                    if (imgFile.exists()) {
                        if (imgFile.delete()) {
                            showThemedToast(requireContext(), getString(R.string.background_image_removed), false)
                        } else {
                            showThemedToast(requireContext(), getString(R.string.error_deleting_image), false)
                        }
                    } else {
                        showThemedToast(requireContext(), getString(R.string.background_image_removed), false)
                    }
                }
                true
            }

            val appThemePref = requirePreference<ListPreference>(getString(R.string.app_theme_key))
            val dayThemePref = requirePreference<ListPreference>(getString(R.string.day_theme_key))
            val nightThemePref = requirePreference<ListPreference>(getString(R.string.night_theme_key))
            val themeIsFollowSystem = appThemePref.value == Themes.FOLLOW_SYSTEM_MODE

            // Remove follow system options in android versions which do not have system dark mode
            // When minSdk reaches 29, the only necessary change is to remove this if-block
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                dayThemePref.isVisible = false
                nightThemePref.isVisible = false

                // Drop "Follow system" option (the first one)
                appThemePref.entries = resources.getStringArray(R.array.app_theme_labels).drop(1).toTypedArray()
                appThemePref.entryValues = resources.getStringArray(R.array.app_theme_values).drop(1).toTypedArray()
                if (themeIsFollowSystem) {
                    appThemePref.value = Theme.fallback.id
                }
            }
            dayThemePref.isEnabled = themeIsFollowSystem
            nightThemePref.isEnabled = themeIsFollowSystem

            appThemePref.setOnPreferenceChangeListener { newValue ->
                val selectedThemeIsFollowSystem = newValue == Themes.FOLLOW_SYSTEM_MODE
                dayThemePref.isEnabled = selectedThemeIsFollowSystem
                nightThemePref.isEnabled = selectedThemeIsFollowSystem

                // Only restart if theme has changed
                if (newValue != appThemePref.value) {
                    val previousThemeId = currentTheme.id
                    appThemePref.value = newValue.toString()
                    updateCurrentTheme()

                    if (previousThemeId != currentTheme.id) {
                        requireActivity().recreate()
                    }
                }
            }

            dayThemePref.setOnPreferenceChangeListener { newValue ->
                if (newValue != dayThemePref.value && !systemIsInNightMode && newValue != currentTheme.id) {
                    dayThemePref.value = newValue.toString()
                    updateCurrentTheme()
                    requireActivity().recreate()
                }
            }

            nightThemePref.setOnPreferenceChangeListener { newValue ->
                if (newValue != nightThemePref.value && systemIsInNightMode && newValue != currentTheme.id) {
                    nightThemePref.value = newValue.toString()
                    updateCurrentTheme()
                    requireActivity().recreate()
                }
            }
            initializeCustomFontsDialog()

            // Show estimate time
            // Represents the collection pref "estTime": i.e.
            // whether the buttons should indicate the duration of the interval if we click on them.
            requirePreference<SwitchPreference>(R.string.show_estimates_preference).apply {
                isChecked = col.get_config_boolean("estTimes")
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("estTimes", newValue)
                }
            }
            // Show progress
            // Represents the collection pref "dueCounts": i.e.
            // whether the remaining number of cards should be shown.
            requirePreference<SwitchPreference>(R.string.show_progress_preference).apply {
                isChecked = col.get_config_boolean("dueCounts")
                setOnPreferenceChangeListener { newValue ->
                    col.set_config("dueCounts", newValue)
                }
            }
        }

        /** Initializes the list of custom fonts shown in the preferences.  */
        private fun initializeCustomFontsDialog() {
            val defaultFontPreference = requirePreference<ListPreference>("defaultFont")
            defaultFontPreference.entries = getCustomFonts("System default")
            defaultFontPreference.entryValues = getCustomFonts("")
            val browserEditorCustomFontsPreference = requirePreference<ListPreference>("browserEditorFont")
            browserEditorCustomFontsPreference.entries = getCustomFonts("System default")
            browserEditorCustomFontsPreference.entryValues = getCustomFonts("", true)
        }

        /** Returns a list of the names of the installed custom fonts.  */
        private fun getCustomFonts(defaultValue: String): Array<String?> {
            return getCustomFonts(defaultValue, false)
        }

        private fun getCustomFonts(defaultValue: String, useFullPath: Boolean): Array<String?> {
            val fonts = Utils.getCustomFonts(requireContext())
            val count = fonts.size
            Timber.d("There are %d custom fonts", count)
            val names = arrayOfNulls<String>(count + 1)
            names[0] = defaultValue
            if (useFullPath) {
                for (index in 1 until count + 1) {
                    names[index] = fonts[index - 1].path
                    Timber.d("Adding custom font: %s", names[index])
                }
            } else {
                for (index in 1 until count + 1) {
                    names[index] = fonts[index - 1].name
                    Timber.d("Adding custom font: %s", names[index])
                }
            }
            return names
        }

        private val mBackgroundImageResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { selectedImage ->
            if (selectedImage != null) {
                // handling file may result in exception
                try {
                    val filePathColumn = arrayOf(MediaStore.MediaColumns.SIZE)
                    requireContext().contentResolver.query(selectedImage, filePathColumn, null, null, null).use { cursor ->
                        cursor!!.moveToFirst()
                        // file size in MB
                        val fileLength = cursor.getLong(0) / (1024 * 1024)
                        val currentAnkiDroidDirectory = CollectionHelper.getCurrentAnkiDroidDirectory(requireContext())
                        val imageName = "DeckPickerBackground.png"
                        val destFile = File(currentAnkiDroidDirectory, imageName)
                        // Image size less than 10 MB copied to AnkiDroid directory
                        if (fileLength < 10) {
                            (requireContext().contentResolver.openInputStream(selectedImage) as FileInputStream?)!!.channel.use { sourceChannel ->
                                FileOutputStream(destFile).channel.use { destChannel ->
                                    destChannel.transferFrom(sourceChannel, 0, sourceChannel.size())
                                    showThemedToast(requireContext(), getString(R.string.background_image_applied), false)
                                }
                            }
                        } else {
                            mBackgroundImage!!.isChecked = false
                            showThemedToast(requireContext(), getString(R.string.image_max_size_allowed, 10), false)
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    Timber.w(e)
                    showThemedToast(requireContext(), getString(R.string.error_selecting_image, e.localizedMessage), false)
                } catch (e: Exception) {
                    Timber.w(e)
                    showThemedToast(requireContext(), getString(R.string.error_selecting_image, e.localizedMessage), false)
                }
            } else {
                mBackgroundImage!!.isChecked = false
                showThemedToast(requireContext(), getString(R.string.no_image_selected), false)
            }
        }
    }

    class AdvancedSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_advanced
        override val analyticsScreenNameConstant: String
            get() = "prefs.advanced"

        @Suppress("Deprecation") // Material dialog neutral button deprecation
        override fun initSubscreen() {
            val screen = preferenceScreen
            // Check that input is valid before committing change in the collection path
            requirePreference<EditTextPreference>(CollectionHelper.PREF_COLLECTION_PATH).apply {
                setOnPreferenceChangeListener { _, newValue: Any? ->
                    val newPath = newValue as String?
                    try {
                        CollectionHelper.initializeAnkiDroidDirectory(newPath)
                        true
                    } catch (e: StorageAccessException) {
                        Timber.e(e, "Could not initialize directory: %s", newPath)
                        MaterialDialog(requireContext()).show {
                            title(R.string.dialog_collection_path_not_dir)
                            positiveButton(R.string.dialog_ok) {
                                dismiss()
                            }
                            negativeButton(R.string.reset_custom_buttons) {
                                text = CollectionHelper.getDefaultAnkiDroidDirectory(requireContext())
                            }
                        }
                        false
                    }
                }
            }
            // Card browser context menu
            requirePreference<SwitchPreference>(R.string.card_browser_external_context_menu_key).apply {
                title = getString(R.string.card_browser_enable_external_context_menu, getString(R.string.card_browser_context_menu))
                summary = getString(R.string.card_browser_enable_external_context_menu_summary, getString(R.string.card_browser_context_menu))
                setOnPreferenceChangeListener { newValue ->
                    CardBrowserContextMenu.ensureConsistentStateWithPreferenceStatus(requireContext(), newValue as Boolean)
                }
            }
            // Anki card context menu
            requirePreference<SwitchPreference>(R.string.anki_card_external_context_menu_key).apply {
                title = getString(R.string.card_browser_enable_external_context_menu, getString(R.string.context_menu_anki_card_label))
                summary = getString(R.string.card_browser_enable_external_context_menu_summary, getString(R.string.context_menu_anki_card_label))
                setOnPreferenceChangeListener { newValue ->
                    AnkiCardContextMenu.ensureConsistentStateWithPreferenceStatus(requireContext(), newValue as Boolean)
                }
            }

            if (col != null && col!!.schedVer() == 1) {
                Timber.i("Displaying V1-to-V2 scheduler preference")
                val schedVerPreference = SwitchPreference(requireContext())
                schedVerPreference.setTitle(R.string.sched_v2)
                schedVerPreference.setSummary(R.string.sched_v2_summ)
                schedVerPreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                    MaterialDialog(requireContext()).show {
                        // Going to V2
                        title(R.string.sched_ver_toggle_title)
                            .message(R.string.sched_ver_1to2)
                            .positiveButton(R.string.dialog_ok) {
                                col!!.modSchemaNoCheck()
                                try {
                                    col!!.changeSchedulerVer(2)
                                    screen.removePreference(schedVerPreference)
                                } catch (e2: ConfirmModSchemaException) {
                                    // This should never be reached as we explicitly called modSchemaNoCheck()
                                    throw RuntimeException(e2)
                                }
                            }
                            .neutralButton(R.string.help) {
                                // call v2 scheduler documentation website
                                val uri = Uri.parse(getString(R.string.link_anki_2_scheduler))
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                startActivity(intent)
                            }
                            .negativeButton(R.string.dialog_cancel) {
                                schedVerPreference.isChecked = false
                            }
                    }
                    false
                }
                // meaning of order here is the position of Preference in xml layout.
                schedVerPreference.order = 5
                screen.addPreference(schedVerPreference)
            }
            // Adding change logs in both debug and release builds
            Timber.i("Adding open changelog")
            val changelogPreference = Preference(requireContext())
            changelogPreference.setTitle(R.string.open_changelog)
            val infoIntent = Intent(requireContext(), Info::class.java)
            infoIntent.putExtra(Info.TYPE_EXTRA, Info.TYPE_NEW_VERSION)
            changelogPreference.intent = infoIntent
            screen.addPreference(changelogPreference)
            // Workaround preferences
            removeUnnecessaryAdvancedPrefs()
            addThirdPartyAppsListener()

            // Configure "Reset languages" preference
            requirePreference<Preference>(R.string.pref_reset_languages_key).setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.reset_languages)
                    .setIcon(R.drawable.ic_warning_black)
                    .setMessage(R.string.reset_languages_question)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        if (MetaDB.resetLanguages(requireContext())) {
                            showThemedToast(requireContext(), R.string.reset_confirmation, true)
                        }
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
                true
            }
            // Advanced statistics
            requirePreference<Preference>(R.string.pref_advanced_statistics_key).setSummaryProvider {
                if (AnkiDroidApp.getSharedPrefs(requireContext()).getBoolean("advanced_statistics_enabled", false)) {
                    getString(R.string.enabled)
                } else {
                    getString(R.string.disabled)
                }
            }

            // Enable API
            requirePreference<SwitchPreference>(R.string.enable_api_key).setOnPreferenceChangeListener { newValue ->
                val providerName = ComponentName(requireContext(), CardContentProvider::class.java.name)
                val state = if (newValue == true) {
                    Timber.i("AnkiDroid ContentProvider enabled by user")
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    Timber.i("AnkiDroid ContentProvider disabled by user")
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                requireActivity().packageManager.setComponentEnabledSetting(providerName, state, PackageManager.DONT_KILL_APP)
            }
        }

        private fun removeUnnecessaryAdvancedPrefs() {
            val plugins = findPreference<PreferenceCategory>("category_plugins")
            // Disable the emoji/kana buttons to scroll preference if those keys don't exist
            if (!CompatHelper.hasKanaAndEmojiKeys()) {
                val emojiScrolling = findPreference<SwitchPreference>("scrolling_buttons")
                if (emojiScrolling != null && plugins != null) {
                    plugins.removePreference(emojiScrolling)
                }
            }
            // Disable the double scroll preference if no scrolling keys
            if (!CompatHelper.hasScrollKeys() && !CompatHelper.hasKanaAndEmojiKeys()) {
                val doubleScrolling = findPreference<SwitchPreference>("double_scrolling")
                if (doubleScrolling != null && plugins != null) {
                    plugins.removePreference(doubleScrolling)
                }
            }
        }

        private fun addThirdPartyAppsListener() {
            // #5864 - some people don't have a browser so we can't use <intent>
            // and need to handle the keypress ourself.
            val showThirdParty = requirePreference<Preference>("thirdpartyapps_link")
            val githubThirdPartyAppsUrl = "https://github.com/ankidroid/Anki-Android/wiki/Third-Party-Apps"
            showThirdParty.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    val openThirdPartyAppsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(githubThirdPartyAppsUrl))
                    super.startActivity(openThirdPartyAppsIntent)
                } catch (e: ActivityNotFoundException) {
                    Timber.w(e)
                    // We use a different message here. We have limited space in the snackbar
                    val error = getString(R.string.activity_start_failed_load_url, githubThirdPartyAppsUrl)
                    showSimpleSnackbar(requireActivity(), error, false)
                }
                true
            }
        }

        companion object {
            @JvmStatic
            fun getSubscreenIntent(context: Context?): Intent {
                return getSubscreenIntent(context, AdvancedSettingsFragment::class.java.name)
            }
        }
    }

    class CustomButtonsSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_custom_buttons
        override val analyticsScreenNameConstant: String
            get() = "prefs.custom_buttons"

        override fun initSubscreen() {
            // Reset toolbar button customizations
            val resetCustomButtons = requirePreference<Preference>("reset_custom_buttons")
            resetCustomButtons.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                AnkiDroidApp.getSharedPrefs(requireContext()).edit {
                    remove("customButtonUndo")
                    remove("customButtonScheduleCard")
                    remove("customButtonEditCard")
                    remove("customButtonTags")
                    remove("customButtonAddCard")
                    remove("customButtonReplay")
                    remove("customButtonCardInfo")
                    remove("customButtonSelectTts")
                    remove("customButtonDeckOptions")
                    remove("customButtonMarkCard")
                    remove("customButtonToggleMicToolBar")
                    remove("customButtonBury")
                    remove("customButtonSuspend")
                    remove("customButtonFlag")
                    remove("customButtonDelete")
                    remove("customButtonEnableWhiteboard")
                    remove("customButtonSaveWhiteboard")
                    remove("customButtonWhiteboardPenColor")
                    remove("customButtonClearWhiteboard")
                    remove("customButtonShowHideWhiteboard")
                }
                // #9263: refresh the screen to display the changes
                refreshScreen()
                true
            }
        }

        @VisibleForTesting(otherwise = VisibleForTesting.NONE)
        fun allKeys(): HashSet<String> {
            val allKeys = HashSet<String>()
            for (i in 0 until preferenceScreen.preferenceCount) {
                val pref = preferenceScreen.getPreference(i)
                if (pref is PreferenceCategory) {
                    for (j in 0 until pref.preferenceCount) {
                        allKeys.add(pref.getPreference(j).key)
                    }
                } else {
                    allKeys.add(pref.key)
                }
            }
            return allKeys
        }

        companion object {
            @JvmStatic
            fun getSubscreenIntent(context: Context?): Intent {
                return getSubscreenIntent(context, CustomButtonsSettingsFragment::class.java.name)
            }
        }
    }

    class AdvancedStatisticsSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_advanced_statistics
        override val analyticsScreenNameConstant: String
            get() = "prefs.advanced_statistics"

        override fun initSubscreen() {
            // Precision of computation
            requirePreference<SeekBarPreferenceCompat>(R.string.pref_computation_precision_key)
                .setFormattedSummary(R.string.pref_summary_percentage)
        }
    }

    class CustomSyncServerSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_custom_sync_server
        override val analyticsScreenNameConstant: String
            get() = "prefs.custom_sync_server"

        override fun initSubscreen() {
            // Use custom sync server
            requirePreference<SwitchPreference>(R.string.custom_sync_server_enable_key).setOnPreferenceChangeListener { _ ->
                handleSyncServerPreferenceChange(requireContext())
            }
            // Sync url
            requirePreference<Preference>(R.string.custom_sync_server_base_url_key).setOnPreferenceChangeListener { _, newValue: Any ->
                val newUrl = newValue.toString()
                if (!URLUtil.isValidUrl(newUrl)) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.custom_sync_server_base_url_invalid)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()

                    return@setOnPreferenceChangeListener false
                }
                handleSyncServerPreferenceChange(requireContext())
                true
            }
            // Media url
            requirePreference<Preference>(R.string.custom_sync_server_media_url_key).setOnPreferenceChangeListener { _, newValue: Any ->
                val newUrl = newValue.toString()
                if (!URLUtil.isValidUrl(newUrl)) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.custom_sync_server_media_url_invalid)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                    return@setOnPreferenceChangeListener false
                }
                handleSyncServerPreferenceChange(requireContext())
                true
            }
        }
    }

    class ControlsSettingsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_controls
        override val analyticsScreenNameConstant: String
            get() = "prefs.controls"

        override fun initSubscreen() {
            addAllControlPreferencesToCategory(requirePreference(R.string.controls_command_mapping_cat_key))
        }
    }

    /**
     * Fragment exclusive to DEBUG builds which can be used
     * to add options useful for developers or WIP features.
     */
    class DevOptionsFragment : SettingsFragment() {
        override val preferenceResource: Int
            get() = R.xml.preferences_dev_options
        override val analyticsScreenNameConstant: String
            get() = "prefs.dev_options"

        override fun initSubscreen() {
            val enableDevOptionsPref = requirePreference<SwitchPreference>(R.string.dev_options_enabled_by_user_key)
            // If it is a DEBUG build, hide the preference to disable developer options
            // If it is a RELEASE build, configure the preference to disable dev options
            if (BuildConfig.DEBUG) {
                enableDevOptionsPref.isVisible = false
            } else {
                enableDevOptionsPref.setOnPreferenceChangeListener { _, _ ->
                    showDisableDevOptionsDialog()
                    false
                }
            }
            // Make it possible to test crash reporting
            requirePreference<Preference>(getString(R.string.pref_trigger_crash_key)).setOnPreferenceClickListener {
                Timber.w("Crash triggered on purpose from advanced preferences in debug mode")
                throw RuntimeException("This is a test crash")
            }
            // Make it possible to test analytics
            requirePreference<Preference>(getString(R.string.pref_analytics_debug_key)).setOnPreferenceClickListener {
                if (UsageAnalytics.isEnabled) {
                    showThemedToast(requireContext(), "Analytics set to dev mode", true)
                } else {
                    showThemedToast(requireContext(), "Done! Enable Analytics in 'General' settings to use.", true)
                }
                UsageAnalytics.setDevMode()
                true
            }
            // Lock database
            requirePreference<Preference>(getString(R.string.pref_lock_database_key)).setOnPreferenceClickListener {
                val c = CollectionHelper.getInstance().getCol(requireContext())!!
                Timber.w("Toggling database lock")
                c.db.database.beginTransaction()
                true
            }
            // Reset onboarding
            requirePreference<Preference>(getString(R.string.pref_reset_onboarding_key)).setOnPreferenceClickListener {
                OnboardingUtils.reset(requireContext())
                true
            }
            // Use V16 Backend
            requirePreference<Preference>(getString(R.string.pref_rust_backend_key)).apply {
                setDefaultValue(!BackendFactory.defaultLegacySchema)
                setOnPreferenceClickListener {
                    BackendFactory.defaultLegacySchema = false
                    (requireActivity() as Preferences).restartWithNewDeckPicker()
                    true
                }
            }
            // Use scoped storage
            requirePreference<Preference>(getString(R.string.pref_scoped_storage_key)).apply {
                setDefaultValue(AnkiDroidApp.TESTING_SCOPED_STORAGE)
                setOnPreferenceClickListener {
                    AnkiDroidApp.TESTING_SCOPED_STORAGE = true
                    (requireActivity() as Preferences).restartWithNewDeckPicker()
                    true
                }
            }
        }

        /**
         * Shows dialog to confirm if developer options should be disabled
         */
        private fun showDisableDevOptionsDialog() {
            MaterialDialog(requireContext()).show {
                title(R.string.disable_dev_options)
                positiveButton(R.string.dialog_ok) {
                    disableDevOptions()
                }
                negativeButton(R.string.dialog_cancel)
            }
        }

        /**
         * Destroys the fragment and hides developer options on [HeaderFragment]
         */
        private fun disableDevOptions() {
            val fragment = parentFragmentManager.findFragmentByTag(HeaderFragment::class.java.name)
            if (fragment is HeaderFragment) {
                fragment.setDevOptionsVisibility(false)
            }
            parentFragmentManager.popBackStack()
            setDevOptionsEnabledByUser(requireContext(), false)
        }

        companion object {
            /**
             * @return whether developer options should be shown to the user.
             * True in case [BuildConfig.DEBUG] is true
             * or if the user has enabled it with the secret on [com.ichi2.anki.preferences.AboutFragment]
             */
            fun isEnabled(context: Context): Boolean {
                return BuildConfig.DEBUG || AnkiDroidApp.getSharedPrefs(context)
                    .getBoolean(context.getString(R.string.dev_options_enabled_by_user_key), false)
            }

            fun setDevOptionsEnabledByUser(context: Context, isEnabled: Boolean) {
                AnkiDroidApp.getSharedPrefs(context).edit {
                    putBoolean(context.getString(R.string.dev_options_enabled_by_user_key), isEnabled)
                }
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
    }

    companion object {
        /** Key of the language preference  */
        const val LANGUAGE = "language"

        /* Only enable AnkiDroid notifications unrelated to due reminders */
        const val PENDING_NOTIFICATIONS_ONLY = 1000000

        /**
         * The number of cards that should be due today in a deck to justify adding a notification.
         */
        const val MINIMUM_CARDS_DUE_FOR_NOTIFICATION = "minimumCardsDueForNotification"

        const val INITIAL_FRAGMENT_EXTRA = "initial_fragment"

        /** Returns the hour that the collection rolls over to the next day  */
        @JvmStatic
        fun getDayOffset(col: Collection): Int {
            return when (col.schedVer()) {
                2 -> col.get_config("rollover", 4.toInt())!!
                // 1, or otherwise:
                else -> col.crtGregorianCalendar()[Calendar.HOUR_OF_DAY]
            }
        }

        fun getSchedVer(col: Collection): Int {
            val ver = col.schedVer()
            if (ver < 1 || ver > 2) {
                Timber.w("Unknown scheduler version: %d", ver)
            }
            return ver
        }

        /**
         * Join [strings] with ` • ` as separator
         * to build a summary string for some preferences categories
         * e.g. `foo`, `bar`, `hi` ->  `foo • bar • hi`
         */
        fun buildCategorySummary(vararg strings: String): String {
            return if (!LanguageUtils.appLanguageIsRTL()) {
                strings.joinToString(separator = " • ")
            } else {
                strings.reversed().joinToString(separator = " • ")
            }
        }

        /** Whether the user is logged on to AnkiWeb  */
        fun hasAnkiWebAccount(preferences: SharedPreferences): Boolean =
            preferences.getString("username", "")!!.isNotEmpty()
    }
}
