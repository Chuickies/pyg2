package com.pinyougou.order.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.pinyougou.mapper.TbOrderItemMapper;
import com.pinyougou.mapper.TbPayLogMapper;
import com.pinyougou.order.service.OrderService;
import com.pinyougou.pojo.TbOrderItem;
import com.pinyougou.pojo.TbPayLog;
import com.pinyougou.pojogroup.Cart;
import com.pinyougou.utils.IdWorker;
import org.springframework.beans.factory.annotation.Autowired;
import com.alibaba.dubbo.config.annotation.Service;
import com.github.abel533.entity.Example;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageHelper;
import com.pinyougou.mapper.TbOrderMapper;
import com.pinyougou.pojo.TbOrder;
import entity.PageResult;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 业务逻辑实现
 *
 * @author Steven
 */
@Service(timeout = 5000)
public class OrderServiceImpl implements OrderService {

    @Autowired
    private TbOrderMapper orderMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private IdWorker worker;
    @Autowired
    private TbOrderItemMapper orderItemMapper;
    @Autowired
    private TbPayLogMapper payLogMapper;

    /**
     * 查询全部
     */
    @Override
    public List<TbOrder> findAll() {
        return orderMapper.select(null);
    }

    /**
     * 按分页查询
     */
    @Override
    public PageResult findPage(int pageNum, int pageSize) {

        PageResult<TbOrder> result = new PageResult<TbOrder>();
        //设置分页条件
        PageHelper.startPage(pageNum, pageSize);

        //查询数据
        List<TbOrder> list = orderMapper.select(null);
        //保存数据列表
        result.setRows(list);

        //获取总记录数
        PageInfo<TbOrder> info = new PageInfo<TbOrder>(list);
        result.setTotal(info.getTotal());
        return result;
    }

    /**
     * 增加
     *
     * @param order 这个对象在前端，只有支付方式与收件人、用户等信息
     */
    @Override
    public void add(TbOrder order) {
        //先查询所有的购物车列表出来
        List<Cart> cartList = (List<Cart>) redisTemplate.boundHashOps("cartList").get(order.getUserId());

        long totalFee = 0;  //订单支付总金额
        //记录所有订单号
        List<Long> orderList = new ArrayList<>();
        //订单拆单-一个商家一张订单
        for (Cart cart : cartList) {
            //1、创建新的订单对象,绑定所需要属性
            TbOrder beSave = new TbOrder();
            //生成唯一订单号
            long orderId = worker.nextId();
            //记录订单号
            orderList.add(orderId);
            beSave.setOrderId(orderId);  //订单id
            double totalMoney = 0.0;  //实付金额(单个商家总金额)
            beSave.setPaymentType(order.getPaymentType());  //支付方式
            beSave.setStatus("1");  //未付款状态

            beSave.setCreateTime(new Date());
            beSave.setUpdateTime(beSave.getCreateTime());
            beSave.setUserId(order.getUserId());  //下单人
            beSave.setReceiverAreaName(order.getReceiverAreaName());//地址
            beSave.setReceiverMobile(order.getReceiverMobile());//手机号
            beSave.setReceiver(order.getReceiver());//收货人
            beSave.setSourceType(order.getSourceType());  //订单来源
            beSave.setSellerId(cart.getSellerId());  //商家Id

            //2、保存订单的商品列表
            for (TbOrderItem orderItem : cart.getOrderItemList()) {
                orderItem.setId(worker.nextId());
                //绑定订单号
                orderItem.setOrderId(orderId);
                //计算总金额
                totalMoney += orderItem.getTotalFee().doubleValue();
                orderItemMapper.insertSelective(orderItem);
            }
            //保存订单
            beSave.setPayment(new BigDecimal(totalMoney));
            //计算订单总金额
            totalFee += (long) (totalMoney * 100);
            orderMapper.insertSelective(beSave);
        }
        //清空购物车
        redisTemplate.boundHashOps("cartList").delete(order.getUserId());

        //生成日志并保存
        if ("1".equals(order.getPaymentType())) {
            TbPayLog payLog = new TbPayLog();
            //生成支付日志单号
            payLog.setOutTradeNo(worker.nextId() + "");
            payLog.setCreateTime(new Date());
            payLog.setTotalFee(totalFee);  //订单总金额(分)
            payLog.setUserId(order.getUserId());
            payLog.setTradeState("0");  //0未支付 1已支付
            payLog.setPayType("1");  //微信支付
            //转换订单列表以","分隔
            String orderIds = orderList.toString().replace("[", "").replace("]", "").replace(" ", "");
            payLog.setOrderList(orderIds);
            //把日志先保存数据库
            payLogMapper.insertSelective(payLog);
            //保存订单到redis
            redisTemplate.boundHashOps("payLogs").put(order.getUserId(), payLog);
        }
    }

    @Override
    public TbPayLog searchPayLogFromRedis(String userId) {
        TbPayLog payLog = (TbPayLog) redisTemplate.boundHashOps("payLogs").get(userId);
        return payLog;
    }

