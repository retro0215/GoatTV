package tv.own.owntv.features.multiscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * In-memory store for the Multiscreen feature (Phase 1).
 * Holds up to 4 selected channels and the current audio focus index.
 */
class MultiscreenStore {
    /**
     * The same on every device: an application maximum, not a decoder promise. A box that cannot run
     * that many streams shows the tile-level decoder error on the tile that failed.
     */
    val maxTiles: Int = MultiscreenLayout.MAX_TILES

    private val _channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val channels: StateFlow<List<ChannelEntity>> = _channels.asStateFlow()

    private val _audioFocusIndex = MutableStateFlow(0)
    val audioFocusIndex: StateFlow<Int> = _audioFocusIndex.asStateFlow()

    fun addChannel(channel: ChannelEntity): Boolean {
        val current = _channels.value
        if (current.size >= maxTiles) return false
        if (current.any { it.id == channel.id }) return true // already added
        _channels.value = current + channel
        return true
    }

    fun removeChannel(channelId: Long) {
        val current = _channels.value
        val index = current.indexOfFirst { it.id == channelId }
        if (index >= 0) {
            _channels.value = current.filterIndexed { i, _ -> i != index }

            // Adjust audio focus if needed
            if (_audioFocusIndex.value >= _channels.value.size) {
                _audioFocusIndex.value = maxOf(0, _channels.value.size - 1)
            }
        }
    }

    fun setAudioFocus(index: Int) {
        if (index in _channels.value.indices) {
            _audioFocusIndex.value = index
        }
    }

    fun moveChannel(fromIndex: Int, toIndex: Int) {
        val current = _channels.value.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        
        val focusedId = _channels.value.getOrNull(_audioFocusIndex.value)?.id
        
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        _channels.value = current
        
        // Restore audio focus to the same channel ID if it moved
        if (focusedId != null) {
            val newIndex = current.indexOfFirst { it.id == focusedId }
            if (newIndex >= 0) _audioFocusIndex.value = newIndex
        }
    }

    /** Move Mode: the two tiles trade places; every other tile stays where it is. */
    fun swapChannels(a: Int, b: Int) {
        val current = _channels.value
        if (a !in current.indices || b !in current.indices || a == b) return

        val focusedId = current.getOrNull(_audioFocusIndex.value)?.id

        val swapped = current.toMutableList()
        swapped[a] = current[b]
        swapped[b] = current[a]
        _channels.value = swapped

        // Audio stays with the same channel wherever it went.
        if (focusedId != null) {
            val newIndex = swapped.indexOfFirst { it.id == focusedId }
            if (newIndex >= 0) _audioFocusIndex.value = newIndex
        }
    }

    /**
     * Replace Channel: the tile at [index] shows [channel] instead, keeping its place (and so the
     * audio focus index). Picking what it already shows is a no-op success; a channel another tile
     * already shows is refused, so a channel is never in two tiles.
     */
    fun replaceChannel(index: Int, channel: ChannelEntity): Boolean {
        val current = _channels.value
        val old = current.getOrNull(index) ?: return false
        if (old.id == channel.id) return true
        if (current.any { it.id == channel.id }) return false

        _channels.value = current.toMutableList().also { it[index] = channel }
        return true
    }

    fun setChannels(list: List<ChannelEntity>) {
        _channels.value = list
        if (_audioFocusIndex.value >= list.size) {
            _audioFocusIndex.value = maxOf(0, list.size - 1)
        }
    }

    fun clear() {
        _channels.value = emptyList()
        _audioFocusIndex.value = 0
    }

    fun isInMultiscreen(channelId: Long): Boolean {
        return _channels.value.any { it.id == channelId }
    }
}
