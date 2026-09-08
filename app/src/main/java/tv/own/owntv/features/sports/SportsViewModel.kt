package tv.own.owntv.features.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.features.settings.data.SettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModel(
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val sourceRepository: SourceRepository,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val activeProfileId = profileDao.observeActiveId()
    private val defaultSourceId = settings.defaultSourceId

    val sportsSections: StateFlow<List<SportsSectionData>> = activeProfileId
        .flatMapLatest { pid ->
            val profileId = pid ?: -1L
            sourceRepository.observeSources(profileId).flatMapLatest { sources ->
                defaultSourceId.flatMapLatest { defaultId ->
                    val scopedSources = if (defaultId > 0) sources.filter { it.id == defaultId } else sources
                    val sourceIds = scopedSources.map { it.id }
                    if (sourceIds.isEmpty()) {
                        flow { emit(emptyList()) }
                    } else {
                        categoryDao.observe(sourceIds, MediaType.LIVE).map { categories ->
                            val sectionMap = mutableMapOf<SportsSection, MutableList<tv.own.owntv.core.database.entity.ChannelEntity>>()

                            for (cat in categories) {
                                val section = matchSportsSection(cat.name)
                                if (section != null) {
                                    val channels = channelDao.getChannelsByCategory(cat.id, 20)
                                    if (channels.isNotEmpty()) {
                                        val list = sectionMap.getOrPut(section) { mutableListOf() }
                                        for (ch in channels) {
                                            if (list.none { it.id == ch.id }) {
                                                list.add(ch)
                                            }
                                        }
                                    }
                                }
                            }

                            SportsSection.entries.mapNotNull { sec ->
                                val chs = sectionMap[sec]
                                if (!chs.isNullOrEmpty()) {
                                    SportsSectionData(sec, chs)
                                } else {
                                    null
                                }
                            }.sortedBy { it.section.sortOrder }
                        }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
