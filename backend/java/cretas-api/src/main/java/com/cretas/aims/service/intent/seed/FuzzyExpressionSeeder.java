package com.cretas.aims.service.intent.seed;

import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.ExpressionLearningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模糊问句种子表达器 (FuzzyExpressionSeeder)
 *
 * <p>背景: 餐饮分析类意图 (评价 / 评分-营收相关性 / 综合分析 / 畅销慢销等) 在
 * {@code ai_intent_configs} 中只配了关键词, 没有示例表达。当用户自由输入
 * "客户评价怎么样 / 门店评分怎么样 / 服务怎么样" 这类以 "怎么样 / 如何 / 好不好"
 * 结尾的问句时, 识别管线把它归类为 GENERAL_QUESTION (模糊查询), 触发提高阈值
 * (0.88 / 0.90, 见 {@code IntentRecognitionPipelineServiceImpl} line ~1425/1521),
 * 纯关键词意图的语义分达不到阈值被拒, 最终落到 LLM 兜底并可能产生幻觉。</p>
 *
 * <p>修法: 把用户实际会输入的模糊问句作为 <b>示例表达</b> 种入 {@code ai_learned_expressions}
 * (带 embedding)。当用户输入近似的问句时, 与种子的余弦相似度 ~0.99, 作为统一语义匹配
 * 结果进入候选, 置信度足以越过 0.88/0.90 模糊阈值, 从而正确路由到对应意图。</p>
 *
 * <p>关键设计:</p>
 * <ul>
 *   <li><b>全局种子</b> (factory_id = "*"): 所有餐饮工厂共享 (制造业态工厂由
 *       BusinessTypeGate 诚实拦截, 不会误答)。</li>
 *   <li><b>幂等</b>: {@link ExpressionLearningService#learnExpression} 内部按
 *       expression_hash + factoryId 去重, 已存在则跳过。每次启动重复跑是安全的。</li>
 *   <li><b>自动入缓存</b>: learnExpression 保存后立即调用 cacheExpression 进 Layer 4
 *       内存搜索, 无需额外刷新。</li>
 *   <li><b>embedding 守卫</b>: 启动时若 embedding 服务不可用则整体跳过 (下次启动重试),
 *       避免存下没有 embedding 的死种子 (hash 已记录会导致永不重试)。</li>
 *   <li><b>只种活跃意图</b>: 已停用的孤儿意图 (DISH_LIST / PERFORMANCE_EVAL /
 *       REVIEW_COMPETITIVE / SLOW_SELLER_QUERY) 不种。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(100)
public class FuzzyExpressionSeeder implements ApplicationRunner {

    /** 全局工厂作用域: 所有工厂共享的示例表达 */
    private static final String GLOBAL_FACTORY = "*";

    /** 种子表达置信度 (用户真实输入, 高置信) */
    private static final double SEED_CONFIDENCE = 1.0;

    private final ExpressionLearningService expressionLearningService;
    private final EmbeddingClient embeddingClient;

    public FuzzyExpressionSeeder(ExpressionLearningService expressionLearningService,
                                 EmbeddingClient embeddingClient) {
        this.expressionLearningService = expressionLearningService;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 种子表: intent_code -> 该意图的模糊问句示例 (用户真实会输入的自然说法)。
     *
     * <p>用 LinkedHashMap 保持声明顺序便于阅读/日志。每条以话题词作为锚点
     * (评价 / 服务 / 环境 / vip / 投诉 / ...), 让相邻意图的种子在向量空间分开,
     * 避免 "服务怎么样" 误命中 "环境" 等。</p>
     */
    static final Map<String, List<String>> SEEDS = buildSeeds();

    private static Map<String, List<String>> buildSeeds() {
        Map<String, List<String>> m = new LinkedHashMap<>();

        // ---- 评价总览 ----
        m.put("RESTAURANT_REVIEW_SUMMARY", List.of(
                "客户评价怎么样", "顾客评价怎么样", "用户评价怎么样",
                "评价情况怎么样", "口碑怎么样", "整体评价怎么样",
                "顾客满意度怎么样", "评价好不好"));

        // ---- 服务 ----
        m.put("RESTAURANT_REVIEW_SERVICE_TAGS", List.of(
                "服务怎么样", "服务评价怎么样", "顾客怎么评价服务",
                "服务态度怎么样", "服务好不好"));
        m.put("RESTAURANT_REVIEW_SERVICE_SCORE", List.of(
                "服务分怎么样", "服务评分怎么样", "服务得分高不高"));

        // ---- 环境 ----
        m.put("RESTAURANT_REVIEW_ENV_TAGS", List.of(
                "环境怎么样", "用餐环境怎么样", "环境评价怎么样", "店里环境好不好"));
        m.put("RESTAURANT_REVIEW_ENV_SCORE", List.of(
                "环境分怎么样", "环境评分怎么样"));

        // ---- VIP / 会员 ----
        m.put("RESTAURANT_REVIEW_VIP", List.of(
                "vip客户评价怎么样", "会员评价怎么样", "vip顾客满意度怎么样",
                "会员和普通顾客评价差别"));
        m.put("RESTAURANT_REVIEW_VIP_TAGS", List.of(
                "vip顾客关注什么", "会员在意什么", "vip顾客评价标签"));

        // ---- 投诉 / 差评 ----
        m.put("RESTAURANT_REVIEW_COMPLAINT", List.of(
                "投诉情况怎么样", "有哪些投诉", "客诉怎么样", "顾客投诉多不多"));
        m.put("RESTAURANT_REVIEW_BAD_STORE", List.of(
                "哪家店评价差", "哪个门店口碑差", "差评最多的店", "评价最差的门店"));
        m.put("RESTAURANT_REVIEW_DISH_ISSUE", List.of(
                "菜品有什么问题", "哪些菜被吐槽", "菜品差评怎么样"));

        // ---- 好评 ----
        m.put("RESTAURANT_REVIEW_GOOD_TAGS", List.of(
                "顾客夸什么", "好评都说什么", "高频好评词", "顾客最满意什么"));

        // ---- 趋势 / 回复率 / 平台 / 城市 / 时段 ----
        m.put("RESTAURANT_REVIEW_TREND", List.of(
                "评价趋势怎么样", "口碑趋势怎么样", "评价走势如何", "评分变化趋势"));
        m.put("RESTAURANT_REVIEW_REPLY_RATE", List.of(
                "评价回复率怎么样", "回复率高不高", "评价回复情况"));
        m.put("RESTAURANT_REVIEW_PLATFORM", List.of(
                "各平台评价怎么样", "平台口碑对比", "哪个平台评价好"));
        m.put("RESTAURANT_REVIEW_CITY", List.of(
                "各城市评价怎么样", "哪个城市口碑好", "城市评价对比"));
        m.put("RESTAURANT_REVIEW_TIME_PERIOD", List.of(
                "哪个时段评价好", "各时段评价怎么样", "时段口碑对比"));

        // ---- 评分-营收相关性 (P3) ----
        m.put("RESTAURANT_RATING_REVENUE_CORRELATION", List.of(
                "高分门店是不是更赚钱", "评分和营收有关系吗", "评分高的店营收怎么样",
                "评分影响营收吗", "口碑好的店赚得多吗"));

        // ---- 综合分析 (P2) ----
        m.put("COMPREHENSIVE_SYNTHESIS", List.of(
                "综合分析一下", "帮我综合分析", "综合看看经营情况",
                "整体经营分析", "全面分析一下经营"));

        // ---- 门店营收排行 ----
        m.put("RESTAURANT_STORE_REVENUE_RANK", List.of(
                "哪家店业绩最好", "门店营收对比", "门店业绩排名",
                "哪家店最赚钱", "哪家店生意最好", "门店营收谁最高"));

        // ---- 销售类 (活跃) ----
        m.put("RESTAURANT_BESTSELLER_QUERY", List.of(
                "哪些菜卖得好", "卖得最好的菜是什么", "畅销菜怎么样", "什么菜最受欢迎"));
        m.put("RESTAURANT_DISH_SLOW", List.of(
                "哪些菜卖得差", "卖不动的菜有哪些", "滞销菜怎么样", "什么菜卖得最少"));
        m.put("RESTAURANT_DISCOUNT_USAGE", List.of(
                "优惠券用得怎么样", "优惠活动效果怎么样", "折扣使用情况"));
        m.put("RESTAURANT_PEAK_MONTH", List.of(
                "哪个月生意最好", "销售高峰在什么时候", "旺季是几月"));
        m.put("RESTAURANT_WEEKDAY_WEEKEND", List.of(
                "工作日和周末生意差别", "周末生意怎么样", "平时和周末对比"));
        m.put("RESTAURANT_STAFF_RANKING", List.of(
                "员工业绩排名怎么样", "哪个员工业绩好", "服务员业绩排行"));

        // ---- 营收趋势 / 同比环比 (gold trend tool; 高余弦种子压过 COMPREHENSIVE_SYNTHESIS) ----
        // 必含仪表盘 chip t9 原话, 让"同比环比/趋势"问题命中 RESTAURANT_REVENUE_TREND(绑 gold
        // restaurant_revenue_trend_gold)直接执行出月度趋势+图, 而非被综合分析拦截或 LLM 描述。
        m.put("RESTAURANT_REVENUE_TREND", List.of(
                "进行同比和环比分析，识别增长和下降趋势",
                "同比和环比分析，识别增长和下降趋势",
                "同比环比分析", "识别增长和下降趋势",
                "营收趋势", "月度趋势", "营收趋势分析", "月度营收趋势",
                "销售额的月度变化趋势", "销售趋势分析", "营收的增长和下降趋势"));

        appendSeedPhrases(m, "RESTAURANT_REVIEW_SUMMARY", List.of(
                "\u5927\u4f17\u70b9\u8bc4\u53e3\u7891\u600e\u4e48\u6837"));
        appendSeedPhrases(m, "RESTAURANT_REVIEW_GOOD_TAGS", List.of(
                "\u54ea\u51e0\u4e2a\u83dc\u54c1\u53e3\u7891\u6700\u597d",
                "\u54ea\u4e9b\u83dc\u53e3\u7891\u6700\u597d",
                "\u83dc\u54c1\u53e3\u7891\u6700\u597d\u7684\u662f\u54ea\u4e9b"));
        appendSeedPhrases(m, "RESTAURANT_REVIEW_COMPLAINT", List.of(
                "\u4f4e\u661f\u8bc4\u4ef7\u5e94\u8be5\u600e\u4e48\u6539\u5584",
                "\u4f4e\u661f\u8bc4\u4ef7\u600e\u4e48\u6539\u5584",
                "\u5dee\u8bc4\u5e94\u8be5\u600e\u4e48\u6539\u5584",
                "\u5dee\u8bc4\u6539\u5584\u5efa\u8bae"));

        return m;
    }

    private static void appendSeedPhrases(Map<String, List<String>> seeds, String intentCode, List<String> additions) {
        List<String> existing = seeds.get(intentCode);
        if (existing == null) {
            seeds.put(intentCode, additions);
            return;
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(existing);
        merged.addAll(additions);
        seeds.put(intentCode, List.copyOf(merged));
    }

    @Override
    public void run(ApplicationArguments args) {
        // embedding 守卫: 服务不可用则整体跳过, 避免存下无 embedding 的死种子。
        if (embeddingClient == null || !embeddingClient.isAvailable()) {
            log.warn("[FuzzyExpressionSeeder] embedding 服务不可用, 跳过模糊问句种子 (下次启动重试)");
            return;
        }

        int intentCount = 0;
        int newSeeded = 0;
        int totalPhrases = 0;
        try {
            for (Map.Entry<String, List<String>> entry : SEEDS.entrySet()) {
                String intentCode = entry.getKey();
                List<String> phrases = entry.getValue();
                totalPhrases += phrases.size();
                int n = expressionLearningService.learnExpressions(
                        GLOBAL_FACTORY, intentCode, phrases,
                        SEED_CONFIDENCE, LearnedExpression.SourceType.MANUAL);
                newSeeded += n;
                intentCount++;
            }
            log.info("[FuzzyExpressionSeeder] 模糊问句种子完成: 意图 {} 个, 新增表达 {}/{} 条 (其余已存在)",
                    intentCount, newSeeded, totalPhrases);
        } catch (Exception e) {
            // 种子失败不阻塞启动 (识别管线退回今天的行为, 无回归)
            log.warn("[FuzzyExpressionSeeder] 模糊问句种子异常 (不影响启动): {}", e.getMessage());
        }
    }
}
