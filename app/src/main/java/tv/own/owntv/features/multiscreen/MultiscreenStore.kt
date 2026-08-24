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
    private val _channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val channels: StateFlow<List<ChannelEntity>> = _channels.asStateFlow()

    private val _audioFocusIndex = MutableStateFlow(0)
    val audioFocusIndex: StateFlow<Int> = _audioFocusIndex.asStateFlow()

    // Map of channel ID to whether it uses ExoPlayer (true) or mpv (false).
    private val _tileEngines = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val tileEngines: StateFlow<Map<Long, Boolean>> = _tileEngines.asStateFlow()

    fun addChannel(channel: ChannelEntity): Boolean {
        val current = _channels.value
        if (current.size >= 4) return false
        if (current.any { it.id == channel.id }) return true // already added
        _channels.value = current + channel
        return true
    }

    fun removeChannel(channelId: Long) {
        val current = _channels.value
        val index = current.indexOfFirst { it.id == channelId }
        if (index >= 0) {
            _channels.value = current.filterIndexed { i, _ -> i != index }
            
            // Clean up engine preference
            val currentEngines = _tileEngines.value.toMutableMap()
            currentEngines.remove(channelId)
            _tileEngines.value = currentEngines

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

    fun setChannels(list: List<ChannelEntity>) {
        _channels.value = list
        if (_audioFocusIndex.value >= list.size) {
            _audioFocusIndex.value = maxOf(0, list.size - 1)
        }
    }

    fun clear() {
        _channels.value = emptyList()
        _tileEngines.value = emptyMap()
        _audioFocusIndex.value = 0
    }

    fun toggleEngine(channelId: Long) {
        val current = _tileEngines.value.toMutableMap()
        val currentlyExo = current[channelId] ?: true
        if (currentlyExo) {
            // libmpv is a singleton, so only one tile can use it at a time.
            // Reset all other tiles to Exo before enabling mpv for this one.
            _tileEngines.value.keys.forEach { current[it] = true }
            current[channelId] = false
        } else {
            current[channelId] = true
        }
        _tileEngines.value = current
    }

    fun isInMultiscreen(channelId: Long): Boolean {
        return _channels.value.any { it.id == channelId }
    }
}
