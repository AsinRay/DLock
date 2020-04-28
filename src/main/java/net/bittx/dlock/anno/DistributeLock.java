package net.bittx.dlock.anno;


import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributeLock {

    String key() default "";

    int timeout() default 300000;

    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
}
