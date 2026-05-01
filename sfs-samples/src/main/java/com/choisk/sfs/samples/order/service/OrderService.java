package com.choisk.sfs.samples.order.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.order.domain.Order;
import com.choisk.sfs.samples.order.repository.OrderRepository;
import com.choisk.sfs.tx.annotation.Transactional;

@Service
public class OrderService {

    @Autowired private OrderRepository orderRepo;
    @Autowired private AuditService auditService;

    @Transactional
    public void placeOrder(String item, int amount) {
        orderRepo.save(Order.toCreate(item, amount));
        auditService.log("order placed: " + item);
        if (amount > 100_000) {
            throw new BusinessException("amount limit exceeded: " + amount);
        }
    }
}
