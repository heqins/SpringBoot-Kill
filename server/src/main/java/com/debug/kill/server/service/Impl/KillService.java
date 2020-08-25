package com.debug.kill.server.service.Impl;

//import com.debug.kill.model.dto.KillSuccessUserInfo;
import com.debug.kill.model.entity.ItemKill;
import com.debug.kill.model.entity.ItemKillSuccess;
import com.debug.kill.model.mapper.ItemKillMapper;
import com.debug.kill.model.mapper.ItemKillSuccessMapper;
//import com.debug.kill.server.enums.SysConstant;
import com.debug.kill.server.enums.SysConstant;
import com.debug.kill.server.service.IKillService;
//import com.debug.kill.server.service.RabbitSenderService;
import com.debug.kill.server.service.RabbitSenderService;
import com.debug.kill.server.utils.RandomUtil;
//import com.debug.kill.server.utils.SnowFlake;
import com.debug.kill.server.utils.SnowFlake;
import com.google.common.collect.Maps;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.joda.time.DateTime;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @Author:debug (SteadyJack)
 * @Date: 2019/6/17 22:21
 **/
@Service
public class KillService implements IKillService {

    private static final Logger log = LoggerFactory.getLogger(KillService.class);

    private SnowFlake snowFlake=new SnowFlake(2,3);

    @Autowired
    private ItemKillSuccessMapper itemKillSuccessMapper;

    @Autowired
    private ItemKillMapper itemKillMapper;

    @Autowired
    private RabbitSenderService rabbitSenderService;

    @Autowired
    private Environment env;

