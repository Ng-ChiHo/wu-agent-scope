package com.chiho.wuagentscope.config;

import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 配置
 * <p>
 * 初始化 OTel SDK，注册 OTLP gRPC 导出器（发往 Jaeger）。
 * OtelTracingMiddleware 通过 GlobalOpenTelemetry.getTracer() 获取 tracer。
 *
 * @author ChiHo
 */
@Configuration
@Slf4j
public class OtelConfig {

    @Value("${otel.exporter.endpoint:http://localhost:4317}")
    private String exporterEndpoint;

    @Value("${otel.service.name:wu-agent-scope}")
    private String serviceName;

    @Value("${otel.exporter.enabled:true}")
    private boolean exporterEnabled;

    private OpenTelemetrySdk sdkInstance;

    @Bean
    public OpenTelemetry openTelemetry() {
        if (!exporterEnabled) {
            log.info("OTel 导出已禁用，使用 no-op 实例");
            return OpenTelemetry.noop();
        }

        log.info("正在初始化 OpenTelemetry SDK，endpoint={}", exporterEndpoint);

        Resource resource = Resource.getDefault()
                .merge(Resource.builder()
                        .put("service.name", serviceName)
                        .build());

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(exporterEndpoint)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .build();

        sdkInstance = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        // 注册为全局实例，OtelTracingMiddleware 通过 GlobalOpenTelemetry.getTracer() 获取
        GlobalOpenTelemetry.set(sdkInstance);
        log.info("OpenTelemetry SDK 已注册全局实例，OTLP endpoint={}", exporterEndpoint);

        return sdkInstance;
    }

    /**
     * 注册 AgentScope 的 OTel 追踪中间件
     * <p>
     * OtelTracingMiddleware 通过 GlobalOpenTelemetry.getTracer() 获取 tracer，
     * 必须在 openTelemetry() Bean 注册全局实例之后创建。
     */
    @Bean
    public OtelTracingMiddleware otelTracingMiddleware() {
        return new OtelTracingMiddleware();
    }

    @PreDestroy
    public void shutdown() {
        if (sdkInstance != null) {
            log.info("关闭 OpenTelemetry SDK");
            sdkInstance.getSdkTracerProvider().close();
        }
    }
}
