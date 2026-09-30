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
package cn.cj.ifmap.remote;

import java.util.Map;

/**
 * 远端调用出口：配置不在本库、而在另一个服务时的唯一依赖点。
 *
 * <p><b>为什么是函数式 SPI 而不是直接依赖 Spring Cloud OpenFeign</b>：</p>
 * <ol>
 *   <li>Feign 的 BOM 版本必须与宿主的 Spring Boot 版本对齐，一个库把「Spring Cloud 版本矩阵」
 *       强加给宿主，升级时会变成宿主的麻烦；</li>
 *   <li>鉴权、超时、重试、链路透传（比如 ECC 的 {@code ApiReq} 请求头）本来就在宿主自己的
 *       Feign 客户端 / {@code RestTemplate} / {@code RestClient} 里配好了，本模块不该重复一份；</li>
 *   <li>本模块因此可以保持 <b>Java 8 + 零 Spring 依赖</b>，SB2/SB3/非 Spring 宿主都能用。</li>
 * </ol>
 *
 * <p>宿主侧实现示例（Feign 与 RestTemplate 各一）：</p>
 * <pre>{@code
 * // ① Feign：客户端方法把返回值声明为 String 就能拿到响应体原文（不要声明成对象），
 * //    查询参数显式列在方法签名上；ApiReq 之类的请求头在 @FeignClient 上统一加。
 * @FeignClient(name = "financing-scheme")
 * interface BankintApi {
 *     @GetMapping("/api/bankint/query_bankint_config_list")
 *     String queryConfigList(@RequestParam("interfaceNo") String interfaceNo,
 *                            @RequestParam("busiNode") String busiNode);
 *
 *     @PostMapping("/api/bankint/insert_log")
 *     String insertLog(@RequestBody String json);
 * }
 *
 * // ② RestTemplate：把 uri 拼到基础地址后面即可。
 * ConfigFetcher fetcher = new ConfigFetcher() {
 *     public String get(String uri, Map<String, String> query) {
 *         return restTemplate.getForObject(baseUrl + uri + queryString(query), String.class);
 *     }
 *     public String postJson(String uri, String jsonBody) {
 *         HttpHeaders headers = new HttpHeaders();
 *         headers.setContentType(MediaType.APPLICATION_JSON);
 *         return restTemplate.postForObject(baseUrl + uri, new HttpEntity<>(jsonBody, headers), String.class);
 *     }
 * };
 * }</pre>
 *
 * <p><b>实现方约定</b>：</p>
 * <ul>
 *   <li>返回<b>响应体原文</b>（JSON 文本）；查不到数据时返回 {@code null} 或空串都行，本模块按空结果处理；</li>
 *   <li>非 2xx、超时、连接失败等<b>必须抛异常</b>（不要吞掉后返回空串）—— 否则线上会表现为
 *       「配置莫名变空 / 日志莫名丢失」，比直接失败难查得多；</li>
 *   <li>本模块不重试、不降级：要不要重试是宿主的可控决策。</li>
 * </ul>
 *
 * @author caijun
 */
public interface ConfigFetcher {

    /**
     * GET 请求。
     *
     * @param uri   路径（已由 {@link RemoteConfigRepository} 拼好，形如 {@code /api/bankint/query_bankint_config_list}）
     * @param query 查询参数（值已过滤掉 null/空白项，可能为空 Map，不会为 null）
     * @return 响应体原文，可为 null
     */
    String get(String uri, Map<String, String> query);

    /**
     * POST JSON 请求。
     *
     * @param uri      路径（形如 {@code /api/bankint/insert_log}）
     * @param jsonBody 请求体（JSON 文本，由 {@code JsonOps.toJson} 生成）
     * @return 响应体原文，可为 null
     */
    String postJson(String uri, String jsonBody);
}
