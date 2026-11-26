package com.securitymodel.interfaces.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Last HTTP-boundary pass for map/JSON responses. Bean responses are handled by the
 * Jackson property modifier in {@link IdJacksonConfig}; this advice covers containers
 * such as dashboard rows, report maps and assistant tool payloads.
 */
@ControllerAdvice
public class IdResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof Resource || body instanceof byte[] || body instanceof String) {
            return body;
        }
        return IdJsonSupport.normalize(body);
    }
}
