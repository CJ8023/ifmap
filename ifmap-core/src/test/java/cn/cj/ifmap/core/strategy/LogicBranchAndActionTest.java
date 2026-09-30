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
package cn.cj.ifmap.core.strategy;

import cn.cj.ifmap.core.exception.IfmapStrategyException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 逻辑分支注册表 + 动作注册表（含"未注册不静默"）。 */
class LogicBranchAndActionTest {

    @LogicBranch("repayDone")
    public static class RepayDoneBranch implements LogicBranchStrategy {
        @Override
        public boolean match(StrategyContext context) {
            return "1".equals(context.get("repayFlag"));
        }
    }

    public static class NoAnnotationBranch implements LogicBranchStrategy {
        @Override
        public boolean match(StrategyContext context) {
            return true;
        }
    }

    @IfmapAction("repayAction")
    public static class RepayAction implements IfmapActionHandler {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public void execute(StrategyContext context) {
            CALLS.incrementAndGet();
            context.putParam("repayed", Boolean.TRUE);
        }
    }

    public static class NoAnnotationAction implements IfmapActionHandler {
        @Override
        public void execute(StrategyContext context) {
        }
    }

    @Test
    void registersByAnnotationAndMatches() {
        LogicBranchStrategyRegistry registry = new LogicBranchStrategyRegistry();
        registry.register(new RepayDoneBranch());

        assertEquals(1, registry.size());
        assertTrue(registry.matches("repayDone", context("1")));
        assertFalse(registry.matches("repayDone", context("0")));
        assertFalse(registry.matches("unknownFlag", context("1")));
    }

    @Test
    void missingAnnotationIsRejected() {
        LogicBranchStrategyRegistry registry = new LogicBranchStrategyRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(new NoAnnotationBranch()));
    }

    @Test
    void actionExecutesAndMutatesContext() {
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RepayAction());

        StrategyContext context = context("1");
        actions.execute("repayAction", context);

        assertEquals(1, RepayAction.CALLS.get());
        assertEquals(Boolean.TRUE, context.get("repayed"));
        assertTrue(actions.contains("repayAction"));
    }

    @Test
    void missingActionThrowsByDefault() {
        ActionRegistry actions = new ActionRegistry();
        assertThrows(IfmapStrategyException.class, () -> actions.execute("nope", context("1")));
    }

    @Test
    void missingActionCanBeTolerated() {
        ActionRegistry actions = new ActionRegistry();
        actions.setFailOnMissingAction(false);
        actions.execute("nope", context("1"));
        assertFalse(actions.isFailOnMissingAction());
    }

    @Test
    void emptyActionKeyAlwaysThrows() {
        ActionRegistry actions = new ActionRegistry();
        actions.setFailOnMissingAction(false);
        assertThrows(IfmapStrategyException.class, () -> actions.execute("", context("1")));
    }

    private static StrategyContext context(String repayFlag) {
        return StrategyContext.builder().build().putParam("repayFlag", repayFlag);
    }
}
