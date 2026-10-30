package ai.godance.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import ai.godance.domain.UserRole;

/**
 * The roles that may call a route under {@code /v1/admin}.
 * {@link AdminAuthorizationFilter} refuses a route without this annotation,
 * whoever is calling. Methods only: a class-level default would let a new
 * route inherit access nobody chose for it.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminRoles {

    UserRole[] value();
}
