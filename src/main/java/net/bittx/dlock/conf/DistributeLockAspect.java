package net.bittx.dlock.conf;

import net.bittx.dlock.anno.DistributeLock;
import net.bittx.dlock.enums.ErrorCodeEnum;
import net.bittx.dlock.model.BaseResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.*;

/**
 *  The Aspect that checks how long a method takes must be the last in the "chain" of aspects.
 *  I have used the @Order annotation on the Aspect to make it the last one to be executed.
 *
 *  If the Aspect is not the last to be executed, the new Thread is not able to access
 *  the ThreadLocal variable containing the ExposeInvocationInterceptor class.
 *
 * @see <a href="https://stackoverflow.com/questions/7147031/spring-aspect-fails-when-join-point-is-invoked-in-new-thread">Spring Aspect fails when join point is invoked in new thread</a>
 */
@Order
@Aspect
@Component

/**
 * DistributeLockAspect implement of redis.
 *
 * Note: this implement only for single node redis server.
 *
 * @see <a href="https://dbaplus.cn/news-21-1110-1.html">基于Redis的分布式锁真的安全吗？（上）</a>
 * @see <a href="https://redis.io/topics/distlock">Redis Documentation: distlock</a>
 *
 */
public class DistributeLockAspect {
    private static final Logger log = LoggerFactory.getLogger(DistributeLockAspect.class);

    /**
     * 自定义的线程池
     */
    /*private static ExecutorService executor = new ThreadPoolExecutor(
            10,
            15,
            2000,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(12),
            new AsyncThreadFactory());*/

    ExecutorService service = Executors.newSingleThreadExecutor();

    @Pointcut("execution(* net.bittx.dlock.controller..*.*(..))")
    public void distribute() {}

