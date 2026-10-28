package io.niko.widget

import android.content.Context
import android.util.AttributeSet
import io.niko.R
import io.niko.database.DataStore
import io.niko.database.ProfileManager
import io.niko.ui.SimpleMenuPreference

class OutboundPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    init {
        setEntries(R.array.outbound_entry)
        setEntryValues(R.array.outbound_value)
    }

    override fun getSummary(): CharSequence? {
        if (value == "3") {
            val routeOutbound = DataStore.profileCacheStore.getLong(key + "Long") ?: 0
            if (routeOutbound > 0) {
                ProfileManager.getProfile(routeOutbound)?.displayName()?.let {
                    return it
                }
            }
        }
        return super.getSummary()
    }

}