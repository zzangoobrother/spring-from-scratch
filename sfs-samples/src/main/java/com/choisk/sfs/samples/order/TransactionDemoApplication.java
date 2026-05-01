package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.order.controller.OrderController;
import com.choisk.sfs.samples.todo.config.AppConfig;

public class TransactionDemoApplication {

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {
            OrderController controller = ctx.getBean(OrderController.class);

            // 정상 흐름
            controller.placeOrder("Book", 5_000);
            // 예외 흐름 — outer rollback, inner audit는 살아남음
            controller.placeOrder("Yacht", 200_000);
        }
    }
}
