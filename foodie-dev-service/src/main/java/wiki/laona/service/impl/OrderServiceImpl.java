package wiki.laona.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.n3r.idworker.Sid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import wiki.laona.enums.OrderStatusEnum;
import wiki.laona.enums.YesOrNo;
import wiki.laona.mapper.OrderItemsMapper;
import wiki.laona.mapper.OrderStatusMapper;
import wiki.laona.mapper.OrdersMapper;
import wiki.laona.pojo.*;
import wiki.laona.pojo.bo.ShopcartBO;
import wiki.laona.pojo.bo.SubmitOrderBO;
import wiki.laona.pojo.vo.MerchantOrdersVO;
import wiki.laona.pojo.vo.OrderVO;
import wiki.laona.service.AddressService;
import wiki.laona.service.ItemService;
import wiki.laona.service.OrderService;
import wiki.laona.utils.DateUtil;
import wiki.laona.utils.RedisOperator;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author laona
 * @description 订单服务实现类
 * @since 2022-05-11 15:22
 **/
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private Sid sid;

    @Autowired
    private OrdersMapper ordersMapper;
    @Autowired
    private OrderItemsMapper orderItemsMapper;
    @Autowired
    private OrderStatusMapper orderStatusMapper;
    @Autowired
    private AddressService addressService;
    @Autowired
    private ItemService itemService;
    @Autowired
    private RedisOperator redisOperator;

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public OrderVO createOrder(List<ShopcartBO> shopcartList, SubmitOrderBO submitOrderBO) {

        String userId = submitOrderBO.getUserId();
        String addressId = submitOrderBO.getAddressId();
        String itemSpecIds = submitOrderBO.getItemSpecIds();
        Integer payMethod = submitOrderBO.getPayMethod();
        String leftMsg = submitOrderBO.getLeftMsg();
        // 设置邮费（默认包邮）
        Integer postAmount = 0;

        // 订单id
        String orderId = sid.nextShort();

        // 1. 新订单信息保存
        Orders newOrder = new Orders();
        newOrder.setId(orderId);
        newOrder.setUserId(userId);

        // 查询地址信息
        UserAddress address = addressService.queryUserAddress(userId, addressId);

        newOrder.setReceiverName(address.getReceiver());
        newOrder.setReceiverMobile(address.getMobile());
        newOrder.setReceiverAddress(
                String.format("%s %s %s %s",
                        address.getProvince(),
                        address.getCity(),
                        address.getDistrict(),
                        address.getDetail())
        );

        newOrder.setPostAmount(postAmount);

        newOrder.setPayMethod(payMethod);
        newOrder.setLeftMsg(leftMsg);

        newOrder.setIsComment(YesOrNo.NO.type);
        newOrder.setIsDelete(YesOrNo.NO.type);
        newOrder.setCreatedTime(new Date());
        newOrder.setUpdatedTime(new Date());

        // 2. 根据itemSpecIds报错订单商品信息表
        String[] itemSpecIdArr = itemSpecIds.split(",");
        // 商品原价累计
        Integer totalAmount = 0;
        // 优惠后的时机支付价格累计
        Integer realPayAmount = 0;
        List<ShopcartBO> toBeRemovedShopcartList = new ArrayList<>();
        for (String itemSpecId : itemSpecIdArr) {
            // 整合 redis 后，商品购买数量重新从 redis 的购物车中获取
            ShopcartBO cartItem = getBuyCountsFromShopcart(shopcartList, itemSpecId);
            int buyCounts = cartItem.getBuyCounts();
            // 添加到待删除购物车列表中
            toBeRemovedShopcartList.add(cartItem);

            // 2.1 获取商品规格
            ItemsSpec itemSpec = itemService.queryItemSpecById(itemSpecId);

            totalAmount += itemSpec.getPriceNormal() * buyCounts;
            realPayAmount += itemSpec.getPriceDiscount() * buyCounts;

            // 2.2 根据商品id，获得商品信息以及商品图片
            String itemId = itemSpec.getItemId();
            Items item = itemService.queryItemById(itemId);
            String imgUrl = itemService.queryItemMainImgById(itemId);

            // 2.3 循环保存子订单到数据库
            String subOrderId = sid.nextShort();
            OrderItems subOrderItem = new OrderItems();
            subOrderItem.setId(subOrderId);
            subOrderItem.setItemId(itemId);
            subOrderItem.setItemName(item.getItemName());
            subOrderItem.setItemImg(imgUrl);
            subOrderItem.setOrderId(orderId);
            subOrderItem.setBuyCounts(buyCounts);
            subOrderItem.setItemSpecId(itemSpecId);
            subOrderItem.setItemSpecName(itemSpec.getName());
            subOrderItem.setPrice(realPayAmount);
            orderItemsMapper.insert(subOrderItem);

            // 2.4 减少规格中的库存，在规格表中扣除库存
            itemService.decreaseItemSpecStock(itemSpecId, buyCounts);
        }

        newOrder.setTotalAmount(totalAmount);
        newOrder.setRealPayAmount(realPayAmount);
        ordersMapper.insert(newOrder);

        // 3. 保存订单状态表
        OrderStatus waitPayOrderStatus = new OrderStatus();
        waitPayOrderStatus.setOrderId(orderId);
        waitPayOrderStatus.setOrderStatus(OrderStatusEnum.WAIT_PAY.type);
        waitPayOrderStatus.setCreatedTime(new Date());
        orderStatusMapper.insert(waitPayOrderStatus);

        // 4. 构建商户订单，用于传给支付中心
        MerchantOrdersVO merchantOrdersVO = new MerchantOrdersVO();
        merchantOrdersVO.setMerchantOrderId(orderId);
        merchantOrdersVO.setMerchantUserId(userId);
        merchantOrdersVO.setAmount(realPayAmount + postAmount);
        merchantOrdersVO.setPayMethod(payMethod);

        // 5. 构建自定义订单 vo
        OrderVO orderVO = new OrderVO();
        orderVO.setOrderId(orderId);
        orderVO.setMerchantOrdersVO(merchantOrdersVO);
        // 待删除列表vo
        orderVO.setShopcartList(toBeRemovedShopcartList);

        return orderVO;
    }

    /**
     * 获取redis购物车中specId的商品数量
     *
     * @param shopcartList 购物车列表
     * @param itemSpecId       规格id
     * @return shopcartBO
     */
    private ShopcartBO getBuyCountsFromShopcart(List<ShopcartBO> shopcartList, String itemSpecId) {
        // 整合 redis 后，商品购买数量重新从 redis 的购物车中获取
        for (ShopcartBO sc : shopcartList) {
            // 设置数量
            if (ObjectUtils.nullSafeEquals(sc.getSpecId(), itemSpecId)) {
                return sc;
            }
        }
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public void updateOrderStatus(String orderId, Integer orderStatus) {

        OrderStatus paidStatus = new OrderStatus();
        paidStatus.setOrderId(orderId);
        paidStatus.setOrderStatus(orderStatus);
        paidStatus.setPayTime(new Date());

        orderStatusMapper.updateByPrimaryKeySelective(paidStatus);
    }

    @Transactional(propagation = Propagation.SUPPORTS, rollbackFor = Exception.class)
    @Override
    public OrderStatus queryOrderStatusInfo(String orderId) {
        return orderStatusMapper.selectByPrimaryKey(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public void closeOrder() {

        // 查询所有未之父订单，判断时间是否超时（1天）, 超时则关闭交易
        OrderStatus orderStatus = new OrderStatus();
        orderStatus.setOrderStatus(OrderStatusEnum.WAIT_PAY.type);
        List<OrderStatus> list = orderStatusMapper.select(orderStatus);
        for (OrderStatus os : list) {
            // 获得订单创建时间
            Date createdTime = os.getCreatedTime();
            int daysBetween = DateUtil.daysBetween(createdTime, new Date());
            if (daysBetween >= 1) {
                // 超过1天，关闭订单
                doCloseOrder(os.getOrderId());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    void doCloseOrder(String orderId) {

        OrderStatus closeOrderStatus = orderStatusMapper.selectByPrimaryKey(orderId);

        if (StringUtils.isBlank(orderId)) {
            logger.error("当前订单不存在: {}", orderId);
            return;
        }

        closeOrderStatus.setOrderStatus(OrderStatusEnum.CLOSE.type);
        closeOrderStatus.setCloseTime(new Date());
        orderStatusMapper.updateByPrimaryKeySelective(closeOrderStatus);
    }
}
