package com.agent.software.bootstrap;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.config.ConfigLoader;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.tool.client.WebClientChannel;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;
import com.agent.software.web.ChatWebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 入口：加载配置 → 组装公司 → 招聘默认团队 → 读档 → 启动 → 日循环 → 退出落盘。
 *
 * <p>对应 master {@code Main}，但业务步骤全部下移到 {@link Company}，
 * 这里只做"驱动一次模拟"的编排。
 */
public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** 日循环的轮询间隔（毫秒）。 */
    private static final long DAY_POLL_MILLIS = 1_000L;

    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = new ConfigLoader().load();
        Transcript.Feed feed = new ChatFeed();
        CompanyBuilder builder = new CompanyBuilder(config,
                AppPaths.resolve(config.storage()), new JacksonJsonCodec())
                .withTranscript(feed);

        ChatWebServer web = null;
        if (config.web().port() > 0) {
            // Web 模式下客户输入走浏览器，客户通道因此绑定到同一个 feed
            builder.withClientChannel(new WebClientChannel((ChatFeed) feed));
        }

        Company company = builder.build();
        for (RoleSpec spec : defaultTeam(config)) {
            company.staffing().hire(spec);
        }

        if (config.web().port() > 0) {
            try {
                web = new ChatWebServer(company, feed, config.web().host(), config.web().port());
                web.start();
                logger.info("Web UI 已启动：http://{}:{}/", web.host(), web.port());
            } catch (RuntimeException e) {
                logger.warn("Web UI 启动失败（不影响主流程）：{}", e.getMessage());
                web = null;
            }
        }

        int restored = company.restore();
        if (restored > 0) {
            logger.info("从存档恢复 {} 个角色 → {}", restored, company.clock().describe());
        } else {
            logger.info("没有存档，从第 1 天 {} 开始", company.clock().currentDateTime());
        }

        company.start();
        logger.info("系统已启动：{}（1 tick = {} 模拟秒，班次 {}:00-{}:00）",
                company.clock().describe(),
                config.schedule().secondsPerTick(),
                config.schedule().shiftStartHour(),
                config.schedule().shiftEndHour());

        try {
            int day = company.clock().nowDay().day();
            while (!Thread.currentThread().isInterrupted()) {
                int current = company.clock().nowDay().day();
                if (current != day) {
                    day = current;
                    logger.info("进入第 {} 天 {}", day, company.clock().describe());
                    logRoster(company);
                }
                Thread.sleep(DAY_POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            logger.info("收到退出信号，保存进度并停止…");
            company.stop();
            if (web != null) {
                try {
                    web.stop();
                } catch (RuntimeException ignored) {
                    // 关闭 Web 失败不影响退出
                }
            }
            logger.info("已退出（运行到第 {} 天）", company.clock().nowDay().day());
        }
    }

    /** 由配置构建一个公司（供 main 与测试复用）。 */
    static Company buildCompany(AppConfig config) {
        Company company = new CompanyBuilder(config,
                AppPaths.resolve(config.storage()), new JacksonJsonCodec()).build();
        for (RoleSpec spec : defaultTeam(config)) {
            company.staffing().hire(spec);
        }
        return company;
    }

    /** 从 classpath 角色模板加载默认团队。 */
    static List<RoleSpec> defaultTeam(AppConfig config) {
        return RoleTemplates.load(new JacksonJsonCodec());
    }

    private static void logRoster(Company company) {
        company.team().snapshots().forEach(snapshot -> logger.info("  [{}] {} {} 队列={}",
                snapshot.id().value(), snapshot.name(), snapshot.state(), snapshot.queueDepth()));
    }
}
