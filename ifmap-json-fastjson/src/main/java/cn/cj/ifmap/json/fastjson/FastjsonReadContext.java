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
package cn.cj.ifmap.json.fastjson;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonReadContext;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 基于 fastjson {@code JSONPath} 的读取上下文：解析一次、多次取数。
 *
 * <p>与 Jackson 实现（jayway json-path）的两处语义差异在此抹平，其余保持 fastjson 原生行为：</p>
 * <ol>
 *   <li><b>路径不存在 → null</b>：fastjson 本就返回 null（jayway 会抛 PathNotFoundException，由 Jackson 实现接住），
 *       所以这里无需特判；</li>
 *   <li><b>通配 / 过滤器不命中 → 空列表</b>：fastjson 返回 null，这里按 {@link FastjsonPaths#isMultiValue(String)}
 *       转成空列表，与 Jackson 实现口径一致（调用列表类规则时不该拿到 null）。</li>
 * </ol>
 *
 * <p>路径语法错误（如 {@code $[?(}）会抛 {@link IfmapConfigException}，不允许静默当成「路径不存在」。</p>
 *
 * @author caijun
 */
final class FastjsonReadContext implements JsonReadContext {

    private final String json;
    private Object root;
    private boolean parsed;

    FastjsonReadContext(String json) {
        // 源报文为 null 按空对象处理（与 Jackson 实现一致）
        this.json = json == null ? "{}" : json;
    }

    @Override
    public Object read(String path) {
        return read(path, false);
    }

    @Override
    public Object read(String path, boolean leafToNull) {
        if (FastjsonPaths.isBlank(path)) {
            return null;
        }
        Object node;
        try {
            Object document = root();
            node = JSONPath.eval(document, path);
            if (leafToNull) {
                node = padMissingLeaves(document, path, node);
            }
        } catch (RuntimeException e) {
            // 与 Jackson 实现同一口径：只有「路径不存在」算正常空值，其余（表达式非法、源报文非法）显式失败，
            // 避免又回到存量引擎「静默取空值」的老路
            throw new IfmapConfigException("JsonPath [" + path + "] 求值失败：" + e.getMessage(), e);
        }
        if (node == null && FastjsonPaths.isMultiValue(path)) {
            return new ArrayList<Object>();
        }
        return FastjsonValues.toJdk(node);
    }

    private Object root() {
        if (!parsed) {
            Object value = FastjsonValues.parseDocument(json);
            root = value == null ? new JSONObject(true) : value;
            parsed = true;
        }
        return root;
    }

    /**
     * leafToNull 补偿：fastjson 的 JSONPath 没有 jayway 的 {@code DEFAULT_PATH_LEAF_TO_NULL}，
     * 通配路径上缺失的叶子会被**跳过**（列表变短、与父节点下标错位）。
     *
     * <p>只对「有界多值段 + 末段属性名」形状补偿：按前缀取到父节点集合，逐个重新取叶子、缺失补 null。
     * 数量一致时直接原样返回（绝大多数报文走这条路，不做多余求值）。</p>
     */
    private static Object padMissingLeaves(Object root, String path, Object node) {
        if (!(node instanceof Collection)) {
            return node;
        }
        String prefix = FastjsonPaths.boundedMultiValuePrefix(path);
        if (prefix == null) {
            return node;
        }
        Object parents = JSONPath.eval(root, prefix);
        if (!(parents instanceof Collection)) {
            return node;
        }
        Collection<?> parentList = (Collection<?>) parents;
        if (parentList.size() == ((Collection<?>) node).size()) {
            return node;
        }
        String leafPath = "$." + path.substring(prefix.length() + 1);
        List<Object> padded = new ArrayList<Object>(parentList.size());
        for (Object parent : parentList) {
            padded.add(JSONPath.eval(parent, leafPath));
        }
        return padded;
    }
}
