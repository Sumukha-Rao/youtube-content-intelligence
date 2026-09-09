package com.yci.security;

import java.lang.annotation.*;

/**
 * Injects the authenticated user's id into a controller method parameter.
 * Resolved by {@link CurrentUserArgumentResolver} from the request attribute the
 * {@link AuthInterceptor} sets, so a controller can never read it from a
 * client-supplied header.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
