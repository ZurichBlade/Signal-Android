package org.thoughtcrime.securesms.components.voice

import android.net.Uri
import org.thoughtcrime.securesms.database.DraftTable
import org.thoughtcrime.securesms.util.MediaUtil

private const val SIZE = "size"
private const val TYPE = "type"

class VoiceNoteDraft(
  val uri: Uri,
  val size: Long,
  val contentType: String = MediaUtil.AUDIO_AAC
) {
  companion object {
    @JvmStatic
    fun fromDraft(draft: DraftTable.Draft): VoiceNoteDraft {
      if (draft.type != DraftTable.Draft.VOICE_NOTE) {
        throw IllegalArgumentException()
      }

      val draftUri = Uri.parse(draft.value)

      val uri: Uri = draftUri.buildUpon().clearQuery().build()
      val size: Long = draftUri.getQueryParameter(SIZE)!!.toLong()
      val contentType = draftUri.getQueryParameter(TYPE) ?: MediaUtil.AUDIO_AAC

      return VoiceNoteDraft(uri, size, contentType)
    }
  }

  fun asDraft(): DraftTable.Draft {
    val draftUri = uri.buildUpon()
      .appendQueryParameter(SIZE, size.toString())
      .appendQueryParameter(TYPE, contentType)

    return DraftTable.Draft(DraftTable.Draft.VOICE_NOTE, draftUri.build().toString())
  }
}
