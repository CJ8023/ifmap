package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示「引入 starter 之后的完整用法」：
 * 写配置 → 从库里查配置 → 引擎按配置模板渲染报文。
 *
 * @author caijun
 */
@Component
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private static final String TENANT = "1001";
    private static final String INTERFACE_NO = "BIZ_APPLY";
    private static final String BUSI_NODE = "apply";

    private static final String REQUEST_TEMPLATE = "{"
            + "\"orgNo\":\"@FUN(bankOrgNo,$.orgCode)\","
            + "\"applyNo\":\"$.applyNo\","
            + "\"applyDate\":\"@FUN(dateFormat,$.applyTime,yyyyMMdd)\","
            + "\"acctName\":\"@FUN(strMask,$.acctName,NAME)\""
            + "}";

    private final ConfigRepository repository;
    private final JdbcConfigWriter writer;
    private final IfmapEngine engine;

    public DemoRunner(ConfigRepository repository, JdbcConfigWriter writer, IfmapEngine engine) {
        this.repository = repository;
        this.writer = writer;
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfAbsent();

        List<IfmapConfig> configs = repository.queryConfigs(TENANT, INTERFACE_NO, BUSI_NODE);
        log.info("从库里查到 {} 条配置（表已由 starter 自动建好）", configs.size());

        List<LogicBranchConfig> branches = repository.queryLogicBranches(TENANT, INTERFACE_NO);
        log.info("逻辑分支 {} 条（该查询带缓存装饰器）", branches.size());

        String source = "{"
                + "\"orgCode\":\"12\","
                + "\"applyNo\":\"AP20250101001\","
                + "\"applyTime\":\"2025-03-08 10:20:30\","
                + "\"acctName\":\"张三\""
                + "}";
        String rendered = engine.render(configs.get(0).getRequestParamTemplate(), source);
        log.info("渲染结果：{}", rendered);
    }

    /** 幂等写入演示配置（重复启动不会插重）。 */
    private void seedIfAbsent() {
        if (!repository.queryConfigs(TENANT, INTERFACE_NO, BUSI_NODE).isEmpty()) {
            return;
        }
        IfmapConfig config = new IfmapConfig();
        config.setTenantId(Long.valueOf(TENANT));
        config.setInterfaceNo(INTERFACE_NO);
        config.setInterfaceCode("BIZ_APPLY_001");
        config.setInterfaceName("授信申请-报文组装");
        config.setBusiNode(BUSI_NODE);
        config.setBankCode("CMB");
        config.setBankName("招商银行");
        config.setInterfaceOrder(Integer.valueOf(1));
        config.setRequestParamTemplate(REQUEST_TEMPLATE);
        config.setResultFlag("$.resultCode");
        config.setSuccessValue("0000");
        config.setStrategyName("cmbApplyStrategy");
        config.setStatus(Integer.valueOf(1));
        long keyId = writer.insert(config);
        log.info("已写入演示配置 keyId={}", keyId);
    }
}
