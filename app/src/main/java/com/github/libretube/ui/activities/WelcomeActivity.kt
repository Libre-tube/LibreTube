package com.github.libretube.ui.activities

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isGone
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.ActivityWelcomeBinding
import com.github.libretube.helpers.BackupHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.InstancesAdapter
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.models.WelcomeModel
import com.github.libretube.ui.preferences.BackupRestoreSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WelcomeActivity : BaseActivity() {
    private val viewModel: WelcomeModel by viewModels()

    private val restoreFilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            CoroutineScope(Dispatchers.IO).launch {
                BackupHelper.restoreAdvancedBackup(this@WelcomeActivity, uri)

                // only skip the welcome activity if the restored backup contains an instance
                val instancePref = PreferenceHelper.getString(PreferenceKeys.FETCH_INSTANCE, "")
                if (instancePref.isNotEmpty()) {
                    withContext(Dispatchers.Main) { startMainActivity() }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.instancesRecycler.layoutManager = LinearLayoutManager(this@WelcomeActivity)
        val adapter = InstancesAdapter(viewModel.selectedInstanceIndex.value) { index ->
            viewModel.selectedInstanceIndex.value = index
            binding.okay.alpha = 1f
        }
        binding.instancesRecycler.adapter = adapter

        // ALl the binding values are optional due to two different possible layouts (normal, landscape)
        viewModel.instances.observe(this) { instances ->
            adapter.submitList(ImmutableList.copyOf(instances))
            binding.progress.isGone = true
        }
        viewModel.fetchInstances()

        binding.okay.alpha = if (viewModel.selectedInstanceIndex.value != null) 1f else 0.5f
        binding.okay.setOnClickListener {
            if (viewModel.selectedInstanceIndex.value != null) {
                val selectedInstance =
                    viewModel.instances.value!![viewModel.selectedInstanceIndex.value!!]
                PreferenceHelper.putString(PreferenceKeys.FETCH_INSTANCE, selectedInstance.apiUrl)
                startMainActivity()
            } else {
                Toast.makeText(this, R.string.choose_instance, Toast.LENGTH_LONG).show()
            }
        }

        binding.restore.setOnClickListener {
            restoreFilePicker.launch(BackupRestoreSettings.JSON)
        }
    }

    private fun startMainActivity() {
        // refresh the api urls since they have changed likely
        RetrofitInstance.lazyMgr.reset()
        val mainActivityIntent = Intent(this@WelcomeActivity, MainActivity::class.java)
        startActivity(mainActivityIntent)
        finish()
    }

    override fun requestOrientationChange() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
    }
}
