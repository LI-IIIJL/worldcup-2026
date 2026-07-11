package worldcup.helper.data.repos

import android.content.Context
import worldcup.helper.data.StadiumData

/**
 * 场馆数据 Repository
 *
 * 数据来源: bdl_stadiums.json（16座球场，静态数据）
 * API 等级: 🟢 基础本地
 * 所属架构: StadiumRepo (new_framework.md §2.1)
 *
 * 职责：
 * - 提供场馆搜索/查询能力
 * - 供 MatchRepo 在比赛详情中关联场馆信息
 * - 供 TeamRepo 关联球队主场信息（可选）
 */
class StadiumRepo(context: Context) {

    private val stadiumData by lazy { StadiumData(context) }

    /** 搜索场馆（按名称或城市） */
    fun searchStadiums(query: String) = stadiumData.searchStadiums(query)

    /** 按场馆名称模糊查找 */
    fun findStadium(name: String) = stadiumData.findStadium(name)

    /** 获取所有场馆文本摘要 */
    fun getAllStadiumsSummary() = stadiumData.getAllStadiumsSummary()

    /** 获取所有场馆列表 */
    fun getAllStadiums() = stadiumData.searchStadiums("")
}