    @Override
    public void updateOrderStatus(String out_trade_no, String transaction_id) {
        //1. 修改支付日志状态
        TbPayLog payLog = payLogMapper.selectByPrimaryKey(out_trade_no);
        payLog.setTradeState("1");  //已支付
        payLog.setPayTime(new Date());  //支付时间
        payLog.setTransactionId(transaction_id);  //微信支付单号
        payLogMapper.updateByPrimaryKeySelective(payLog);
        //2. 修改关联的订单的状态
        String[] orderList = payLog.getOrderList().split(",");
        for (String orderId : orderList) {
            TbOrder beUpdate = new TbOrder();
            beUpdate.setOrderId(new Long(orderId));
            beUpdate.setStatus("2");  //已支付
            orderMapper.updateByPrimaryKeySelective(beUpdate);
        }
        //3. 清除缓存中的支付日志对象
        redisTemplate.boundHashOps("payLogs").delete(payLog.getUserId());
    }


    /**
     * 修改
     */
    @Override
    public void update(TbOrder order) {
        orderMapper.updateByPrimaryKeySelective(order);
    }

    /**
     * 根据ID获取实体
     *
     * @param id
     * @return
     */
    @Override
    public TbOrder findOne(Long id) {
        return orderMapper.selectByPrimaryKey(id);
    }

    /**
     * 批量删除
     */
    @Override
    public void delete(Long[] ids) {
        //数组转list
        List longs = Arrays.asList(ids);
        //构建查询条件
        Example example = new Example(TbOrder.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andIn("id", longs);

        //跟据查询条件删除数据
        orderMapper.deleteByExample(example);
    }


    @Override
    public PageResult findPage(TbOrder order, int pageNum, int pageSize) {
        PageResult<TbOrder> result = new PageResult<TbOrder>();
        //设置分页条件
        PageHelper.startPage(pageNum, pageSize);

        //构建查询条件
        Example example = new Example(TbOrder.class);
        Example.Criteria criteria = example.createCriteria();

        if (order != null) {
            //如果字段不为空
            if (order.getPaymentType() != null && order.getPaymentType().length() > 0) {
                criteria.andLike("paymentType", "%" + order.getPaymentType() + "%");
            }
            //如果字段不为空
            if (order.getPostFee() != null && order.getPostFee().length() > 0) {
                criteria.andLike("postFee", "%" + order.getPostFee() + "%");
            }
            //如果字段不为空
            if (order.getStatus() != null && order.getStatus().length() > 0) {
                criteria.andLike("status", "%" + order.getStatus() + "%");
            }
            //如果字段不为空
            if (order.getShippingName() != null && order.getShippingName().length() > 0) {
                criteria.andLike("shippingName", "%" + order.getShippingName() + "%");
            }
            //如果字段不为空
            if (order.getShippingCode() != null && order.getShippingCode().length() > 0) {
                criteria.andLike("shippingCode", "%" + order.getShippingCode() + "%");
            }
            //如果字段不为空
            if (order.getUserId() != null && order.getUserId().length() > 0) {
                criteria.andLike("userId", "%" + order.getUserId() + "%");
            }
            //如果字段不为空
            if (order.getBuyerMessage() != null && order.getBuyerMessage().length() > 0) {
                criteria.andLike("buyerMessage", "%" + order.getBuyerMessage() + "%");
            }
            //如果字段不为空
            if (order.getBuyerNick() != null && order.getBuyerNick().length() > 0) {
                criteria.andLike("buyerNick", "%" + order.getBuyerNick() + "%");
            }
            //如果字段不为空
            if (order.getBuyerRate() != null && order.getBuyerRate().length() > 0) {
                criteria.andLike("buyerRate", "%" + order.getBuyerRate() + "%");
            }
            //如果字段不为空
            if (order.getReceiverAreaName() != null && order.getReceiverAreaName().length() > 0) {
                criteria.andLike("receiverAreaName", "%" + order.getReceiverAreaName() + "%");
            }
            //如果字段不为空
            if (order.getReceiverMobile() != null && order.getReceiverMobile().length() > 0) {
                criteria.andLike("receiverMobile", "%" + order.getReceiverMobile() + "%");
            }
            //如果字段不为空
            if (order.getReceiverZipCode() != null && order.getReceiverZipCode().length() > 0) {
                criteria.andLike("receiverZipCode", "%" + order.getReceiverZipCode() + "%");
            }
            //如果字段不为空
            if (order.getReceiver() != null && order.getReceiver().length() > 0) {
                criteria.andLike("receiver", "%" + order.getReceiver() + "%");
            }
            //如果字段不为空
            if (order.getInvoiceType() != null && order.getInvoiceType().length() > 0) {
                criteria.andLike("invoiceType", "%" + order.getInvoiceType() + "%");
            }
            //如果字段不为空
            if (order.getSourceType() != null && order.getSourceType().length() > 0) {
                criteria.andLike("sourceType", "%" + order.getSourceType() + "%");
            }
            //如果字段不为空
            if (order.getSellerId() != null && order.getSellerId().length() > 0) {
                criteria.andLike("sellerId", "%" + order.getSellerId() + "%");
            }

        }

        //查询数据
        List<TbOrder> list = orderMapper.selectByExample(example);
        //保存数据列表
        result.setRows(list);

        //获取总记录数
        PageInfo<TbOrder> info = new PageInfo<TbOrder>(list);
        result.setTotal(info.getTotal());

        return result;
    }

}
