package com.example.bean_refresh.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Product(
        String id,
        String name,
        String category,
        BigDecimal price,
        int stock,
        LocalDateTime fetchedAt
) implements Serializable {
}

