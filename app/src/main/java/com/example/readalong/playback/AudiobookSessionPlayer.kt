package com.example.readalong.playback

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

/** Advertise audiobook time jumps to System UI/Auto, rather than music track skipping. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudiobookSessionPlayer(player: Player, private val skip: (Long) -> ListenableFuture<*>) : ForwardingSimpleBasePlayer(player) {
    private var back = 20_000L
    private var forward = 30_000L
    fun intervals(backMs: Long, forwardMs: Long) { back = backMs; forward = forwardMs; invalidateState() }
    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon().setSeekBackIncrementMs(back).setSeekForwardIncrementMs(forward)
            .setAvailableCommands(state.availableCommands.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()).build()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_BACK -> skip(-back)
        Player.COMMAND_SEEK_FORWARD -> skip(forward)
        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
