// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.kitchen

import androidx.core.content.FileProvider
import helium314.keyboard.latin.BuildConfig

/** Must match the manifest authority `${applicationId}.kitchenprovider` (debug suffix included). */
const val KITCHEN_AUTHORITY_SUFFIX = ".kitchenprovider"

fun kitchenProviderAuthority(): String = BuildConfig.APPLICATION_ID + KITCHEN_AUTHORITY_SUFFIX

class KitchenFileProvider : FileProvider()
