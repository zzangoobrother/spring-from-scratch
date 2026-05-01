package com.choisk.sfs.samples.order.domain;

public record Order(Long id, String item, int amount) {

    public static Order toCreate(String item, int amount) {
        return new Order(null, item, amount);
    }
}
