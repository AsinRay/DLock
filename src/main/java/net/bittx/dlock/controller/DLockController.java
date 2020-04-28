package net.bittx.dlock.controller;

import net.bittx.dlock.anno.DistributeLock;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("dlock")
public class DLockController {

    @RequestMapping("test")
    @DistributeLock
    public Object dLockTest(){

        return " cc-dd d lock test.";
    }

    /**
     * 测试分布式锁的实现.
     * @DistributeLock可以不用设置 key，此时的key默认是此方法的
     * 完全限定名称+参数。timeout 默认为 5min.
     *
     * 如果需要设置 key 请您保证此key在系统中的唯一性.
     * 如果需要设置 timeout, 请您保证在此timeout时间长度内，使用此注解的方法
     * 可以执行完成。
     *
     * @return
     * @throws InterruptedException
     */
    @RequestMapping("t")
    @DistributeLock(key = "asdfasmmm",timeout = 15000)
    public Object dlockTest() throws InterruptedException {
        System.out.println("start running method...");
        System.out.println("Controller thread id is:" + Thread.currentThread().getId());
        Thread.currentThread().sleep(3000);
        System.out.println("end running method...");
        return " X: timeout supported d lock test.";
    }
}
