package com.luxera.agentserver.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * {@code SimulatorAccessPort} 的 HTTP 适配器 —— 数字人拿自己"手机"的短期访问令牌。
 *
 * <p>语义与 G2 占位一致: 拿不到 token → {@code Optional.empty()}。
 * ChatSimulatorConnector 对这个答案的既有处理就是"拿不到 token 不连" —— 链路安全,
 * chat 平台缺席时仿真连接器安静地不上线, 生命周期照常跑。
 */
@Slf4j
public class HttpSimulatorAccessAdapter extends HttpClientSupport
        implements SimulatorAccessPort {

    public HttpSimulatorAccessAdapter(String baseUrl, String serviceKey,
                                      ObjectMapper objectMapper, int timeoutMillis) {
        super(baseUrl, serviceKey, objectMapper, timeoutMillis);
    }

    @Override protected org.slf4j.Logger log() { return log; }

    @Override
    public Optional<String> refreshToken(String deviceId, String secret) {
        if (deviceId == null || secret == null) return Optional.empty();
        try {
            Map<?, ?> resp = post("/internal/simulator/refresh-token",
                    Map.of("deviceId", deviceId, "secret", secret), Map.class);
            if (resp == null || resp.get("token") == null) return Optional.empty();
            return Optional.of(String.valueOf(resp.get("token")));
        } catch (RuntimeException e) {
            // 与 G2 占位同判据: 设备令牌由 chat 平台签发; 平台缺席时设备一概未知
            log.debug("[SimulatorAccess] token 刷新失败(设备暂不可用): device={}, error={}",
                    deviceId, e.getMessage());
            return Optional.empty();
        }
    }
}
