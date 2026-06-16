package nz.co.ksktech.ollamamcp.logging;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor binding marking a method whose invocation should be logged with the
 * {@code >>> / <<<} correlation-id convention (see {@link InvocationLoggingInterceptor}). Put it on
 * the {@code @Tool} methods so every MCP tool call is traced in one readable place.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Logged {}
