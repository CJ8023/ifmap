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