    @Around(value="distribute() && @annotation(distributeLock)")
    public Object doAround(ProceedingJoinPoint joinPoint, DistributeLock distributeLock) throws Exception {
        String key = annotationResolver.resolver(joinPoint,distributeLock);
        String lockKey = getLock(key, distributeLock.timeout(), distributeLock.timeUnit());
        if (null == lockKey) {
            // Failed to get D-Lock
            return BaseResponse.addError(ErrorCodeEnum.OPERATE_FAILED, "请勿频繁操作");
        }

        System.out.println("Around thread id is : " + Thread.currentThread().getId());

        Callable<Object> callable = new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Object result = null;
                try {
                    System.out.println("Inner call thread id:" + Thread.currentThread().getId());
                    result = joinPoint.proceed();
                    if (result instanceof Future) {
                        return ((Future<?>) result).get();
                    }
                } catch (Throwable throwable) {
                    log.warn("Failed to execute proceed()!");
                    // Failed to execute proceed()
                    return BaseResponse.addError(ErrorCodeEnum.SYSTEM_ERROR, "系统异常");
                }finally {
                    unlock(key,lockKey);
                }
                return result;
            }
        };

        Future<Object> future = service.submit(callable);

        // stolen time here

        Object rtn = BaseResponse.addError(ErrorCodeEnum.SYSTEM_ERROR, "系统异常:锁已过期");

        try {
            rtn = future.get(distributeLock.timeout(), distributeLock.timeUnit());
        } catch (InterruptedException  | ExecutionException | TimeoutException e) {
            log.warn("Cancel the task!!");
            future.cancel(true);
        }
        return rtn;
    }



    private static final Charset utf8 = Charset.forName("UTF-8");
    private static final String UNLOCK_LUA = "if redis.call(\"get\",KEYS[1]) == ARGV[1] then\n"+
            "    return redis.call(\"del\",KEYS[1])\n"+
            "else\n"+
            "    return 0\n"+
            "end";

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private AnnotationResolver annotationResolver;

    /**
     * Return the locked key if success or null otherwise.
     *
     * Redis客户端为了获取锁，向Redis节点发送如下命令
        <pre>
            SET resource_name my_random_value NX PX 30000
        </pre>

     * 上面的命令如果执行成功，则客户端成功获取到了锁，接下来就可以访问共享资源了，
     * 而如果上面的命令执行失败，则说明获取锁失败。
     *
     * 注意：
     * 在上面的SET命令中：
     * <ul>
     *     <li>
     *         my_random_value是由客户端生成的一个随机字符串，它要保证在足够长的一段时间
     *         内在所有客户端的所有获取锁的请求都是唯一的。
     *     </li>
     *
     *     <li>
     *         NX (SetOption.SET_IF_ABSENT)表示只有当resource_named对应的key值不存在的时候才能SET成功。这保证了只有第一个请求
     *         客户端才能获取锁，而其它客户端在锁释放之前都无法获得锁。
     *     </li>
     *     <li>
     *         PX 30000 表示这个锁有一个30秒的自动过期时间。当然，这里30秒只是一个例子，客户端可以选择合适
     *         的过期时间。
     *     </li>
     * </ul>
     *
     * 设置一个随机字符串my_random_value是很有必要的，它保证了一个客户端释放的锁必须是自己持有的那个锁。
     * 假如获取锁时SET的不是一个随机字符串，而是一个固定值，那么可能会发生下面的执行序列：
     *
     * 1. 客户端1获取锁成功
     * 2. 客户端1在某个操作上阻塞了很长时间
     * 3. 过期时间到了，锁自动释放了
     * 4. 客户端2获取到了对应同一个资源的锁
     * 5. 客户端1从阻塞中恢复过来，释放掉了客户端2持有的锁
     *
     * 之后，客户端2在访问共享资源的时候，就没有锁为它提供保护了。
     *
     * @param key
     * @param timeout
     * @param timeUnit
     * @return
     */
    private String getLock(String key, long timeout, TimeUnit timeUnit) {
        try {
            ThreadLocalRandom rdm = ThreadLocalRandom.current();
            String value = new UUID(rdm.nextInt(), rdm.nextInt()).toString();
            Boolean lockStat = stringRedisTemplate.execute((RedisCallback<Boolean>) connection ->
                    connection.set(key.getBytes(utf8), value.getBytes(utf8),
                            Expiration.from(timeout, timeUnit),
                            RedisStringCommands.SetOption.SET_IF_ABSENT));
            if (log.isDebugEnabled()) {
                log.debug("set NX key:{} value:{}",key,value);
            }
            if (!lockStat) {
                // Fail to get lock.
                return null;
            }
            return value;
        } catch (Exception e) {
            log.warn("Failed to get distribute lock key = {}", key, e);
            return null;
        }
    }

    /**
     * 当客户端完成了对共享资源的操作之后，执行下面的Redis Lua脚本来释放锁
     *
     * <pre>

        if redis.call("get",KEYS[1]) == ARGV[1] then
            return redis.call("del",KEYS[1])
        else
            return 0
        end
     * </pre>
     *
     * 这段Lua脚本在执行的时候要把前面的my_random_value作为ARGV[1]的值传进去，把resource_name作为KEYS[1]的值传进去。
     *
     *
     * <strong>释放锁的操作必须使用Lua脚本来实现</strong>
     * 释放锁其实包含三步操作：'GET'、判断和'DEL'，用Lua脚本来实现能保证这三步的原子性。
     * 否则，如果把这三步操作放到客户端逻辑中去执行的话，就有可能发生与前面第三个问题类似的执行序列：
     *
     * 1. 客户端1获取锁成功
     * 2. 客户端1访问共享资源
     * 3. 客户端1为了释放锁，先执行'GET'操作获取随机字符串的值
     * 4. 客户端1判断随机字符串的值，与预期的值相等
     * 5. 客户端1由于某个原因阻塞住了很长时间
     * 6. 过期时间到了，锁自动释放了
     * 7. 客户端2获取到了对应同一个资源的锁
     * 8. 客户端1从阻塞中恢复过来，执行DEL操纵，释放掉了客户端2持有的锁
     *
     * @param key
     * @param value
     */
    private void unlock(String key, String value) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("unlock the key: {} and value is:{}", key,value);
            }
            boolean unLockStat = stringRedisTemplate.execute((RedisCallback<Boolean>)connection ->
                    connection.eval(UNLOCK_LUA.getBytes(), ReturnType.BOOLEAN, 1,
                            key.getBytes(utf8), value.getBytes(utf8)));
            if (!unLockStat) {
                log.warn("Failed to unlock the distribute lock key {}, please unlock it manually.", key);
            }

        } catch (Exception e) {
            log.error("Failed to unlock the distribute lock key {}, please unlock it manually or wait until lock timeout. ", key,e);
        }
    }
}



 /*new Runnable(){
            @Override
            public void run() {
                ExecutorService service = Executors.newSingleThreadExecutor();
                Callable<Object> callable = new Callable<Object>(){
                    public Object call() throws Exception{
                        try {
                            return joinPoint.proceed();
                        } catch (Throwable throwable) {
                            log.error("Failed to execute proceed()!");
                            // Failed to execute proceed()
                            return BaseResponse.addError(ErrorCodeEnum.SYSTEM_ERROR, "系统异常");
                            //return null;
                        }finally {
                            // unlock the D-Lock
                            unlock(key,lockKey);
                        }
                    }
                };

                Future<Object> result = service.submit(callable);
                service.shutdown();

                try {
                    boolean terminated = service.awaitTermination(distributeLock.timeout(), distributeLock.timeUnit());
                    if (!terminated) {
                        service.shutdownNow();
                    }

                } catch (Exception e) {
                    // TODO: ex
                }
            }
        };*/


      /*  try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            log.error("Failed to execute proceed()!");
            // Failed to execute proceed()
            return BaseResponse.addError(ErrorCodeEnum.SYSTEM_ERROR, "系统异常");
            //return null;
        }finally {
            // unlock the D-Lock
            unlock(key,lockKey);
        }*/