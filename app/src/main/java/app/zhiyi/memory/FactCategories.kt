package app.zhiyi.memory

/** 事实分类固定表 + 自由文本归一化（模型有时不按分类输出，兜底映射） */
object FactCategories {
    val ALL = listOf(
        "基本信息", "性格", "喜好", "健康", "工作学业", "家庭关系",
        "情感", "经历往事", "计划", "状态", "观念", "其他",
    )

    fun normalize(raw: String): String {
        val r = raw.trim()
        if (r.isEmpty()) return "其他"
        if (r in ALL) return r
        return when {
            // 计划优先：带时间/事件的句子更容易被其他关键词抢走（"去医院"→健康、"看电影"→喜好）
            Regex("计划|打算|准备|明天|下周|周末|要去|准备去|面试|考试|出差|约会|交报告|要去看|要去见|去医院|去医院|搬家|旅行").containsMatchIn(r) -> "计划"
            Regex("职业|工作|公司|老板|同事|学业|学校|专业|职位|开发|项目|做这个|软件|客户|加班|辞职").containsMatchIn(r) -> "工作学业"
            Regex("性格|感性|理性|内向|外向|脾气|习惯|为人|随和|固执").containsMatchIn(r) -> "性格"
            Regex("喜欢(?!的)|爱好|偏好|口味|颜色|食物|音乐|游戏|动漫|电影|看书|咖啡|猫|狗|宠物").containsMatchIn(r) -> "喜好"
            Regex("健康|病|疼|痛|睡|失眠|身体|医|药|感冒|过敏").containsMatchIn(r) -> "健康"
            Regex("家人|家里|家庭|父母|爸|妈|哥|姐|弟|妹|孩子|儿子|女儿|亲戚").containsMatchIn(r) -> "家庭关系"
            Regex("恋爱|喜欢的人|恋|前任|暗恋|分手|情感|心动").containsMatchIn(r) -> "情感"
            Regex("计划|打算|准备|将来|明天|下周|要去做|面试|考试|出差|约会|交报告").containsMatchIn(r) -> "计划"
            Regex("身份|名字|姓名|年龄|生日|住|城市|家乡|身高|职业是").containsMatchIn(r) -> "基本信息"
            Regex("想法|观点|看法|认为|意义|价值|观念|相信|哲学|思考|困惑").containsMatchIn(r) -> "观念"
            Regex("状态|心情|最近|忙|累|状态|情绪|低落|开心").containsMatchIn(r) -> "状态"
            Regex("经历|曾经|过去|以前|去过|见过|小时候|当年|记得").containsMatchIn(r) -> "经历往事"
            else -> "其他"
        }
    }
}
