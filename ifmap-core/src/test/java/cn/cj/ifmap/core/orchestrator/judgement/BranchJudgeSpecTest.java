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
package cn.cj.ifmap.core.orchestrator.judgement;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分支判定规格：与 {@code result_judge} 同源（同一解析器、同一深度、同一闭合算子集），
 * 只额外放开两个无 path 的叶子算子。
 *
 * @author caijun
 */
class BranchJudgeSpecTest {

    private final JsonOps jsonOps = new TestJsonOps();

    private List<String> errors(String spec) {
        return BranchJudgeSpec.errorsOf(spec, jsonOps);
    }

    @Test
    void topLevelIsTheSameThreeShapesAsResultJudge() {
        assertTrue(errors("{\"op\":\"always\"}").isEmpty());
        assertTrue(BranchJudgeSpec.isAlways("{\"op\":\"always\"}", jsonOps));
        // 兜底判定靠编译后的根节点，不做字符串比较：大小写与空白形态同样是 always
        assertTrue(BranchJudgeSpec.isAlways("{ \"op\" : \"ALWAYS\" }", jsonOps));
        // 空规格不是兜底（D10）：要么写规格，要么显式写 always
        assertFalse(errors("").isEmpty());
        assertFalse(errors("{}").isEmpty());
        assertFalse(errors("{\"all\":[]}").isEmpty());
        assertFalse(errors("{\"any\":[]}").isEmpty());
        assertFalse(BranchJudgeSpec.isAlways("", jsonOps));
        assertFalse(BranchJudgeSpec.isAlways("{\"any\":[{\"op\":\"strategy\",\"value\":\"X\"}]}", jsonOps));
    }

    @Test
    void branchOnlyOpsNeedNoPathButMustNotCarryOne() {
        assertTrue(errors("{\"any\":[{\"op\":\"strategy\",\"value\":\"PAY_SUCCESS\"}]}").isEmpty());
        assertTrue(errors("{\"any\":[{\"op\":\"judged\",\"value\":[\"SUCCESS\"],\"ignoreCase\":true}]}").isEmpty());
        // 带 path 就变成"到底读原文还是读判定值"的歧义，直接判非法
        assertFalse(errors("{\"any\":[{\"op\":\"strategy\",\"path\":\"$.a\",\"value\":\"X\"}]}").isEmpty());
        assertFalse(errors("{\"any\":[{\"op\":\"judged\",\"path\":\"$.a\",\"value\":[\"X\"]}]}").isEmpty());
        // judged 必须是数组、strategy 必须是标量
        assertFalse(errors("{\"any\":[{\"op\":\"judged\",\"value\":\"SUCCESS\"}]}").isEmpty());
        assertFalse(errors("{\"any\":[{\"op\":\"strategy\",\"value\":[\"X\"]}]}").isEmpty());
        // 分支专用算子也必须带 value
        assertFalse(errors("{\"any\":[{\"op\":\"strategy\"}]}").isEmpty());
        assertFalse(errors("{\"any\":[{\"op\":\"judged\"}]}").isEmpty());
    }

    @Test
    void pathConditionsAndGroupRulesAreSharedWithResultJudge() {
        assertTrue(errors("{\"all\":[{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"SUCCESS\"}]}").isEmpty());
        assertTrue(errors("{\"all\":[{\"path\":\"$.code\",\"op\":\"notBlank\"}]}").isEmpty());
        // 深度上限 3 与 all/any 互斥照旧
        assertFalse(errors("{\"all\":[{\"any\":[{\"all\":[{\"any\":[{\"path\":\"$.a\",\"op\":\"exists\"}]}]}]}]}")
                .isEmpty());
        assertFalse(errors("{\"all\":[{\"path\":\"$.a\",\"op\":\"exists\"}],\"any\":[]}").isEmpty());
        // 未知算子仍然点名
        assertTrue(errors("{\"all\":[{\"path\":\"$.a\",\"op\":\"gt\",\"value\":\"1\"}]}").get(0).contains("op 非法"));
    }

    @Test
    void parseRejectsInvalidAndExposesSpec() {
        assertNotNull(BranchJudgeSpec.parse("{\"op\":\"always\"}", jsonOps));
        boolean thrown = false;
        try {
            BranchJudgeSpec.parse("{\"all\":[]}", jsonOps);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }
}
