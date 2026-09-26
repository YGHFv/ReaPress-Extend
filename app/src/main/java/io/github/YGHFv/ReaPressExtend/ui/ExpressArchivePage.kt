package io.github.YGHFv.ReaPressExtend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 「归档快递」二级页 —— 首页最下面那一行进来的地方。
 *
 * ## 它装的是什么
 *
 * [ExpressHomeGrouper.archive] 挑出来的那些：**已完成、且超过
 * [ExpressHomeGrouper.ARCHIVE_RETENTION_MS]（7 天）没再动过**的包裹。首页
 * 「已签收 / 异常」那一档于是恒等于「最近一周结束的件」，这一页则是它后面那段历史。
 *
 * 不删除任何东西 —— 归档只是**换一页显示**。这点是有意为之：那些记录仍然能被搜到、
 * 仍然能在详情页里看到全轨迹，将来要「永久清理」也该是用户显式点的动作，而不是时间到了自动抹掉。
 *
 * ## 版面
 *
 * 与首页 / 详情页同一套：`GroupTitle` 抬头 + 一列 [ParcelCard]。**刻意不分档、不按驿站聚合** ——
 * 归档件已经没有「要去取」这个动作了，按取件地点分组只会把同一段时间的包裹拆得七零八落；
 * 这里唯一的顺序就是时间倒序（最近归档的在最上面），也就是用户翻它时想要的那个顺序。
 *
 * ## 没有下拉刷新
 *
 * 这一页的数据不来自网络、也不来自宿主：它是本地存储里已有记录的一个视图，上层
 * （Activity）重读存储后本页会因入参变化自动重组。挂一个下拉箭头只会让人以为「拉一下能拿到什么
 * 新东西」，而这里没有新东西可拿。
 *
 * @param records **全量**记录，不是归档子集。归档页自己筛 —— 聚类（驿站名的简称 / 全名折叠）
 *   必须拿完整集合做，只喂归档件会让同一个驿站在两页上印出两个名字。理由与 [ExpressHomeGrouper.archive]
 *   的 `@param` 一致。
 * @param rules 用户在「驿站管理」里做的合并 / 改名。列表上印的驿站名走它。
 * @param onOpenDetail 单击卡片进包裹详情页。传 null 表示这一页只读（卡片不可点）。
 * @param onBack 顶栏返回 / 系统返回键，两者都走它。
 */
@Composable
internal fun ExpressArchivePage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    onBack: () -> Unit,
    onOpenDetail: ((ExpressRecord) -> Unit)? = null,
) {
    val scrollBehavior = MiuixScrollBehavior()

    // 系统返回键等同于顶栏的返回箭头（与详情页 / 驿站管理页同一条约定）。
    BackHandler(onBack = onBack)

    // 「现在」只用来做归档切分，取一次就够 —— 与首页那边同一个口径
    // （`ExpressHomeGrouper.archive` 的 now 参数）。
    val now = System.currentTimeMillis()
    val archived = ExpressHomeGrouper.archive(records, now)
    // 显示名表拿**全量**记录算，理由见上面 @param。
    val stationLabels = ExpressHomeGrouper.stationLabels(records, rules)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "归档快递",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 与详情页 / 日志页同一条规矩：顶栏上的返回不铺 secondaryContainer 底色。
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(vertical = 4.dp),
        ) {
            if (archived.isEmpty()) {
                // 空态也要说清「这里将来会有什么」—— 否则用户点进来是一片空白，
                // 只会以为入口点坏了，而不知道归档是被时间条件触发的。
                GroupTitle("归档快递")
                SettingsCard {
                    HintText("暂无归档。已签收或已取件超过 7 天的包裹会自动移到这里，首页就不再显示它们。")
                }
            } else {
                GroupTitle("归档快递 · ${archived.size}件")
                for (record in archived) {
                    ParcelCard(
                        record = record,
                        stationLabel = ExpressHomeGrouper.stationLabelOf(record, stationLabels),
                        rules = rules,
                        now = now,
                        // 归档件不支持双击撤销「已取件」：那是首页卡片上的动作（这一件刚从驿站
                        // 拿回来、可能点错了）。几个月前的记录不再有「撤销」这个语义，
                        // 留着只会多出一个隐藏的双击区。
                        onTogglePickup = null,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
            // 内容区自己补上顶栏 / 底栏的高度（与首页同一个写法：滚动内容要能整段穿过
            // 半透明顶栏，所以 padding 加在内容里而不是 Scaffold 的容器上）。
            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}