    /**
     * 商品秒杀核心业务逻辑的处理
     *
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Override
    public Boolean killItem(Integer killId, Integer userId) throws Exception {
        Boolean result = false;

        //TODO:判断当前用户是否已经抢购了当前商品
        int test = itemKillSuccessMapper.countByKillUserId(killId, userId);
        if (itemKillSuccessMapper.countByKillUserId(killId, userId) <= 0) {
            //TODO:判断当前代抢购的商品库存是否充足、以及是否出在可抢的时间段内 - canKill
            ItemKill itemKill = itemKillMapper.selectById(killId);
            if (itemKill != null && 1 == itemKill.getCanKill()) {

                //TODO:扣减库存-减1
                int res = itemKillMapper.updateKillItem(killId);
                if (res > 0) {
                    //TODO:判断是否扣减成功了?是-生成秒杀成功的订单、同时通知用户秒杀已经成功（在一个通用的方法里面实现）
                    this.commonRecordKillSuccessInfo(itemKill, userId);

                    result = true;
                }
            }
        } else {
            throw new Exception("您已经抢购过该商品了！");
        }

        return result;
    }

    /**
     * 商品秒杀核心业务逻辑的处理-mysql的优化
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Override
    public Boolean killItemV2(Integer killId, Integer userId) throws Exception {
        Boolean result=false;

        //TODO:判断当前用户是否已经抢购过当前商品
        if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){
            //TODO:A.查询待秒杀商品详情
            ItemKill itemKill=itemKillMapper.selectByIdV2(killId);

            //TODO:判断是否可以被秒杀canKill=1?
            // 在这里多线程并发安全问题：数据库更新操作时没有对商品剩余数量进行验证，产生了超卖问题
            // 产生的多个线程对“同一段操作共享数据的操作代码”进行并发操作，从而出现并发安全的问题
            // 核心方案： 分布式锁解决共享资源在高并发访问时出现的“并发安全”问题
            // 协助方案： 对于瞬时流量，并发请求进行限流-用rabbitmq进行接口层面的限流
            if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
            //if (itemKill!=null && 1==itemKill.getCanKill()){
                    //TODO:B.扣减库存-减一
                int res=itemKillMapper.updateKillItemV2(killId);

                //TODO:扣减是否成功?是-生成秒杀成功的订单，同时通知用户秒杀成功的消息
                if (res>0){
                    commonRecordKillSuccessInfo(itemKill,userId);

                    result=true;
                }
            }
        }else{
            throw new Exception("您已经抢购过该商品了!");
        }
        return result;
    }

    /**
     * 通用的方法-记录用户秒杀成功后生成的订单-并进行异步邮件消息的通知
     * @param kill
     * @param userId
     * @throws Exception
     */
    private void commonRecordKillSuccessInfo(ItemKill kill, Integer userId) throws Exception{
        //TODO:记录抢购成功后生成的秒杀订单记录

        ItemKillSuccess entity=new ItemKillSuccess();
        String orderNo=String.valueOf(snowFlake.nextId());

        //entity.setCode(RandomUtil.generateOrderCode());   //传统时间戳+N位随机数
        entity.setCode(orderNo); //雪花算法
        entity.setItemId(kill.getItemId());
        entity.setKillId(kill.getId());
        entity.setUserId(String.valueOf(userId));
        entity.setStatus(SysConstant.OrderStatus.SuccessNotPayed.getCode().byteValue());
        entity.setCreateTime(DateTime.now().toDate());
        //TODO:学以致用，举一反三 -> 仿照单例模式的双重检验锁写法
        if (itemKillSuccessMapper.countByKillUserId(kill.getId(),userId) <= 0){
            int res=itemKillSuccessMapper.insertSelective(entity);

            if (res>0){
                //TODO:进行异步邮件消息的通知=rabbitmq+mail
                rabbitSenderService.sendKillSuccessEmailMsg(orderNo);

                //TODO:入死信队列，用于 “失效” 超过指定的TTL时间时仍然未支付的订单
                /**
                 * 缺陷：有多个订单在某个TTL集中失效，但是恰好RabbitMQ服务挂掉了
                 * ①高可用: RabbitMQ集群
                 * ②监控
                 * 补充解决方案：基于@Scheduled注解的定时任务实现-批量获取status=0的订单并判断时间是否超过了TTL
                 */
                rabbitSenderService.sendKillSuccessOrderExpireMsg(orderNo);
            }
        }
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 商品秒杀核心业务逻辑的处理-redis的分布式锁
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Override
    public Boolean killItemV3(Integer killId, Integer userId) throws Exception {
        Boolean result=false;

        if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){

            //TODO:借助Redis的原子操作实现分布式锁-对共享操作-资源进行控制
            ValueOperations valueOperations=stringRedisTemplate.opsForValue();
            final String key=new StringBuffer().append(killId).append(userId).append("-RedisLock").toString();
            final String value=RandomUtil.generateOrderCode();
            Boolean cacheRes=valueOperations.setIfAbsent(key,value); //lua脚本提供“分布式锁服务”，就可以写在一起
            //TODO:redis部署节点宕机了
            if (cacheRes){
                stringRedisTemplate.expire(key,30, TimeUnit.SECONDS);

                try {
                    ItemKill itemKill=itemKillMapper.selectByIdV2(killId);
                    if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
                        int res=itemKillMapper.updateKillItemV2(killId);
                        if (res>0){
                            commonRecordKillSuccessInfo(itemKill,userId);

                            result=true;
                        }
                    }
                }catch (Exception e){
                    throw new Exception("还没到抢购日期、已过了抢购时间或已被抢购完毕！");
                }finally {
                    if (value.equals(valueOperations.get(key).toString())){
                        stringRedisTemplate.delete(key);
                    }
                }
            }
        }else{
            throw new Exception("Redis-您已经抢购过该商品了!");
        }
        return result;
    }


    @Autowired
    private RedissonClient redissonClient;

    /**
     * 商品秒杀核心业务逻辑的处理-redisson的分布式锁
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Override
    public Boolean killItemV4(Integer killId, Integer userId) throws Exception {
        Boolean result=false;

        final String lockKey=new StringBuffer().append(killId).append(userId).append("-RedissonLock").toString();
        RLock lock=redissonClient.getLock(lockKey);

        try {
            //TODO:第一个参数30s=表示尝试获取分布式锁，并且最大的等待获取锁的时间为30s
            //TODO:第二个参数10s=表示上锁之后，10s内操作完毕将自动释放锁
            Boolean cacheRes=lock.tryLock(30,10,TimeUnit.SECONDS);
            if (cacheRes){
                //TODO:核心业务逻辑的处理
                if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){
                    ItemKill itemKill=itemKillMapper.selectByIdV2(killId);
                    if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
                        int res=itemKillMapper.updateKillItemV2(killId);
                        if (res>0){
                            commonRecordKillSuccessInfo(itemKill,userId);

                            result=true;
                        }
                    }
                }else{
                    //throw new Exception("redisson-您已经抢购过该商品了!");
                    log.error("redisson-您已经抢购过该商品了!");
                }
            }
        }finally {
            //TODO:释放锁
            lock.unlock();
            //lock.forceUnlock();
        }
        return result;
    }
}

