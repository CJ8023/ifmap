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
package cn.cj.ifmap.core.spi;

/**
 * 主键生成 SPI。
 *
 * <p>starter 必须自带生成能力，否则"自带建表"无法闭环（原实现依赖外部服务生成 ID）。
 * 默认实现为 {@link SnowflakeIdGenerator}；若建表时使用 {@code AUTO_INCREMENT}，
 * 可提供返回 {@code null} 的实现让数据库生成。</p>
 *
 * @author caijun
 */
public interface IdGenerator {

    /** 生成下一个主键；返回 {@code null} 表示交给数据库生成。 */
    Long nextId();

    /** 库自增策略：不生成 ID，由数据库负责。 */
    IdGenerator AUTO_INCREMENT = () -> null;
}
