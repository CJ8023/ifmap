package cn.cj.ifmap.core.util;

import cn.cj.ifmap.core.strategy.IfmapAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 注解查找必须"穿透"父类链与接口链 —— Spring CGLIB 代理类的类注解不可见。
 *
 * @author caijun
 */
class AnnotationsTest {

    @IfmapAction("concrete")
    static class Concrete implements Marker {
    }

    static class Child extends Concrete {
    }

    static class NoAnnotation implements Marker {
    }

    interface Marker {
    }

    @IfmapAction("onInterface")
    interface Annotated {
    }

    static class ImplementsAnnotated implements Annotated {
    }

    @Test
    @DisplayName("直接标注：能查到")
    void findsOnClass() {
        assertEquals("concrete", Annotations.find(Concrete.class, IfmapAction.class).value());
    }

    @Test
    @DisplayName("子类未标注：沿父类链查到父类注解（CGLIB 代理即此形态）")
    void findsOnSuperclass() {
        // 模拟 CGLIB 代理：运行期类型是宿主机类的子类，类注解不继承 —— 必须靠父类链查找
        class Proxy extends Concrete {
        }
        assertEquals("concrete", Annotations.find(Proxy.class, IfmapAction.class).value());
    }

    @Test
    @DisplayName("接口上标注：实现类能查到")
    void findsOnInterface() {
        assertEquals("onInterface", Annotations.find(ImplementsAnnotated.class, IfmapAction.class).value());
    }

    @Test
    @DisplayName("都没有时返回 null，不抛异常")
    void returnsNullWhenAbsent() {
        assertNull(Annotations.find(NoAnnotation.class, IfmapAction.class));
        assertNull(Annotations.find(Object.class, IfmapAction.class));
    }
}
