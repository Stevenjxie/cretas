package com.cretas.aims.service.cron.handler;

import com.cretas.aims.service.cron.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Sample {@link TaskHandler} that logs an echo line — Phase 5 skeleton reference.
 *
 * <p>Sister chat <b>keeps</b> this handler as a smoke-test target / example for
 * future handler authors. It is registered as bean name {@code echoTaskHandler}
 * (the default @Component naming derived from the class), which is referenced
 * as the {@code handler_bean_name} of any debug/sample {@code scheduled_tasks} row.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component("echoTaskHandler")
public class EchoTaskHandler implements TaskHandler {

    @Override
    public void execute(Map<String, Object> context) {
        log.info(
                "[EchoTaskHandler] ran at {} — taskId={}, taskCode={}, factoryId={}, lastRunAt={}",
                LocalDateTime.now(),
                context.get("taskId"),
                context.get("taskCode"),
                context.get("factoryId"),
                context.get("lastRunAt")
        );
    }
}
