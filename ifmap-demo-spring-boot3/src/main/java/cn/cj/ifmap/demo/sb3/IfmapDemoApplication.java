package cn.cj.ifmap.demo.sb3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ifmap Spring Boot 3 示例应用。
 *
 * <p>启动后：starter 自动建表 → {@link DemoRunner} 写入一条演示配置 → 用引擎渲染报文并打印。
 * 直接运行：{@code mvn -pl ifmap-demo-spring-boot3 spring-boot:run}。</p>
 *
 * @author caijun
 */
@SpringBootApplication
public class IfmapDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(IfmapDemoApplication.class, args);
    }
}
