package com.chiho.wuagentscope.tools;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.*;

/**
 * 时间工具类
 * @author chiho
 */
@Component
public class TimeTool {

    @Tool(name = "get_current_date_time", description = "Get current date and time info, optional timezone ID. Example: Asia/Shanghai")
    public String getCurrentDateTime(
            @ToolParam(name = "timezone", description = "Optional IANA timezone ID, default system timezone") String timezone
    ) {
        try {
            ZoneId targetZone = StrUtil.isBlank(timezone) ? ZoneId.systemDefault() : ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(targetZone);
            LocalDate date = now.toLocalDate();
            LocalTime time = now.toLocalTime();
            LocalDateTime dateTime = now.toLocalDateTime();

            return String.join("\n",
                    "zone: " + targetZone.getId(),
                    "date: " + date,
                    "time: " + time,
                    "datetime: " + dateTime,
                    "epochMillis: " + Instant.now().toEpochMilli()
            );
        } catch (Exception e) {
            return "Error: invalid timezone. Please use a valid IANA timezone ID, e.g. Asia/Shanghai. details=" + e.getMessage();
        }
    }
}
