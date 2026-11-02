package com.myen88.wearkey.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.myen88.wearkey.MainActivity
import com.myen88.wearkey.data.WatchKeyStore

class KeyTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val store = WatchKeyStore(this)
        val lock = store.lastLock ?: "Pair the watch"
        val auto = if (store.autoKey) "Auto on" else "Auto off"
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout(lock, auto))
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build(),
        )
    }

    private fun layout(lock: String, auto: String): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(label(lock, 14f, null))
            .addContent(label("Unlock", 16f, click("unlock")))
            .addContent(label("Lock", 16f, click("lock")))
            .addContent(label(auto, 14f, click("auto")))
            .build()
    }

    private fun label(
        text: String,
        size: Float,
        clickable: ModifiersBuilders.Clickable?,
    ): LayoutElementBuilders.LayoutElement {
        val builder = LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(size))
                    .setColor(ColorBuilders.argb(0xFFF6F3EC.toInt()))
                    .build(),
            )
        if (clickable != null) {
            builder.setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(DimensionBuilders.dp(6f))
                            .setBottom(DimensionBuilders.dp(2f))
                            .build(),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    private fun click(command: String): ModifiersBuilders.Clickable {
        return ModifiersBuilders.Clickable.Builder()
            .setId(command)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_COMMAND,
                                ActionBuilders.AndroidStringExtra.Builder().setValue(command).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    companion object {
        private const val RESOURCES = "1"
    }
}
