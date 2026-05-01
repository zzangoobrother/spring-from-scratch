package com.choisk.sfs.samples.order.repository;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.order.domain.Order;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;

import java.util.List;

@Repository
public class OrderRepository {

    @Autowired private JdbcTemplate jdbc;

    public void save(Order order) {
        jdbc.update("INSERT INTO orders (item, amount) VALUES (?, ?)", order.item(), order.amount());
    }

    public List<Order> findAll() {
        return jdbc.query("SELECT id, item, amount FROM orders ORDER BY id",
                (rs, i) -> new Order(rs.getLong(1), rs.getString(2), rs.getInt(3)));
    }

    public long count() {
        return jdbc.query("SELECT COUNT(*) FROM orders", (rs, i) -> rs.getLong(1)).get(0);
    }
}
