package com.github.libretube.ui.preferences

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.InstanceHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.PipedInstance
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.SimpleOptionsRecyclerBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.InstancesAdapter
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.CustomInstanceDialog
import com.github.libretube.ui.dialogs.DeleteAccountDialog
import com.github.libretube.ui.dialogs.LoginDialog
import com.github.libretube.ui.dialogs.LogoutDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

class InstanceSettings : BasePreferenceFragment() {
    override val titleResourceId: Int = R.string.instance
    private val token get() = PreferenceHelper.getToken()
    private var instances = mutableListOf<PipedInstance>()
    private val authInstanceToggle get() = findPreference<SwitchPreferenceCompat>(
        PreferenceKeys.AUTH_INSTANCE_TOGGLE
    )!!

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.instance_settings, rootKey)

        val instancePref = findPreference<ListPreference>(PreferenceKeys.FETCH_INSTANCE)!!
        val authInstanceToggle = findPreference<SwitchPreferenceCompat>(
            PreferenceKeys.AUTH_INSTANCE_TOGGLE
        )!!
        val authInstance = findPreference<ListPreference>(PreferenceKeys.AUTH_INSTANCE)!!
        val instancePrefs = listOf(instancePref, authInstance)
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            // update the instances to also show custom ones
            initInstancesPref(instancePrefs, InstanceHelper.getInstancesFallback(appContext))

            // try to fetch the public list of instances async
            try {
                val instances = withContext(Dispatchers.IO) {
                    InstanceHelper.getInstances(appContext)
                }
                initInstancesPref(instancePrefs, instances)
            } catch (e: Exception) {
                appContext.toastFromMainDispatcher(e.message.orEmpty())
            }
        }

        authInstance.setOnPreferenceChangeListener { _, _ ->
            RetrofitInstance.lazyMgr.reset()
            logoutAndUpdateUI()
            true
        }

        authInstanceToggle.setOnPreferenceChangeListener { _, _ ->
            RetrofitInstance.lazyMgr.reset()
            logoutAndUpdateUI()
            true
        }

        val customInstance = findPreference<Preference>(PreferenceKeys.CUSTOM_INSTANCE)
        customInstance?.setOnPreferenceClickListener {
            CustomInstanceDialog()
                .show(childFragmentManager, CustomInstanceDialog::class.java.name)
            true
        }

        val clearCustomInstances = findPreference<Preference>(PreferenceKeys.CLEAR_CUSTOM_INSTANCES)
        clearCustomInstances?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                Database.customInstanceDao().deleteAll()
                ActivityCompat.recreate(requireActivity())
            }
            true
        }

        val login = findPreference<Preference>(PreferenceKeys.LOGIN_REGISTER)
        val logout = findPreference<Preference>(PreferenceKeys.LOGOUT)
        val deleteAccount = findPreference<Preference>(PreferenceKeys.DELETE_ACCOUNT)

        login?.isVisible = token.isEmpty()
        logout?.isVisible = token.isNotEmpty()
        deleteAccount?.isEnabled = token.isNotEmpty()

        childFragmentManager.setFragmentResultListener(
            INSTANCE_DIALOG_REQUEST_KEY,
            this
        ) { _, resultBundle ->
            val isLoggedIn = resultBundle.getBoolean(IntentData.loginTask)
            val isLoggedOut = resultBundle.getBoolean(IntentData.logoutTask)
            if (isLoggedIn) {
                login?.isVisible = false
                logout?.isVisible = true
                deleteAccount?.isEnabled = true
            } else if (isLoggedOut) {
                logoutAndUpdateUI()
            }
        }

        login?.setOnPreferenceClickListener {
            LoginDialog().show(childFragmentManager, LoginDialog::class.java.name)
            true
        }

        logout?.setOnPreferenceClickListener {
            LogoutDialog().show(childFragmentManager, LogoutDialog::class.java.name)
            true
        }

        deleteAccount?.setOnPreferenceClickListener {
            DeleteAccountDialog()
                .show(childFragmentManager, DeleteAccountDialog::class.java.name)
            true
        }
    }

    private suspend fun initInstancesPref(
        instancePrefs: List<ListPreference>,
        publicInstances: List<PipedInstance>
    ) = runCatching {
        val customInstances = withContext(Dispatchers.IO) {
            Database.customInstanceDao().getAll()
        }.map { PipedInstance(it.name, it.apiUrl) }

        instances = publicInstances.plus(customInstances).toMutableList()

        // add the currently used instances to the list if they're currently down / not part
        // of the public instances list
        for (apiUrl in listOf(RetrofitInstance.apiUrl, RetrofitInstance.authUrl)) {
            if (instances.none { it.apiUrl == apiUrl }) {
                val origin = apiUrl.toHttpUrl().host
                instances.add(PipedInstance(origin, apiUrl, isCurrentlyDown = true))
            }
        }

        instances.sortBy { it.name }

        // If any preference dialog is visible in this fragment, it's one of the instance selection
        // dialogs. In order to prevent UX issues, we don't update the instances list then.
        if (isDialogVisible) return@runCatching

        for (instancePref in instancePrefs) {
            // add custom instances to the list preference
            instancePref.entries = instances.map { it.name }.toTypedArray()
            instancePref.entryValues = instances.map { it.apiUrl }.toTypedArray()
            instancePref.summaryProvider =
                Preference.SummaryProvider<ListPreference> { preference ->
                    preference.entry
                }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key in arrayOf(PreferenceKeys.FETCH_INSTANCE, PreferenceKeys.AUTH_INSTANCE)) {
            showInstanceSelectionDialog(preference as ListPreference)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun showInstanceSelectionDialog(preference: ListPreference) {
        var selectedInstance = preference.value
        val selectedIndex = instances.indexOfFirst { it.apiUrl == selectedInstance }

        val layoutInflater = LayoutInflater.from(context)
        val binding = SimpleOptionsRecyclerBinding.inflate(layoutInflater)
        binding.optionsRecycler.layoutManager = LinearLayoutManager(context)

        val instances = ImmutableList.copyOf(this.instances)
        binding.optionsRecycler.adapter = InstancesAdapter(selectedIndex) {
            selectedInstance = instances[it].apiUrl
        }.also {
            it.submitList(instances)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.title)
            .setView(binding.root)
            .setPositiveButton(R.string.okay) { _, _ ->
                preference.value = selectedInstance
                resetForNewInstance()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun logoutAndUpdateUI() {
        PreferenceHelper.setToken("")
        Toast.makeText(context, getString(R.string.loggedout), Toast.LENGTH_SHORT).show()
        findPreference<Preference>(PreferenceKeys.LOGIN_REGISTER)?.isVisible = true
        findPreference<Preference>(PreferenceKeys.LOGOUT)?.isVisible = false
        findPreference<Preference>(PreferenceKeys.DELETE_ACCOUNT)?.isEnabled = false
    }

    private fun resetForNewInstance() {
        if (!authInstanceToggle.isChecked) {
            logoutAndUpdateUI()
        }
        RetrofitInstance.lazyMgr.reset()
        ActivityCompat.recreate(requireActivity())
    }

    companion object {
        const val INSTANCE_DIALOG_REQUEST_KEY = "instance_dialog_request_key"
    }
}
