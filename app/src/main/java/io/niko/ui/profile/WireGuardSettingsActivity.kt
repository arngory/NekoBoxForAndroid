package io.niko.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.niko.R
import io.niko.database.preference.EditTextPreferenceModifiers
import io.niko.fmt.wireguard.WireGuardBean
import io.niko.proxy.PreferenceBinding
import io.niko.proxy.PreferenceBindingManager
import io.niko.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val persistentKeepalive = pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepalive"))
    private val amneziaWG = pbm.add(PreferenceBinding(Type.Text, "amneziaWG"))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (persistentKeepalive.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (amneziaWG.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Monospace)
    }

}