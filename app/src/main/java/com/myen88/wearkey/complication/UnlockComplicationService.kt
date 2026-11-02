package com.myen88.wearkey.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.myen88.wearkey.MainActivity

class UnlockComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationDataSourceService.ComplicationRequestListener,
    ) {
        listener.onComplicationData(unlockData())
    }

    override fun getPreviewData(type: ComplicationType): ShortTextComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return unlockData()
    }

    private fun unlockData(): ShortTextComplicationData {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_COMMAND, "unlock")
        }
        val pending = PendingIntent.getActivity(
            this,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("Unlock").build(),
            PlainComplicationText.Builder("Unlock the car").build(),
        )
            .setTapAction(pending)
            .build()
    }
}
