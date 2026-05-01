package com.choisk.sfs.samples.order.controller;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.samples.order.service.BusinessException;
import com.choisk.sfs.samples.order.service.OrderService;

@Controller
public class OrderController {

    @Autowired private OrderService orderService;

    public void placeOrder(String item, int amount) {
        try {
            orderService.placeOrder(item, amount);
            System.out.println("[OrderController] order placed: " + item + " (amount=" + amount + ")");
        } catch (BusinessException e) {
            System.out.println("[OrderController] order failed: " + e.getMessage());
        }
    }
}
