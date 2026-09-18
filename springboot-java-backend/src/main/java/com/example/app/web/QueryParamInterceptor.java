package com.example.app.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link AllowedQueryParams}: unknown or repeated parameters are rejected. */
public class QueryParamInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        AllowedQueryParams allowed = method.getMethodAnnotation(AllowedQueryParams.class);
        if (allowed == null) {
            return true;
        }
        Set<String> names = Set.copyOf(Arrays.asList(allowed.value()));
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (!names.contains(parameter.getKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Unknown query parameter: " + sanitize(parameter.getKey())
                                + "; this endpoint accepts " + String.join(", ", sorted(allowed)));
            }
            if (parameter.getValue().length > 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Query parameter given more than once: " + sanitize(parameter.getKey()));
            }
        }
        return true;
    }

    private static List<String> sorted(AllowedQueryParams allowed) {
        return Arrays.stream(allowed.value()).sorted().toList();
    }

    /** The name is echoed back, so it is truncated and stripped of anything that is not printable. */
    private static String sanitize(String name) {
        String shortened = name.length() > 64 ? name.substring(0, 64) : name;
        return shortened.replaceAll("[^\\x20-\\x7E]", "");
    }
}
