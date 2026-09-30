package cn.cj.ifmap.core.cache;

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CachingConfigRepository} 单测：验证「只缓存分支列表」与租户隔离。
 *
 * @author caijun
 */
class CachingConfigRepositoryTest {

    /** 计数版仓储（不引入 mock 框架，保持 core 测试零依赖）。 */
    private static final class CountingRepository implements ConfigRepository {

        private int branchCalls;
        private int configCalls;
        private int findCalls;
        private int logCalls;

        @Override
        public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
            configCalls++;
            return Collections.singletonList(new IfmapConfig());
        }

        @Override
        public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
            findCalls++;
            return Optional.empty();
        }

        @Override
        public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo) {
            branchCalls++;
            List<LogicBranchConfig> list = new ArrayList<LogicBranchConfig>();
            LogicBranchConfig branch = new LogicBranchConfig();
            branch.setKeyId(1L);
            branch.setInterfaceNo(interfaceNo);
            branch.setMethodFlag("doApply");
            list.add(branch);
            return list;
        }

        @Override
        public void saveExecutionLog(ExecutionLog log) {
            logCalls++;
        }
    }

    private CountingRepository delegate;
    private InMemoryConfigCache cache;
    private CachingConfigRepository repository;

    @BeforeEach
    void setUp() {
        delegate = new CountingRepository();
        cache = new InMemoryConfigCache(10, 0L);
        repository = new CachingConfigRepository(delegate, cache);
    }

    @Test
    @DisplayName("同一 (租户, 接口) 只查一次库")
    void cachesBranchList() {
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        repository.queryLogicBranches("1001", "BIZ_APPLY");

        assertEquals(1, delegate.branchCalls);
        assertEquals(2L, cache.stats().getHitCount());
        assertEquals(1, repository.queryLogicBranches("1001", "BIZ_APPLY").size());
    }

    @Test
    @DisplayName("租户隔离：不同租户互不命中（跨租户串配置是最高风险项）")
    void isolatesTenants() {
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        repository.queryLogicBranches("2002", "BIZ_APPLY");

        assertEquals(2, delegate.branchCalls);
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("接口隔离：不同 interfaceNo 互不命中")
    void isolatesInterfaces() {
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        repository.queryLogicBranches("1001", "BIZ_CANCEL");

        assertEquals(2, delegate.branchCalls);
    }

    @Test
    @DisplayName("ingest：返回的列表不可变（防调用方就地修改缓存内容）")
    void returnedListIsImmutable() {
        final List<LogicBranchConfig> branches = repository.queryLogicBranches("1001", "BIZ_APPLY");
        assertEquals(1, branches.size());
        assertThrows(UnsupportedOperationException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                branches.add(new LogicBranchConfig());
            }
        });
    }

    @Test
    @DisplayName("invalidate 后重新查库")
    void invalidateReloads() {
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        repository.invalidate("1001", "BIZ_APPLY");
        repository.queryLogicBranches("1001", "BIZ_APPLY");

        assertEquals(2, delegate.branchCalls);
    }

    @Test
    @DisplayName("其余方法不缓存，直接委托")
    void otherMethodsDelegate() {
        repository.queryConfigs("1001", "BIZ_APPLY", "apply");
        repository.queryConfigs("1001", "BIZ_APPLY", "apply");
        repository.findConfig("1001", "BIZ_APPLY", "apply", 1);
        repository.saveExecutionLog(ExecutionLog.success("BIZ_APPLY", "B1", 5L));

        assertEquals(2, delegate.configCalls, "主配置表不缓存");
        assertEquals(1, delegate.findCalls);
        assertEquals(1, delegate.logCalls);
        assertSame(delegate, repository.delegate());
    }

    @Test
    @DisplayName("缓存 key 格式固定为 ifmap:logicBranch:<tenantId>:<interfaceNo>")
    void cacheKeyFormat() {
        assertEquals("ifmap:logicBranch:1001:BIZ_APPLY",
                CachingConfigRepository.cacheKey("1001", "BIZ_APPLY"));
        assertEquals("ifmap:logicBranch::BIZ_APPLY",
                CachingConfigRepository.cacheKey(null, "BIZ_APPLY"));
    }

    @Test
    @DisplayName("构造参数必填")
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new CachingConfigRepository(null, cache);
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new CachingConfigRepository(delegate, null);
            }
        });
    }

    @Test
    @DisplayName("缓存统计可见（便于排障：命中率低说明 key 维度选错了）")
    void exposesStats() {
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        repository.queryLogicBranches("1001", "BIZ_APPLY");
        ConfigCache.CacheStats stats = cache.stats();
        assertEquals(1L, stats.getMissCount());
        assertEquals(1L, stats.getHitCount());
        assertTrue(stats.toString().contains("hit=1"));
    }
}
