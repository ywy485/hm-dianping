package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //调用防止缓存穿透方法
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    //互斥锁解决缓存击穿方法
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis缓存查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回(把Json解析为POJO)
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if ("".equals(shopJson)) {
            return null;
        }
        //4.实现缓存重建
        //4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2.判断是否获取成功
            if (!isLock) {
                //4.3.失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4.再次检测缓存是否存在，避免在拿锁之前被其他线程更新缓存
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson2)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if ("".equals(shopJson2)) {
                return null;
            }
            //4.5.成功则去数据库中根据id查询
            shop = getById(id);
            //模拟重建时的延时
            Thread.sleep(200);
            //5.若数据库中不存在，返回错误
            if (shop == null) {
                //缓存空值(null)
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.若数据库中存在，写入redis缓存(把POJO转为Json存入redis)
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回
        return shop;
    }

    //防止缓存穿透方法
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis缓存查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回(把Json解析为POJO)
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if ("".equals(shopJson)) {
            return null;
        }
        //4.缓存中不存在，则去数据库中根据id查询
        Shop shop = getById(id);
        //5.若数据库中不存在，返回错误
        if (shop == null) {
            //缓存空值(null)
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.若数据库中存在，写入redis缓存(把POJO转为Json存入redis)
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return null;
    }
}



