/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;

/**
 * 演示数据的「自对齐」工具：让演示在<b>反复启动、反复改代码</b>之后仍然跑得起来。
 *
 * <p><b>为什么不能只做「有就跳过」</b>：演示行一旦落库就是<b>数据</b>，它不会跟着代码变。策略 bean 改过名
 * （{@code demoStrategy} → {@code demoPreStrategy}）、模板或判定改过内容之后，库里那一行还是旧的，
 * 启动时 {@code applyPreStrategy} 会直接抛「前序策略未注册：pre_strategy_name=demoStrategy」——
 * 而异常里列出的是<b>当前注册的 bean</b>，完全看不出「是数据旧了」，很容易误判成代码问题。</p>
 *
 * <p>三条规则：</p>
 * <ol>
 *   <li><b>配置</b>：用 {@link #matches} 判断演示自己拥有的字段是否与代码一致，不一致就 {@link #alignConfig}
 *       覆盖回去（只覆盖演示拥有的字段，{@code project_code} / {@code remark} 之类保持库里的样子）；</li>
 *   <li><b>分支</b>：{@link #upsertBranch} 按 {@code logic_branch_name} 找同名演示分支，有就改写
 *       judge / 顺序 / 动作，没有才插 —— 直接 {@code insert} 会撞唯一键，而「查空才插」又会让旧 judge 留着；</li>
 *   <li><b>缓存</b>：分支表走 {@link CachingConfigRepository}，改完必须 {@link #invalidateBranches}，
 *       否则引擎拿到的还是旧规格（配置表不缓存，那边不用管）。</li>
 * </ol>
 *
 * @author caijun
 */
final class DemoSeedSupport {

    /** 指纹分隔符用控制字符，避免和模板/判定里的可见字符（含 {@code |}）撞车。 */
    private static final String SEP = "\u0001";

    private DemoSeedSupport() {
    }

    /**
     * 库里那行与代码期望值是否一致。
     *
     * <p>比的是 {@link #fingerprint} 拼出来的整串，而不是 12 个字段各写一遍 {@code if}：加字段时只改一处。</p>
     */
    static boolean matches(IfmapConfig row, IfmapConfig desired) {
        return fingerprint(row).equals(fingerprint(desired));
    }

    /**
     * 把代码里的期望值覆盖回库里那行，让演示重新可用。
     *
     * @return {@code true} 表示确实覆盖过（调用方据此决定打 info 还是 warn）；{@code false} 表示本来就一致
     * @throws IllegalStateException 乐观锁冲突或那行已被软删（演示场景下直接失败比带病跑更好）
     */
    static boolean alignConfig(JdbcConfigWriter writer, IfmapConfig row, IfmapConfig desired) {
        if (matches(row, desired)) {
            return false;
        }
        copyOwned(row, desired);
        int version = row.getVersion() == null ? 0 : row.getVersion().intValue();
        // 「demo」是操作人、「demo-seed」是请求号：会进 config_history，能看出这行是演示自己改的
        if (!writer.update(row, version, "demo", "demo-seed")) {
            throw new IllegalStateException(
                    "演示配置对齐失败（乐观锁冲突或已被软删）：keyId=" + row.getKeyId());
        }
        return true;
    }

    /**
     * 演示自己拥有的配置字段（就这 12 个）从 {@code source} 拷到 {@code target}。
     *
     * <p>刻意<b>不</b>整体替换成新对象：{@code writer.update} 会把所有列都写一遍，
     * 新对象里 {@code project_code} / {@code judge_name} / {@code remark} 全是 {@code null}，
     * 会把演示行上已经填过的这些列擦掉。</p>
     */
    static void copyOwned(IfmapConfig target, IfmapConfig source) {
        target.setInterfaceCode(source.getInterfaceCode());
        target.setInterfaceName(source.getInterfaceName());
        target.setPartnerCode(source.getPartnerCode());
        target.setPartnerName(source.getPartnerName());
        target.setInterfaceOrder(source.getInterfaceOrder());
        target.setCallDirection(source.getCallDirection());
        target.setRequestParamTemplate(source.getRequestParamTemplate());
        target.setResponseParamTemplate(source.getResponseParamTemplate());
        target.setResultJudge(source.getResultJudge());
        target.setPreStrategyName(source.getPreStrategyName());
        target.setPostStrategyName(source.getPostStrategyName());
        target.setStatus(source.getStatus());
    }

    /**
     * 按{@code logic_branch_name}「有则改、无则插」，返回分支的 {@code key_id}。
     *
     * <p>为什么不直接 {@code writer.insert}：分支表在 (tenant, interface_no, 名称) 上有唯一语义，
     * 重复插会撞键；而只「查空就跳过」又会把<b>旧的 judge</b> 留在库里 —— 演示改了判定却在跑老逻辑，
     * 比直接报错更难查。</p>
     */
    static long upsertBranch(ConfigRepository repository, JdbcConfigWriter writer, String tenantId,
                             LogicBranchConfig desired) {
        for (LogicBranchConfig row : repository.queryLogicBranches(tenantId, desired.getInterfaceNo())) {
            if (!desired.getLogicBranchName().equals(row.getLogicBranchName())) {
                continue;
            }
            row.setMethodFlag(desired.getMethodFlag());
            row.setBranchJudge(desired.getBranchJudge());
            row.setLogicBranchOrder(desired.getLogicBranchOrder());
            writer.update(row);
            return row.getKeyId() == null ? -1L : row.getKeyId().longValue();
        }
        return writer.insert(desired);
    }

    /**
     * 清掉该接口的分支缓存。
     *
     * <p>{@link ConfigRepository} 接口上没有 {@code invalidate}（缓存是装饰器的事），所以这里按类型探测；
     * 宿主没装缓存装饰器时什么也不做。</p>
     */
    static void invalidateBranches(ConfigRepository repository, String tenantId, String interfaceNo) {
        if (repository instanceof CachingConfigRepository) {
            ((CachingConfigRepository) repository).invalidate(tenantId, interfaceNo);
        }
    }

    /** 演示拥有的 12 个字段拼成一个指纹（{@code null} 与空串按不同值处理，因为模板留空是有意义的）。 */
    private static String fingerprint(IfmapConfig c) {
        return String.join(SEP,
                nz(c.getInterfaceCode()), nz(c.getInterfaceName()),
                nz(c.getPartnerCode()), nz(c.getPartnerName()),
                String.valueOf(c.getInterfaceOrder()), String.valueOf(c.getCallDirection()),
                nz(c.getRequestParamTemplate()), nz(c.getResponseParamTemplate()),
                nz(c.getResultJudge()), nz(c.getPreStrategyName()), nz(c.getPostStrategyName()),
                String.valueOf(c.getStatus()));
    }

    private static String nz(String value) {
        return value == null ? "<null>" : value;
    }
}
