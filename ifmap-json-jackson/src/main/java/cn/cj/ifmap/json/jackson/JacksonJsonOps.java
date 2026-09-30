package cn.cj.ifmap.json.jackson;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Jackson + JsonPath 的 {@link JsonOps} 默认实现。
 *
 * <p>通过 {@code META-INF/services} 自动发现；也可 {@code new JacksonJsonOps()} 手动注入。</p>
 *
 * <p>注意：{@code JacksonJsonProvider} 默认把 JSON 解析成 JDK 原生结构（{@code Map} / {@code List} /
 * {@code String} / {@code Integer} / {@code Double}），并不一定返回 {@code JsonNode}，
 * 所以取值后必须做一次「两种形态都兼容」的归一化，否则会在类型转换上静默取空值。</p>
 *
 * @author caijun
 */
public final class JacksonJsonOps implements JsonOps {

    private static final Logger log = LoggerFactory.getLogger(JacksonJsonOps.class);

    private static final Option[] NO_OPTION = new Option[0];
    private static final Option[] LEAF_TO_NULL = new Option[]{Option.DEFAULT_PATH_LEAF_TO_NULL};

    private final ObjectMapper mapper;
    private final Configuration strictConfiguration;
    private final Configuration leafToNullConfiguration;

    public JacksonJsonOps() {
        this(defaultMapper());
    }

    public JacksonJsonOps(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.mapper = mapper;
        this.strictConfiguration = configuration(mapper, NO_OPTION);
        this.leafToNullConfiguration = configuration(mapper, LEAF_TO_NULL);
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }

    private static Configuration configuration(ObjectMapper mapper, Option[] options) {
        return Configuration.builder()
                .jsonProvider(new JacksonJsonProvider(mapper))
                .mappingProvider(new JacksonMappingProvider(mapper))
                .options(options)
                .build();
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    @Override
    public String name() {
        return "jackson";
    }

    @Override
    public Object parse(String json) {
        if (json == null || json.trim().length() == 0) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public JsonReadContext readContext(String json) {
        return new JacksonReadContext(json == null ? "{}" : json, strictConfiguration, leafToNullConfiguration);
    }

    @Override
    public boolean isJson(String text) {
        if (text == null || text.trim().length() == 0) {
            return false;
        }
        try {
            mapper.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isValidPath(String path) {
        if (path == null || path.trim().length() == 0) {
            return false;
        }
        try {
            JsonPath.compile(path.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 一次解析、多次取数。 */
    private static final class JacksonReadContext implements JsonReadContext {

        private final String json;
        private final Configuration strictConfiguration;
        private final Configuration leafToNullConfiguration;
        private DocumentContext strictContext;
        private DocumentContext leafToNullContext;

        JacksonReadContext(String json, Configuration strictConfiguration, Configuration leafToNullConfiguration) {
            this.json = json;
            this.strictConfiguration = strictConfiguration;
            this.leafToNullConfiguration = leafToNullConfiguration;
        }

        @Override
        public Object read(String path) {
            return read(path, false);
        }

        @Override
        public Object read(String path, boolean leafToNull) {
            if (path == null || path.trim().length() == 0) {
                return null;
            }
            Object node;
            try {
                node = context(leafToNull).read(path);
            } catch (PathNotFoundException e) {
                log.debug("JsonPath [{}] 不存在", path);
                return null;
            } catch (Exception e) {
                // 只有「路径不存在」算正常空值；路径语法错误、源报文非法等一律显式失败，
                // 避免又回到存量引擎「静默取空值」的老路
                throw new IfmapConfigException("JsonPath [" + path + "] 求值失败：" + e.getMessage(), e);
            }
            return node instanceof JsonNode ? JsonNodes.toJdk((JsonNode) node) : node;
        }

        private DocumentContext context(boolean leafToNull) {
            if (leafToNull) {
                if (leafToNullContext == null) {
                    leafToNullContext = JsonPath.using(leafToNullConfiguration).parse(json);
                }
                return leafToNullContext;
            }
            if (strictContext == null) {
                strictContext = JsonPath.using(strictConfiguration).parse(json);
            }
            return strictContext;
        }
    }
}
