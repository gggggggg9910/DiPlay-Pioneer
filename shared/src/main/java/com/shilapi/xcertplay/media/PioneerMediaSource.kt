package com.shilapi.xcertplay.media
import android.content.Context
/** Factory source selection is applied when the next CarPlay sink is created. */
enum class PioneerSourceChoice {
 USB, BLUETOOTH,
 /** The App media slot (source 300) QQ Music plays on, reached through the 401 TX_MEDIA stream. */
 MEDIA;
 companion object {
  private const val PREFS = "pioneer_media_source"
  fun load(context: Context): PioneerSourceChoice = runCatching { valueOf(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("source",MEDIA.name)!!) }.getOrDefault(MEDIA)
  fun save(context: Context, source: PioneerSourceChoice) { context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("source",source.name).apply() }
 }
}
