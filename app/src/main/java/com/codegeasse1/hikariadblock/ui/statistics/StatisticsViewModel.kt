package com.codegeasse1.hikariadblock.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codegeasse1.hikariadblock.data.entities.AppStat
import com.codegeasse1.hikariadblock.data.entities.DailyStat
import com.codegeasse1.hikariadblock.data.dao.DnsLogDao
import com.codegeasse1.hikariadblock.data.repository.FilterListRepository
import com.codegeasse1.hikariadblock.data.entities.HourlyStat
import com.codegeasse1.hikariadblock.data.entities.MonthlyStat
import com.codegeasse1.hikariadblock.data.entities.TopBlockedDomain
import com.codegeasse1.hikariadblock.data.entities.WeeklyStat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class StatisticsViewModel(
    dnsLogDao: DnsLogDao
) : ViewModel() {

    private val todayStart: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayTotal: StateFlow<Int> = dnsLogDao.getTotalCountSince(todayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayBlocked: StateFlow<Int> = dnsLogDao.getBlockedCountSince(todayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val securityBlockedCount: StateFlow<Int> = dnsLogDao.getBlockedCountByReason(
        FilterListRepository.BLOCK_REASON_SECURITY
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todaySecurityBlocked: StateFlow<Int> = dnsLogDao.getBlockedCountByReasonSince(
        FilterListRepository.BLOCK_REASON_SECURITY, todayStart
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val hourlyStats: StateFlow<List<HourlyStat>> = dnsLogDao.getHourlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyStats: StateFlow<List<DailyStat>> = dnsLogDao.getDailyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklyStats: StateFlow<List<WeeklyStat>> = dnsLogDao.getWeeklyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthlyStats: StateFlow<List<MonthlyStat>> = dnsLogDao.getMonthlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topBlockedDomains: StateFlow<List<TopBlockedDomain>> = dnsLogDao.getTopBlockedDomains()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topApps: StateFlow<List<AppStat>> = dnsLogDao.getTopApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
