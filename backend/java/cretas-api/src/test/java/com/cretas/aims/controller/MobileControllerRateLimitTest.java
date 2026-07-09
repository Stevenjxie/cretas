package com.cretas.aims.controller;

import com.cretas.aims.annotation.RateLimit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MobileControllerRateLimitTest {

    @Test
    void demoLoginIsIpRateLimited() throws Exception {
        Method method = MobileController.class.getDeclaredMethod(
                "demoLogin",
                String.class,
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class
        );

        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        assertThat(rateLimit).isNotNull();
        assertThat(rateLimit.count()).isEqualTo(20);
        assertThat(rateLimit.period()).isEqualTo(60);
        assertThat(rateLimit.limitType()).isEqualTo(RateLimit.LimitType.IP);
    }
}
