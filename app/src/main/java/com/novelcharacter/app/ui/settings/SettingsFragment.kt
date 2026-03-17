package com.novelcharacter.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentSettingsBinding
import com.novelcharacter.app.util.ThemeHelper

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateThemeLabel()

        binding.themeRow.setOnClickListener {
            showThemeDialog()
        }

        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.versionText.text = getString(R.string.settings_version_format, pInfo.versionName)
        } catch (e: Exception) {
            binding.versionText.text = getString(R.string.settings_version_format, "1.0")
        }
    }

    private fun updateThemeLabel() {
        val mode = ThemeHelper.getSavedTheme(requireContext())
        binding.themeValue.text = when (mode) {
            ThemeHelper.MODE_LIGHT -> getString(R.string.settings_theme_light)
            ThemeHelper.MODE_DARK -> getString(R.string.settings_theme_dark)
            else -> getString(R.string.settings_theme_system)
        }
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val current = ThemeHelper.getSavedTheme(requireContext())

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                ThemeHelper.saveTheme(requireContext(), which)
                ThemeHelper.applyTheme(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
